package server.service;

import analysis.GameAnalyzer;
import com.github.bhlangonijr.chesslib.move.MoveList;
import main.Board;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class WebGameService {
    private static final String DEFAULT_SESSION = "default";
    private static final Pattern TAG_PAIR = Pattern.compile("(?m)^\\s*\\[[^\\n]*]\\s*$");
    private static final Pattern COMMENT = Pattern.compile("\\{[^}]*}|;[^\\n]*");
    private static final Pattern NAG = Pattern.compile("\\$\\d+");
    private static final Pattern MOVE_NUMBER = Pattern.compile("\\b\\d+\\s*\\.(?:\\.\\.)?");
    private static final Pattern RESULT = Pattern.compile("(?<!\\S)(1-0|0-1|1/2-1/2|\\*)(?!\\S)");

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();

    public WebGameService(@Value("${stockfish.path:}") String stockfishPath) {
        System.setProperty("java.awt.headless", "true");
        if(stockfishPath != null && !stockfishPath.isBlank() && System.getProperty("stockfish.path") == null){
            System.setProperty("stockfish.path", stockfishPath);
        }
    }

    public GameState state(String sessionId) {
        return session(sessionId).state();
    }

    public GameState startNewGame(String sessionId, NewGameRequest request) {
        return session(sessionId).startNewGame(request);
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

    public CompletableFuture<GameState> analyze(String sessionId) {
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
        String key = sessionId == null || sessionId.isBlank() ? DEFAULT_SESSION : sessionId;
        return sessions.computeIfAbsent(key, ignored -> new GameSession());
    }

    private final class GameSession {
        private final Board board;
        private String status = "White to move";
        private String evaluation = "Material even";
        private String analysisStatus = "idle";
        private List<String> analysisReport = List.of("Press Analyze to run a Stockfish-powered review.");
        private AnalysisFrameView analysisFrame;
        private GameAnalyzer.Summary analysisSummary;
        private boolean humanVsHuman = true;
        private boolean enginePlaysWhite = false;
        private int engineElo = 1200;
        private int timeMinutes = 10;

        GameSession() {
            board = new Board();
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
            board.startNewGame();
        }

        synchronized GameState startNewGame(NewGameRequest request) {
            applySettings(request);
            resetAnalysis("Press Analyze to run a Stockfish-powered review.");
            board.startNewGame();
            return state();
        }

        synchronized GameState updateSettings(SettingsRequest request) {
            applySettings(request);
            return state();
        }

        private void applySettings(SettingsRequest request) {
            if(request == null){
                return;
            }
            if(request.minutes() != null){
                timeMinutes = clamp(request.minutes(), 1, 60);
                board.setTimeControlMinutes(timeMinutes);
            }
            if(request.humanVsHuman() != null){
                humanVsHuman = request.humanVsHuman();
                board.setHumanVsHuman(humanVsHuman);
            }
            if(request.enginePlaysWhite() != null){
                enginePlaysWhite = request.enginePlaysWhite();
                board.setEnginePlaysWhite(enginePlaysWhite);
            }
            if(request.engineElo() != null){
                engineElo = clamp(request.engineElo(), 800, 2800);
                board.setEngineElo(engineElo);
            }
        }

        synchronized GameState state() {
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
                board.isAnalysisMode(),
                analysisStatus,
                analysisReport,
                AnalysisSummaryView.from(analysisSummary),
                AnalysisEntryView.from(analysisSummary),
                analysisFrame
            );
        }

        synchronized MoveResponse move(MoveRequest request) {
            boolean legal = board.playMove(request.fromCol(), request.fromRow(), request.toCol(), request.toRow());
            return new MoveResponse(legal, legal ? null : "Illegal move", state());
        }

        synchronized List<Board.SquareView> legalMoves(int col, int row) {
            return board.getLegalDestinations(col, row);
        }

        String pgn() {
            synchronized (this) {
                return board.getPgn(board.getLastResultTag());
            }
        }

        CompletableFuture<GameState> analyze() {
            CompletableFuture<GameState> future = new CompletableFuture<>();
            synchronized (this) {
                analysisStatus = "running";
                analysisReport = List.of("Analyzing game...");
                analysisSummary = null;
                analysisFrame = null;
            }
            board.analyzeGame(lines -> {
                synchronized (this) {
                    analysisReport = lines;
                    analysisStatus = "ready";
                    future.complete(state());
                }
            });
            return future.orTimeout(90, TimeUnit.SECONDS);
        }

        synchronized GameState importPgn(PgnImportRequest request) {
            String pgn = request == null ? "" : request.pgn();
            if(pgn == null || pgn.isBlank()){
                throw new IllegalArgumentException("Paste PGN text or upload a .pgn file first.");
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
            status = board.isGameOver() ? status : "Imported PGN. Ready to analyze.";
            return state();
        }

        synchronized GameState stepAnalysis(int delta) {
            board.stepAnalysis(delta);
            return state();
        }

        synchronized GameState exitAnalysis() {
            board.exitAnalysisReview();
            resetAnalysis("Press Analyze to run a Stockfish-powered review.");
            return state();
        }

        private void resetAnalysis(String message) {
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

    public record MoveRequest(int fromCol, int fromRow, int toCol, int toRow) {}

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
        double loss,
        String severity,
        Integer moveNumber,
        Boolean whiteMove
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
                entry == null ? 0 : entry.loss,
                entry == null ? null : entry.severity,
                frame.hasMove() ? frame.moveNumber() : null,
                frame.hasMove() ? frame.isWhiteMove() : null
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
