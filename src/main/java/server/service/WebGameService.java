package server.service;

import analysis.GameAnalyzer;
import analysis.Tactics;
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
import java.util.Map;
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
    // Review settings. Fixed depth keeps reviews reproducible; finished reviews are cached across sessions.
    private final int analysisDepth;
    private final int analysisMultiPv;
    private final GameAnalyzer.ResultCache analysisCache;
    // Review "explore" mode: stateless single-move analyses on a few warm engines. Results are cached per
    // position + move, and searches per position (the position after one explored move is the one before the next).
    private final int maxConcurrentExplores;
    private final Cache<String, ExploreView> exploreCache;
    private final Cache<String, List<uci.StockfishClient.Line>> positionCache;
    private EnginePool explorePool;
    private static final Pattern FEN = Pattern.compile(
        "[pnbrqkPNBRQK1-8]{1,8}(/[pnbrqkPNBRQK1-8]{1,8}){7} [wb] (-|[KQkq]{1,4}) (-|[a-h][36]) \\d{1,3} \\d{1,4}");

    @Autowired
    public WebGameService(@Value("${stockfish.path:}") String stockfishPath,
                          @Value("${chess.sessions.max:500}") int maxSessions,
                          @Value("${chess.sessions.idle-timeout:30m}") Duration idleTimeout,
                          @Value("${chess.engine.max-sessions:8}") int maxEngineSessions,
                          @Value("${chess.analysis.max-concurrent:2}") int maxConcurrentAnalyses,
                          @Value("${chess.analysis.depth:14}") int analysisDepth,
                          @Value("${chess.analysis.multipv:2}") int analysisMultiPv,
                          @Value("${chess.analysis.cache-size:200}") int analysisCacheSize,
                          @Value("${chess.explore.max-concurrent:4}") int maxConcurrentExplores) {
        this(stockfishPath, maxSessions, idleTimeout, maxEngineSessions, maxConcurrentAnalyses, Ticker.systemTicker(),
            analysisDepth, analysisMultiPv, analysisCacheSize, maxConcurrentExplores);
    }

    WebGameService(String stockfishPath, int maxSessions, Duration idleTimeout,
                   int maxEngineSessions, int maxConcurrentAnalyses, Ticker ticker) {
        this(stockfishPath, maxSessions, idleTimeout, maxEngineSessions, maxConcurrentAnalyses, ticker,
            GameAnalyzer.DEFAULT_DEPTH, GameAnalyzer.DEFAULT_MULTI_PV, 50, 4);
    }

    WebGameService(String stockfishPath, int maxSessions, Duration idleTimeout,
                   int maxEngineSessions, int maxConcurrentAnalyses, Ticker ticker,
                   int analysisDepth, int analysisMultiPv, int analysisCacheSize, int maxConcurrentExplores) {
        this.maxConcurrentExplores = Math.max(1, maxConcurrentExplores);
        this.exploreCache = Caffeine.newBuilder().maximumSize(2_000).build();
        this.positionCache = Caffeine.newBuilder().maximumSize(5_000).build();
        this.analysisDepth = Math.max(1, Math.min(30, analysisDepth));
        this.analysisMultiPv = Math.max(1, Math.min(5, analysisMultiPv));
        Cache<String, GameAnalyzer.Summary> summaries = Caffeine.newBuilder()
            .maximumSize(Math.max(0, analysisCacheSize))
            .build();
        this.analysisCache = new GameAnalyzer.ResultCache() {
            @Override
            public GameAnalyzer.Summary get(String key) {
                return summaries.getIfPresent(key);
            }

            @Override
            public void put(String key, GameAnalyzer.Summary summary) {
                summaries.put(key, summary);
            }
        };
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
        synchronized (this) {
            if(explorePool != null){
                explorePool.close();
                explorePool = null;
            }
        }
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

    /** Legal moves (UCI) in a position; no engine involved. */
    public ExploreLegalView exploreLegal(String sessionId, ExploreRequest request) {
        session(sessionId);
        com.github.bhlangonijr.chesslib.Board board = parseFen(request == null ? null : request.fen());
        return new ExploreLegalView(board.getFen(), board.legalMoves().stream().map(Object::toString).toList());
    }

    /**
     * Plays one move from any position and judges it like a reviewed game move: classification, explanation,
     * the better alternative and the evaluation afterwards. Stateless; used to try other moves during review.
     */
    public ExploreView explore(String sessionId, ExploreRequest request) {
        session(sessionId);
        com.github.bhlangonijr.chesslib.Board board = parseFen(request == null ? null : request.fen());
        String move = request.move() == null ? "" : request.move().trim().toLowerCase();
        if(!move.matches("[a-h][1-8][a-h][1-8][qrbn]?") || Tactics.parseLegal(board, move) == null){
            throw new IllegalArgumentException("Illegal move in this position: " + move);
        }
        String key = analysisDepth + ":" + analysisMultiPv + ":" + board.getFen() + ":" + move;
        ExploreView cached = exploreCache.getIfPresent(key);
        if(cached != null){
            return cached;
        }
        java.nio.file.Path engine = Board.configuredEnginePath();
        if(engine == null){
            throw new CapacityException("Stockfish is not available on this server.");
        }
        EnginePool pool = explorePool(engine);
        try {
            GameAnalyzer analyzer = new GameAnalyzer(engine, analysisDepth, analysisMultiPv);
            GameAnalyzer.Exploration result = analyzer.explore(board.getFen(), move, position -> {
                String positionKey = analysisDepth + ":" + analysisMultiPv + ":" + position;
                List<uci.StockfishClient.Line> known = positionCache.getIfPresent(positionKey);
                if(known != null){
                    return known;
                }
                List<uci.StockfishClient.Line> lines;
                try {
                    lines = pool.withEngine(3_000, client -> client.analyzeFen(position, analysisDepth));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Interrupted while waiting for the engine");
                }
                positionCache.put(positionKey, lines);
                return lines;
            });
            ExploreView view = ExploreView.from(result);
            exploreCache.put(key, view);
            return view;
        } catch (java.io.IOException e) {
            throw new CapacityException("Stockfish failed: " + e.getMessage());
        }
    }

    private synchronized EnginePool explorePool(java.nio.file.Path engine) {
        if(explorePool == null){
            // Two warm engines are kept for up to five minutes; more start on demand up to the cap.
            explorePool = new EnginePool(engine, maxConcurrentExplores, Math.min(2, maxConcurrentExplores),
                analysisMultiPv, 32, java.time.Duration.ofMinutes(5).toMillis());
        }
        return explorePool;
    }

    private static com.github.bhlangonijr.chesslib.Board parseFen(String fen) {
        String value = fen == null ? "" : fen.trim();
        if(value.length() > 100 || !FEN.matcher(value).matches()){
            throw new IllegalArgumentException("A valid FEN is required.");
        }
        try {
            com.github.bhlangonijr.chesslib.Board board = new com.github.bhlangonijr.chesslib.Board();
            board.loadFromFen(value);
            if(board.getPieceLocation(com.github.bhlangonijr.chesslib.Piece.WHITE_KING).size() != 1
                || board.getPieceLocation(com.github.bhlangonijr.chesslib.Piece.BLACK_KING).size() != 1){
                throw new IllegalArgumentException("A position needs exactly one king per side.");
            }
            return board;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("A valid FEN is required.");
        }
    }

    public GameState jumpAnalysis(String sessionId, int ply) {
        return session(sessionId).jumpAnalysis(ply);
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
    // Games vs Stockfish have no clock; this only sets how long the engine takes per move (Board's 10-minute pace).
    private static final int ENGINE_GAME_PACE_MINUTES = 10;

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
        // While a review runs: moves classified so far, so the client can fill in the graph and move list.
        private List<GameAnalyzer.Entry> partialEntries = List.of();
        private int analysisDone;
        private int analysisTotal;
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
            board.setAnalysisDepth(analysisDepth);
            board.setAnalysisMultiPv(analysisMultiPv);
            board.setAnalysisCache(analysisCache);
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
            resetBoard();
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
            resetBoard();
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

        /** Fresh starting position: timed for two players, untimed against Stockfish. */
        private void resetBoard() {
            if(humanVsHuman){
                board.startNewGame();
            } else {
                board.startUntimedGame();
            }
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
            board.setTimeControlMinutes(humanVsHuman ? timeMinutes : ENGINE_GAME_PACE_MINUTES);
        }

        synchronized GameState state() {
            ensureOpen();
            return new GameState(
                board.getPieceViews(),
                board.isWhiteToMove() ? "white" : "black",
                board.isGameActive(),
                board.isGameOver(),
                board.isClockStarted(),
                board.isUntimed(),
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
                "running".equals(analysisStatus) || analysisSummary != null
                    ? new AnalysisProgressView(analysisSummary != null ? analysisSummary.totalMoves : analysisDone,
                        analysisSummary != null ? analysisSummary.totalMoves : analysisTotal)
                    : null,
                analysisReport,
                AnalysisSummaryView.from(analysisSummary),
                AnalysisEntryView.from(analysisSummary != null ? analysisSummary.entries : partialEntries),
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
            partialEntries = List.of();
            analysisDone = 0;
            analysisTotal = board.getMoveHistorySnapshot().size();
            board.analyzeGame(lines -> {
                // Runs under this session's lock (callback executor) or synchronously from this method.
                releaseAnalysisSlot();
                if(generation != analysisGeneration){
                    return;
                }
                analysisReport = lines;
                analysisStatus = board.isAnalysisMode() ? "ready" : "failed";
                partialEntries = List.of();
            }, progress -> {
                if(generation != analysisGeneration){
                    return;
                }
                partialEntries = progress.entries;
                analysisDone = progress.done;
                analysisTotal = progress.total;
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
            // Replayed moves are history, not play: the imported game has no running clock.
            board.startUntimedGame();
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

        synchronized GameState jumpAnalysis(int ply) {
            ensureOpen();
            if(!board.isAnalysisMode()){
                throw new ConflictException("Run the game review first.");
            }
            board.jumpToAnalysisPly(ply);
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
            partialEntries = List.of();
            analysisDone = 0;
            analysisTotal = 0;
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
        boolean untimed,
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
        AnalysisProgressView analysisProgress,
        List<String> analysisReport,
        AnalysisSummaryView analysisSummary,
        List<AnalysisEntryView> analysisEntries,
        AnalysisFrameView analysisFrame
    ) {}

    public record AnalysisProgressView(int done, int total) {}

    public record ExploreRequest(String fen, String move) {}

    public record ExploreLegalView(String fen, List<String> legalMoves) {}

    /** One explored move: its judgement (like a review frame) plus the new position to continue from. */
    public record ExploreView(
        String fen,
        String uci,
        String san,
        int moveNumber,
        boolean whiteMove,
        String qualityTag,
        String explanation,
        String opening,
        String bestMove,
        String bestMoveSan,
        List<LinePlyView> bestLinePlies,
        double evalAfter,
        Integer mateAfter,
        double winAfter,
        boolean deliveredMate,
        String gameOver,
        List<String> legalMoves
    ) {
        static ExploreView from(GameAnalyzer.Exploration result) {
            GameAnalyzer.Entry entry = result.entry();
            List<LinePlyView> plies = Tactics.renderLine(entry.fenBefore,
                    entry.bestLine.subList(0, Math.min(AnalysisFrameView.MAX_LINE_PLIES, entry.bestLine.size()))).stream()
                .map(ply -> new LinePlyView(ply.uci(), ply.san(), ply.fen()))
                .toList();
            com.github.bhlangonijr.chesslib.Board after = new com.github.bhlangonijr.chesslib.Board();
            after.loadFromFen(result.fenAfter());
            String gameOver = after.isMated() ? "checkmate"
                : after.isStaleMate() ? "stalemate"
                : after.isInsufficientMaterial() ? "insufficient material" : null;
            return new ExploreView(result.fenAfter(), entry.playedMove, entry.san, entry.plyIndex / 2 + 1, entry.isWhite,
                entry.qualityTag, entry.explanation, entry.opening, entry.bestMove, entry.bestMoveSan, plies,
                entry.evalAfter, entry.mateAfter, entry.winAfter, after.isMated(), gameOver, result.legalMoves());
        }
    }

    public record AnalysisSummaryView(
        double whiteAccuracy,
        double blackAccuracy,
        double accuracyScore,
        int bestCount,
        int inaccuracies,
        int mistakes,
        int misses,
        int blunders,
        double avgLoss,
        double maxLoss,
        SideSummaryView white,
        SideSummaryView black,
        int totalMoves,
        String eco,
        String openingName
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
                summary.misses,
                summary.blunders,
                summary.avgLoss,
                summary.maxLoss,
                SideSummaryView.from(summary.white),
                SideSummaryView.from(summary.black),
                summary.totalMoves,
                summary.eco,
                summary.openingName
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
        int totalMoves,
        Map<String, Integer> counts
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
                summary.totalMoves,
                summary.counts
            );
        }
    }

    /** One step of the best line: UCI, SAN and the position after it (FEN), so clients can play it on the board. */
    public record LinePlyView(String uci, String san, String fen) {}

    public record AnalysisFrameView(
        int plyIndex,
        int totalPlies,
        String san,
        String qualityTag,
        String playedMove,
        String bestMove,
        String bestMoveSan,
        List<String> bestLine,
        List<LinePlyView> bestLinePlies,
        double evalBefore,
        double evalAfter,
        Integer mateBefore,
        Integer mateAfter,
        Double winBefore,
        Double winAfter,
        double loss,
        Double winLoss,
        String severity,
        String explanation,
        String opening,
        Integer moveNumber,
        Boolean whiteMove,
        boolean deliveredMate,
        String gameEndReason,
        String gameEndMessage,
        String positionFen
    ) {
        static final int MAX_LINE_PLIES = 10;
        private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

        /** FEN of the position shown at this frame (after the move), from the review entries. */
        private static String positionFen(Board.AnalysisFrame frame) {
            if(frame.summary == null || frame.summary.entries.isEmpty()){
                return frame.plyIndex < 0 ? START_FEN : null;
            }
            List<GameAnalyzer.Entry> entries = frame.summary.entries;
            if(frame.plyIndex < 0){
                return entries.get(0).fenBefore;
            }
            if(frame.plyIndex + 1 < entries.size()){
                return entries.get(frame.plyIndex + 1).fenBefore;
            }
            GameAnalyzer.Entry last = entries.get(Math.min(frame.plyIndex, entries.size() - 1));
            List<Tactics.LinePly> after = Tactics.renderLine(last.fenBefore, List.of(last.playedMove));
            return after.isEmpty() ? null : after.get(0).fen();
        }

        static AnalysisFrameView from(Board.AnalysisFrame frame) {
            if(frame == null){
                return null;
            }
            GameAnalyzer.Entry entry = frame.entry;
            List<LinePlyView> plies = entry == null ? List.of() : Tactics.renderLine(entry.fenBefore,
                    entry.bestLine.subList(0, Math.min(MAX_LINE_PLIES, entry.bestLine.size()))).stream()
                .map(ply -> new LinePlyView(ply.uci(), ply.san(), ply.fen()))
                .toList();
            return new AnalysisFrameView(
                frame.plyIndex,
                frame.totalPlies,
                frame.san,
                entry == null ? null : entry.qualityTag,
                entry == null ? null : entry.playedMove,
                entry == null ? null : entry.bestMove,
                entry == null ? null : entry.bestMoveSan,
                entry == null ? List.of() : entry.bestLine,
                plies,
                entry == null ? 0 : entry.evalBefore,
                entry == null ? 0 : entry.evalAfter,
                entry == null ? null : entry.mateBefore,
                entry == null ? null : entry.mateAfter,
                entry == null ? null : finite(entry.winBefore),
                entry == null ? null : finite(entry.winAfter),
                entry == null ? 0 : entry.loss,
                entry == null ? null : finite(entry.winLoss),
                entry == null ? null : entry.severity,
                entry == null ? null : entry.explanation,
                entry == null ? null : entry.opening,
                frame.hasMove() ? frame.moveNumber() : null,
                frame.hasMove() ? frame.isWhiteMove() : null,
                frame.deliveredMate,
                frame.gameEndReason == null ? null : frame.gameEndReason.code,
                frame.gameEndMessage,
                positionFen(frame)
            );
        }
    }

    /** Compact per-move data for the move list and evaluation graph (sent on every poll). */
    public record AnalysisEntryView(
        int plyIndex,
        boolean whiteMove,
        String san,
        String playedMove,
        String bestMove,
        double evalBefore,
        double evalAfter,
        Integer mateAfter,
        Double winAfter,
        double loss,
        String severity,
        String qualityTag
    ) {
        static List<AnalysisEntryView> from(List<GameAnalyzer.Entry> entries) {
            if(entries == null){
                return List.of();
            }
            return entries.stream()
                .map(entry -> new AnalysisEntryView(
                    entry.plyIndex,
                    entry.isWhite,
                    entry.san,
                    entry.playedMove,
                    entry.bestMove,
                    entry.evalBefore,
                    entry.evalAfter,
                    entry.mateAfter,
                    finite(entry.winAfter),
                    entry.loss,
                    entry.severity,
                    entry.qualityTag
                ))
                .toList();
        }
    }

    private static Double finite(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
    }
}
