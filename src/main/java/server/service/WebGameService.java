package server.service;

import analysis.GameAnalyzer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.bhlangonijr.chesslib.move.MoveList;
import main.Board;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

@Service
public class WebGameService {
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    static final int MAX_PGN_CHARS = 100_000;
    static final int MAX_ANALYSIS_PLIES = 300;
    private static final Pattern TAG_PAIR = Pattern.compile("(?m)^\\s*\\[[^\\n]*]\\s*$");
    private static final Pattern COMMENT = Pattern.compile("\\{[^}]*}|;[^\\n]*");
    private static final Pattern NAG = Pattern.compile("\\$\\d+");
    private static final Pattern MOVE_NUMBER = Pattern.compile("\\b\\d+\\s*\\.(?:\\.\\.)?");
    private static final Pattern RESULT = Pattern.compile("(?<!\\S)(1-0|0-1|1/2-1/2|\\*)(?!\\S)");

    private final Cache<String, GameSession> sessions;
    // Global caps on Stockfish work: live engine opponents and concurrent game reviews.
    private final Semaphore engineSlots;
    private final Semaphore analysisSlots;

    @Autowired
    public WebGameService(@Value("${stockfish.path:}") String stockfishPath,
                          @Value("${chess.sessions.max:500}") int maxSessions,
                          @Value("${chess.sessions.idle-timeout:30m}") Duration idleTimeout,
                          @Value("${chess.engine.max-sessions:8}") int maxEngineSessions,
                          @Value("${chess.analysis.max-concurrent:2}") int maxConcurrentAnalyses) {
        this(stockfishPath, maxSessions, idleTimeout, maxEngineSessions, maxConcurrentAnalyses, Ticker.systemTicker());
    }

    WebGameService(String stockfishPath, int maxSessions, Duration idleTimeout,
                   int maxEngineSessions, int maxConcurrentAnalyses, Ticker ticker) {
        System.setProperty("java.awt.headless", "true");
        if(stockfishPath != null && !stockfishPath.isBlank() && System.getProperty("stockfish.path") == null){
            System.setProperty("stockfish.path", stockfishPath);
        }
        this.engineSlots = new Semaphore(Math.max(0, maxEngineSessions));
        this.analysisSlots = new Semaphore(Math.max(0, maxConcurrentAnalyses));
        this.sessions = Caffeine.newBuilder()
            .maximumSize(Math.max(1, maxSessions))
            .expireAfterAccess(idleTimeout)
            .ticker(ticker)
            .scheduler(Scheduler.systemScheduler())
            .removalListener((String id, GameSession session, RemovalCause cause) -> {
                if(session != null){
                    session.dispose();
                }
            })
            .build();
    }

    @PreDestroy
    void shutdown() {
        sessions.invalidateAll();
        sessions.cleanUp();
    }

    long activeSessionCount() {
        sessions.cleanUp();
        return sessions.estimatedSize();
    }

    int availableEngineSlots() {
        return engineSlots.availablePermits();
    }

    int availableAnalysisSlots() {
        return analysisSlots.availablePermits();
    }

    void cleanUpSessions() {
        sessions.cleanUp();
    }

    public GameState state(String sessionId) {
        return session(sessionId).state();
    }

    /** Resets to the setup phase: a fresh board whose settings can be changed; nothing moves until {@link #startGame}. */
    public GameState startNewGame(String sessionId, NewGameRequest request) {
        return session(sessionId).startNewGame(request);
    }

    /** Applies the given settings and starts play; Stockfish moves first if it plays White. */
    public GameState startGame(String sessionId, NewGameRequest request) {
        return session(sessionId).startGame(request);
    }

    public GameState updateSettings(String sessionId, GameSettingsRequest request) {
        return session(sessionId).updateSettings(request);
    }

    public MoveResponse move(String sessionId, MoveRequest request) {
        return session(sessionId).move(request);
    }

    public List<Board.SquareView> legalMoves(String sessionId, int col, int row) {
        return session(sessionId).legalMoves(col, row);
    }

    public String pgn(String sessionId) {
        return session(sessionId).pgn();
    }

    public GameState analyze(String sessionId) {
        return session(sessionId).analyze();
    }

    public GameState importPgn(String sessionId, PgnImportRequest request) {
        return session(sessionId).importPgn(request);
    }

    public GameState stepAnalysis(String sessionId, int delta) {
        return session(sessionId).stepAnalysis(delta);
    }

    public GameState exitAnalysis(String sessionId) {
        return session(sessionId).exitAnalysis();
    }

    private GameSession session(String sessionId) {
        if(sessionId == null || !SESSION_ID.matcher(sessionId).matches()){
            throw new InvalidSessionException();
        }
        return sessions.get(sessionId, ignored -> new GameSession());
    }

    /** Missing or malformed X-Chess-Session header. */
    public static final class InvalidSessionException extends RuntimeException {
        InvalidSessionException() {
            super("Missing or invalid X-Chess-Session header. Reload the page to start a new session.");
        }
    }

    /** The request is valid but not allowed in the game's current state (engine's turn, review mode, ...). */
    public static final class ConflictException extends RuntimeException {
        ConflictException(String message) {
            super(message);
        }
    }

    /** A shared capacity limit (engine opponents, analyses) is exhausted. */
    public static final class CapacityException extends RuntimeException {
        CapacityException(String message) {
            super(message);
        }
    }

    private static final String SETUP_STATUS_ENGINE = "Choose your settings, then press Start game.";
    private static final String SETUP_STATUS_LOCAL = "Press Start game or make the first move.";

    private final class GameSession {
        private final Board board;
        // Setup phase: settings are configuration only and the engine is held until the game is explicitly started.
        private boolean awaitingStart = true;
        private volatile String status = "White to move";
        private volatile String evaluation = "Material even";
        private String analysisStatus = "idle";
        private List<String> analysisReport = List.of("Press Analyze to run a Stockfish-powered review.");
        private AnalysisFrameView analysisFrame;
        private GameAnalyzer.Summary analysisSummary;
        private boolean humanVsHuman = true;
        private boolean enginePlaysWhite = false;
        private int engineElo = 1200;
        private int timeMinutes = 10;
        private boolean holdsEngineSlot;
        // True while a Board analysis task is queued or running; that task owns one analysis slot.
        private boolean holdsAnalysisSlot;
        // Bumped by resetAnalysis so results of an abandoned analysis are not shown.
        private long analysisGeneration;
        private boolean disposed;

        GameSession() {
            // Engine moves and analysis results are applied under this session's lock, never on the Swing EDT.
            board = new Board(task -> {
                synchronized (this) {
                    if(!disposed){
                        task.run();
                    }
                }
            });
            board.setEngineOnHold(true);
            board.setTimeControlMinutes(timeMinutes);
            board.setHumanVsHuman(humanVsHuman);
            board.setEngineElo(engineElo);
            board.setEnginePlaysWhite(enginePlaysWhite);
            board.setStatusConsumer(message -> status = message);
            board.setGameEndConsumer(message -> status = message);
            board.setEvaluationConsumer(message -> evaluation = message);
            board.setAnalysisFrameConsumer(frame -> {
                analysisFrame = AnalysisFrameView.from(frame);
                analysisSummary = frame == null ? null : frame.summary;
            });
            enterSetup();
        }

        /** Fresh starting position with the current settings; clocks reset, no engine move. */
        private void enterSetup() {
            awaitingStart = true;
            board.setEngineOnHold(true);
            board.startNewGame();
            status = humanVsHuman ? SETUP_STATUS_LOCAL : SETUP_STATUS_ENGINE;
        }

        synchronized void dispose() {
            if(disposed){
                return;
            }
            disposed = true;
            board.dispose();
            releaseEngineSlot();
            releaseAnalysisSlot();
        }

        private void ensureOpen() {
            if(disposed){
                throw new ConflictException("This game session expired. Reload the page to start a new one.");
            }
        }

        private void releaseEngineSlot() {
            if(holdsEngineSlot){
                holdsEngineSlot = false;
                engineSlots.release();
            }
        }

        private void releaseAnalysisSlot() {
            if(holdsAnalysisSlot){
                holdsAnalysisSlot = false;
                analysisSlots.release();
            }
        }

        synchronized GameState startNewGame(NewGameRequest request) {
            ensureOpen();
            // Hold first so neither the settings nor the reset can trigger an engine move.
            board.setEngineOnHold(true);
            awaitingStart = true;
            applySettings(request);
            resetAnalysis("Press Analyze to run a Stockfish-powered review.");
            enterSetup();
            return state();
        }

        synchronized GameState startGame(NewGameRequest request) {
            ensureOpen();
            if(!awaitingStart){
                throw new ConflictException("A game is already in progress. Press New game first.");
            }
            applySettings(request);
            resetAnalysis("Press Analyze to run a Stockfish-powered review.");
            board.startNewGame();
            awaitingStart = false;
            board.setEngineOnHold(false); // Stockfish plays White's first move now, if it is White
            return state();
        }

        synchronized GameState updateSettings(SettingsRequest request) {
            ensureOpen();
            applySettings(request);
            if(awaitingStart){
                enterSetup(); // reflect the new time control / side on the idle board
            }
            return state();
        }

        private void applySettings(SettingsRequest request) {
            if(request == null){
                return;
            }
            if(!awaitingStart){
                boolean opponentChange = request.humanVsHuman() != null && request.humanVsHuman() != humanVsHuman;
                boolean sideChange = request.enginePlaysWhite() != null && request.enginePlaysWhite() != enginePlaysWhite;
                if(opponentChange || sideChange){
                    throw new ConflictException("Start a new game to change the opponent or side.");
                }
            }
            boolean wantsEngine = Boolean.FALSE.equals(request.humanVsHuman());
            if(wantsEngine && !holdsEngineSlot){
                if(!engineSlots.tryAcquire()){
                    throw new CapacityException("All Stockfish opponents are busy right now. Try again in a few minutes.");
                }
                holdsEngineSlot = true;
            }
            if(request.minutes() != null){
                timeMinutes = clamp(request.minutes(), 1, 60);
                board.setTimeControlMinutes(timeMinutes);
            }
            // Side first, so enabling the engine mid-game immediately plays for the correct colour.
            if(request.enginePlaysWhite() != null){
                enginePlaysWhite = request.enginePlaysWhite();
                board.setEnginePlaysWhite(enginePlaysWhite);
            }
            if(request.humanVsHuman() != null){
                humanVsHuman = request.humanVsHuman();
                board.setHumanVsHuman(humanVsHuman);
                if(humanVsHuman){
                    releaseEngineSlot();
                }
            }
            if(request.engineElo() != null){
                engineElo = clamp(request.engineElo(), 800, 2800);
                board.setEngineElo(engineElo);
            }
        }

        synchronized GameState state() {
            ensureOpen();
            return new GameState(
                board.getPieceViews(),
                board.isWhiteToMove() ? "white" : "black",
                board.isGameActive(),
                board.isGameOver(),
                board.isClockStarted(),
                status,
                evaluation,
                board.getWhiteClockText(),
                board.getBlackClockText(),
                board.getCapturedByWhiteText(),
                board.getCapturedByBlackText(),
                board.getDisplayMovesSnapshot(),
                board.getMoveHistorySnapshot(),
                board.getLastMoveView(),
                board.getLastResultTag(),
                humanVsHuman,
                enginePlaysWhite,
                engineElo,
                timeMinutes,
                awaitingStart,
                board.isEngineEnabled(),
                board.isEngineTurn(),
                board.isAnalysisMode(),
                analysisStatus,
                analysisReport,
                AnalysisSummaryView.from(analysisSummary),
                AnalysisEntryView.from(analysisSummary),
                analysisFrame
            );
        }

        synchronized MoveResponse move(MoveRequest request) {
            ensureOpen();
            if(request == null){
                throw new IllegalArgumentException("Move is required.");
            }
            if(board.isAnalysisMode()){
                throw new ConflictException("Exit the game review before making a move.");
            }
            if(awaitingStart && !humanVsHuman){
                throw new ConflictException("Press Start game to begin playing Stockfish.");
            }
            if(board.isEngineTurn()){
                throw new ConflictException("Wait for Stockfish to make its move.");
            }
            char promotion = parsePromotion(request.promotion());
            boolean legal = board.playMove(request.fromCol(), request.fromRow(), request.toCol(), request.toRow(), promotion);
            if(legal && awaitingStart){
                // Local two-player games may start with the first move; there is no engine to race.
                awaitingStart = false;
                board.setEngineOnHold(false);
            }
            return new MoveResponse(legal, legal ? null : "Illegal move", state());
        }

        synchronized List<Board.SquareView> legalMoves(int col, int row) {
            ensureOpen();
            if(awaitingStart && !humanVsHuman){
                return List.of();
            }
            return board.getLegalDestinations(col, row);
        }

        synchronized String pgn() {
            ensureOpen();
            return board.getPgn(board.getLastResultTag());
        }

        /** Starts a background review and returns immediately; clients poll {@code analysisStatus}. */
        synchronized GameState analyze() {
            ensureOpen();
            if(holdsAnalysisSlot){
                if("running".equals(analysisStatus)){
                    return state();
                }
                throw new ConflictException("The previous analysis is still finishing. Try again in a moment.");
            }
            if(board.getMoveHistorySnapshot().size() > MAX_ANALYSIS_PLIES){
                throw new IllegalArgumentException("Games longer than " + MAX_ANALYSIS_PLIES + " ply cannot be analyzed.");
            }
            if(!analysisSlots.tryAcquire()){
                throw new CapacityException("The server is busy analyzing other games. Try again in a minute.");
            }
            holdsAnalysisSlot = true;
            final long generation = ++analysisGeneration;
            analysisStatus = "running";
            analysisReport = List.of("Analyzing game...");
            analysisSummary = null;
            analysisFrame = null;
            board.analyzeGame(lines -> {
                // Runs under this session's lock (callback executor) or synchronously from this method.
                releaseAnalysisSlot();
                if(generation != analysisGeneration){
                    return;
                }
                analysisReport = lines;
                analysisStatus = board.isAnalysisMode() ? "ready" : "failed";
            });
            return state();
        }

        synchronized GameState importPgn(PgnImportRequest request) {
            ensureOpen();
            String pgn = request == null ? "" : request.pgn();
            if(pgn == null || pgn.isBlank()){
                throw new IllegalArgumentException("Paste PGN text or upload a .pgn file first.");
            }
            if(pgn.length() > MAX_PGN_CHARS){
                throw new IllegalArgumentException("PGN is too large (max " + MAX_PGN_CHARS / 1000 + " KB).");
            }

            List<String> moves = parsePgnToUci(pgn);
            if(moves.isEmpty()){
                throw new IllegalArgumentException("No moves found in the PGN.");
            }

            boolean wasHumanVsHuman = humanVsHuman;
            resetAnalysis("Imported " + moves.size() + " ply from PGN. Press Analyze to review it.");
            board.setHumanVsHuman(true);
            board.startNewGame();
            for(String move : moves){
                if(!board.playUciMove(move)){
                    board.setHumanVsHuman(wasHumanVsHuman);
                    throw new IllegalArgumentException("Could not replay imported move: " + move);
                }
            }
            humanVsHuman = true;
            board.setHumanVsHuman(true);
            releaseEngineSlot();
            awaitingStart = false;
            board.setEngineOnHold(false);
            status = board.isGameOver() ? status : "Imported PGN. Ready to analyze.";
            return state();
        }

        synchronized GameState stepAnalysis(int delta) {
            ensureOpen();
            board.stepAnalysis(delta);
            return state();
        }

        synchronized GameState exitAnalysis() {
            ensureOpen();
            board.exitAnalysisReview();
            resetAnalysis("Press Analyze to run a Stockfish-powered review.");
            return state();
        }

        private void resetAnalysis(String message) {
            analysisGeneration++;
            analysisStatus = "idle";
            analysisReport = List.of(message);
            analysisSummary = null;
            analysisFrame = null;
        }
    }

    private static List<String> parsePgnToUci(String pgn) {
        String cleaned = cleanPgn(pgn);
        try {
            MoveList moveList = new MoveList();
            moveList.loadFromSan(cleaned);
            return moveList.stream()
                .map(Object::toString)
                .toList();
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse PGN: " + e.getMessage(), e);
        }
    }

    private static String cleanPgn(String pgn) {
        String cleaned = TAG_PAIR.matcher(pgn).replaceAll(" ");
        cleaned = COMMENT.matcher(cleaned).replaceAll(" ");
        cleaned = stripParentheses(cleaned);
        cleaned = NAG.matcher(cleaned).replaceAll(" ");
        cleaned = MOVE_NUMBER.matcher(cleaned).replaceAll(" ");
        cleaned = RESULT.matcher(cleaned).replaceAll(" ");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private static String stripParentheses(String value) {
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for(int i = 0; i < value.length(); i++){
            char ch = value.charAt(i);
            if(ch == '('){
                depth++;
            } else if(ch == ')'){
                depth = Math.max(0, depth - 1);
            } else if(depth == 0){
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static char parsePromotion(String promotion) {
        if(promotion == null || promotion.isBlank()){
            return 'q';
        }
        String normalized = promotion.trim().toLowerCase();
        if(normalized.length() != 1 || "qrbn".indexOf(normalized.charAt(0)) < 0){
            throw new IllegalArgumentException("Promotion must be one of q, r, b or n.");
        }
        return normalized.charAt(0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record NewGameRequest(
        Integer minutes,
        Boolean humanVsHuman,
        Boolean enginePlaysWhite,
        Integer engineElo
    ) implements SettingsRequest {}

    public interface SettingsRequest {
        Integer minutes();
        Boolean humanVsHuman();
        Boolean enginePlaysWhite();
        Integer engineElo();
    }

    public record GameSettingsRequest(
        Integer minutes,
        Boolean humanVsHuman,
        Boolean enginePlaysWhite,
        Integer engineElo
    ) implements SettingsRequest {}

    public record MoveRequest(int fromCol, int fromRow, int toCol, int toRow, String promotion) {
        public MoveRequest(int fromCol, int fromRow, int toCol, int toRow) {
            this(fromCol, fromRow, toCol, toRow, null);
        }
    }

    public record MoveResponse(boolean legal, String error, GameState state) {}

    public record PgnImportRequest(String pgn) {}

    public record PgnResponse(String pgn) {}

    public record GameState(
        List<Board.PieceView> pieces,
        String turn,
        boolean active,
        boolean gameOver,
        boolean clockStarted,
        String status,
        String evaluation,
        String whiteClock,
        String blackClock,
        String capturedByWhite,
        String capturedByBlack,
        List<String> displayMoves,
        List<String> uciMoves,
        Board.MoveView lastMove,
        String result,
        boolean humanVsHuman,
        boolean enginePlaysWhite,
        int engineElo,
        int timeMinutes,
        boolean awaitingStart,
        boolean engineEnabled,
        boolean engineTurn,
        boolean analysisMode,
        String analysisStatus,
        List<String> analysisReport,
        AnalysisSummaryView analysisSummary,
        List<AnalysisEntryView> analysisEntries,
        AnalysisFrameView analysisFrame
    ) {}

    public record AnalysisSummaryView(
        double whiteAccuracy,
        double blackAccuracy,
        double accuracyScore,
        int bestCount,
        int inaccuracies,
        int mistakes,
        int blunders,
        double avgLoss,
        double maxLoss,
        SideSummaryView white,
        SideSummaryView black,
        int totalMoves
    ) {
        static AnalysisSummaryView from(GameAnalyzer.Summary summary) {
            if(summary == null){
                return null;
            }
            return new AnalysisSummaryView(
                summary.whiteAccuracy,
                summary.blackAccuracy,
                summary.accuracyScore,
                summary.bestCount,
                summary.inaccuracies,
                summary.mistakes,
                summary.blunders,
                summary.avgLoss,
                summary.maxLoss,
                SideSummaryView.from(summary.white),
                SideSummaryView.from(summary.black),
                summary.totalMoves
            );
        }
    }

    public record SideSummaryView(
        double accuracy,
        int best,
        int excellent,
        int good,
        int inaccuracies,
        int mistakes,
        int blunders,
        int totalMoves
    ) {
        static SideSummaryView from(GameAnalyzer.SideSummary summary) {
            if(summary == null){
                return null;
            }
            return new SideSummaryView(
                summary.accuracy,
                summary.best,
                summary.excellent,
                summary.good,
                summary.inaccuracies,
                summary.mistakes,
                summary.blunders,
                summary.totalMoves
            );
        }
    }

    public record AnalysisFrameView(
        int plyIndex,
        int totalPlies,
        String san,
        String qualityTag,
        String playedMove,
        String bestMove,
        List<String> bestLine,
        double evalBefore,
        double evalAfter,
        Integer mateBefore,
        Integer mateAfter,
        double loss,
        String severity,
        Integer moveNumber,
        Boolean whiteMove,
        boolean deliveredMate,
        String gameEndReason,
        String gameEndMessage
    ) {
        static AnalysisFrameView from(Board.AnalysisFrame frame) {
            if(frame == null){
                return null;
            }
            GameAnalyzer.Entry entry = frame.entry;
            return new AnalysisFrameView(
                frame.plyIndex,
                frame.totalPlies,
                frame.san,
                entry == null ? null : entry.qualityTag,
                entry == null ? null : entry.playedMove,
                entry == null ? null : entry.bestMove,
                entry == null ? List.of() : entry.bestLine,
                entry == null ? 0 : entry.evalBefore,
                entry == null ? 0 : entry.evalAfter,
                entry == null ? null : entry.mateBefore,
                entry == null ? null : entry.mateAfter,
                entry == null ? 0 : entry.loss,
                entry == null ? null : entry.severity,
                frame.hasMove() ? frame.moveNumber() : null,
                frame.hasMove() ? frame.isWhiteMove() : null,
                frame.deliveredMate,
                frame.gameEndReason == null ? null : frame.gameEndReason.code,
                frame.gameEndMessage
            );
        }
    }

    public record AnalysisEntryView(
        int plyIndex,
        boolean whiteMove,
        String playedMove,
        String bestMove,
        List<String> bestLine,
        double evalBefore,
        double evalAfter,
        double loss,
        String severity,
        String qualityTag
    ) {
        static List<AnalysisEntryView> from(GameAnalyzer.Summary summary) {
            if(summary == null){
                return List.of();
            }
            return summary.entries.stream()
                .map(entry -> new AnalysisEntryView(
                    entry.plyIndex,
                    entry.isWhite,
                    entry.playedMove,
                    entry.bestMove,
                    entry.bestLine,
                    entry.evalBefore,
                    entry.evalAfter,
                    entry.loss,
                    entry.severity,
                    entry.qualityTag
                ))
                .toList();
        }
    }
}
