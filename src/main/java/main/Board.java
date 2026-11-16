package main;

import analysis.GameAnalyzer;
import analysis.GameReviewFormatter;
import pieces.*;

import javax.swing.*;
import java.awt.*;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import javazoom.jl.player.Player;

import uci.StockfishClient;

public class Board extends JPanel {

    public String fenStartingPosition = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public int tileSize = 75;
    private final int coordinateMargin = 24;
    int cols = 8;
    int rows = 8;

    ArrayList<Piece> pieceList = new ArrayList<>();

    public Piece selectedPiece;

    Input input = new Input(this);

    public CheckScanner checkScanner = new CheckScanner(this);

    public int enPassantTile = -1;
    private Move lastMove;

    private boolean isWhiteToMove = true;
    private boolean isGameOver = false;
    private boolean isGameActive = false;

    private final ArrayList<String> moveHistory = new ArrayList<>();
    private final ArrayList<String> displayMoves = new ArrayList<>();
    private final ArrayList<String> capturedByWhite = new ArrayList<>();
    private final ArrayList<String> capturedByBlack = new ArrayList<>();
    private final ArrayList<String> sanHistory = new ArrayList<>();
    private StockfishClient stockfishClient;
    private ExecutorService engineExecutor;
    private ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private boolean engineEnabled = false;
    private boolean engineIsWhite = false;
    private volatile boolean engineThinking = false;
    // Bumped whenever a pending engine/analysis result must be ignored (new game, side change, shutdown, ...).
    private long engineRequestId = 0L;
    private long analysisRequestId = 0L;
    // Game review search settings (fixed depth => reproducible results) and an optional shared result cache.
    private int analysisDepth = 16;
    private int analysisMultiPv = GameAnalyzer.DEFAULT_MULTI_PV;
    private GameAnalyzer.ResultCache analysisCache;
    // While held (server setup phase), configuration changes never trigger an engine move.
    private boolean engineOnHold = false;
    // Server mode: no eager Stockfish, no Swing clock timer, async results applied via callbackExecutor instead of the EDT.
    private final boolean serverMode;
    private final Executor callbackExecutor;
    private boolean flipBoard = false;
    private int engineSkillLevel = 8;
    private int engineElo = 1200;
    private Duration engineThinkTime = Duration.ofMillis(1200);
    private int timeControlMinutes = 10;
    private long initialClockMillis = Duration.ofMinutes(10).toMillis();
    private long whiteClockMillis = initialClockMillis;
    private long blackClockMillis = initialClockMillis;
    private long lastClockUpdateMillis = 0L;
    private javax.swing.Timer clockTimer;
    private boolean clockStarted = false;
    // Untimed games (e.g. imported PGNs): the clock never starts, so no time is charged and nobody flags.
    private boolean untimed = false;
    private String lastResultTag = "*";
    // Why the game ended, decided by board rules / clocks (never by engine evaluation). Null while the game is running.
    private GameEndReason gameEndReason;
    private String gameEndMessage;
    private boolean isDraggingPiece = false;
    private int dragScreenX;
    private int dragScreenY;
    private byte[] moveSound;
    private byte[] captureSound;
    private byte[] promoteSound;
    private byte[] castleSound;
    private byte[] checkSound;
    private byte[] notifySound;
    private boolean soundEnabled = !GraphicsEnvironment.isHeadless();
    private Consumer<String> statusConsumer;
    private Consumer<String> gameEndConsumer;
    private Consumer<String> evaluationConsumer;
    private java.util.function.BiConsumer<String, String> captureConsumer;
    private java.util.function.Consumer<java.util.List<String>> moveLogConsumer;
    private java.util.function.BiConsumer<String, String> clockConsumer;
    private Path engineBinaryPath;
    private boolean lastMoveDeliveredCheck;
    private boolean lastMoveDeliveredMate;

    private boolean analysisMode = false;
    private int analysisPointer = -1;
    private final java.util.List<AnalysisSnapshot> analysisSnapshots = new java.util.ArrayList<>();
    private java.util.List<String> reviewUciMoves = java.util.List.of();
    private java.util.List<String> reviewSanMoves = java.util.List.of();
    private java.util.List<GameAnalyzer.Entry> reviewEntries = java.util.List.of();
    private GameAnalyzer.Summary lastAnalysisSummary;
    private java.util.function.Consumer<AnalysisFrame> analysisFrameConsumer;
    private MoveHighlight bestMoveArrow;
    private String currentQualityTag;
    private LiveGameState liveStateBackup;
    private final ArrayList<MoveHighlight> checkMarkers = new ArrayList<>();


    public Board() {
        this(null);
    }

    /**
     * Creates a board for headless server use when {@code callbackExecutor} is non-null. Engine moves and
     * analysis results are then handed to that executor (which must serialize them with the caller's own
     * locking) instead of the Swing EDT, Stockfish is only started when engine play is enabled, and the
     * clock is settled on read instead of by a Swing timer.
     */
    public Board(Executor callbackExecutor) {
        this.serverMode = callbackExecutor != null;
        this.callbackExecutor = serverMode ? callbackExecutor : SwingUtilities::invokeLater;
        this.setPreferredSize(new Dimension(cols * tileSize + coordinateMargin * 2, rows * tileSize + coordinateMargin * 2));
        this.setBackground(new Color(43, 43, 43));
        this.setOpaque(true);
        this.addMouseListener(input);
        this.addMouseMotionListener(input);
        loadPositionFromFEN(fenStartingPosition);
        if(serverMode){
            resolveEnginePath();
        } else {
            initializeEngineIntegration();
        }
        loadSounds();
        updateCheckMarkers();
    }

    private LiveGameState captureLiveGameState(){
        java.util.List<PieceState> piecesSnapshot = pieceList.stream()
            .map(p -> new PieceState(p.name, p.col, p.row, p.isWhite, p.isFirstMove))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        StoredMove storedMove = null;
        if(lastMove != null){
            storedMove = new StoredMove(lastMove.oldCol, lastMove.oldRow, lastMove.newCol, lastMove.newRow);
        }
        return new LiveGameState(
            piecesSnapshot,
            new ArrayList<>(capturedByWhite),
            new ArrayList<>(capturedByBlack),
            new ArrayList<>(moveHistory),
            new ArrayList<>(displayMoves),
            new ArrayList<>(sanHistory),
            storedMove,
            isWhiteToMove,
            isGameActive,
            isGameOver,
            engineEnabled,
            engineIsWhite,
            flipBoard,
            enPassantTile,
            whiteClockMillis,
            blackClockMillis,
            clockStarted,
            lastResultTag,
            lastMoveDeliveredCheck,
            lastMoveDeliveredMate
        );
    }

    private void restoreLiveGameState(){
        if(liveStateBackup == null){
            analysisMode = false;
            analysisSnapshots.clear();
            reviewEntries = java.util.List.of();
            reviewUciMoves = java.util.List.of();
            reviewSanMoves = java.util.List.of();
            analysisPointer = -1;
            lastAnalysisSummary = null;
            bestMoveArrow = null;
            currentQualityTag = null;
            notifyAnalysisFrame();
            repaint();
            return;
        }

        analysisMode = false;
        reviewEntries = java.util.List.of();
        reviewUciMoves = java.util.List.of();
        reviewSanMoves = java.util.List.of();
        analysisSnapshots.clear();
        analysisPointer = -1;
        lastAnalysisSummary = null;
        bestMoveArrow = null;
        currentQualityTag = null;

        pieceList.clear();
        for(PieceState state : liveStateBackup.pieces){
            Piece piece = createPieceByName(state.name, state.col, state.row, state.isWhite);
            if(piece != null){
                piece.isFirstMove = state.wasFirstMove;
                pieceList.add(piece);
            }
        }
        capturedByWhite.clear();
        capturedByWhite.addAll(liveStateBackup.capturedByWhite);
        capturedByBlack.clear();
        capturedByBlack.addAll(liveStateBackup.capturedByBlack);
        moveHistory.clear();
        moveHistory.addAll(liveStateBackup.moveHistory);
        displayMoves.clear();
        displayMoves.addAll(liveStateBackup.displayMoves);
        sanHistory.clear();
        sanHistory.addAll(liveStateBackup.sanHistory);
        isWhiteToMove = liveStateBackup.isWhiteToMove;
        isGameActive = liveStateBackup.isGameActive;
        isGameOver = liveStateBackup.isGameOver;
        engineEnabled = liveStateBackup.engineEnabled;
        engineIsWhite = liveStateBackup.engineIsWhite;
        flipBoard = liveStateBackup.flipBoard;
        enPassantTile = liveStateBackup.enPassantTile;
        whiteClockMillis = liveStateBackup.whiteClockMillis;
        blackClockMillis = liveStateBackup.blackClockMillis;
        clockStarted = liveStateBackup.clockStarted;
        lastResultTag = liveStateBackup.lastResultTag;
        lastMoveDeliveredCheck = liveStateBackup.lastMoveDeliveredCheck;
        lastMoveDeliveredMate = liveStateBackup.lastMoveDeliveredMate;
        lastMove = rebuildStoredMove(liveStateBackup.lastMove);

        notifyCaptures();
        notifyEvaluation();
        notifyMoveLog();
        notifyClock();
        updateCheckMarkers();
        if(engineEnabled){
            requestEngineMoveIfNeeded();
        }

        liveStateBackup = null;
        notifyAnalysisFrame();
        repaint();
    }

    private Move rebuildStoredMove(StoredMove stored){
        if(stored == null){
            return null;
        }
        Piece piece = getPiece(stored.newCol, stored.newRow);
        if(piece == null){
            return null;
        }
        Move restored = new Move(this, piece, stored.newCol, stored.newRow);
        restored.oldCol = stored.oldCol;
        restored.oldRow = stored.oldRow;
        return restored;
    }

    public void setStatusConsumer(Consumer<String> consumer){
        this.statusConsumer = consumer;
    }

    public void setGameEndConsumer(Consumer<String> consumer){
        this.gameEndConsumer = consumer;
    }

    public void setEvaluationConsumer(Consumer<String> consumer){
        this.evaluationConsumer = consumer;
    }

    public void setCaptureConsumer(java.util.function.BiConsumer<String, String> consumer){
        this.captureConsumer = consumer;
    }

    public void setMoveLogConsumer(java.util.function.Consumer<java.util.List<String>> consumer){
        this.moveLogConsumer = consumer;
    }

    public void setClockConsumer(java.util.function.BiConsumer<String, String> consumer){
        this.clockConsumer = consumer;
    }

    public void setTimeControlMinutes(int minutes){
        int sanitized = Math.max(1, minutes);
        timeControlMinutes = sanitized;
        initialClockMillis = Duration.ofMinutes(sanitized).toMillis();
        if(!isGameActive){
            whiteClockMillis = initialClockMillis;
            blackClockMillis = initialClockMillis;
            notifyClock();
        }
    }

    public void setEngineSkillLevel(int level) {
        this.engineSkillLevel = Math.max(0, Math.min(20, level));
        configureEngine();
    }

    public void setEngineElo(int elo){
        this.engineElo = Math.max(800, Math.min(2800, elo));
        updateEngineSkillLevelForElo();
        updateEngineThinkTimeForElo();
        configureEngine();
    }

    public void setEngineThinkTimeMillis(long millis){
        this.engineThinkTime = Duration.ofMillis(Math.max(100, millis));
    }

    public void setEnginePlaysWhite(boolean playsWhite){
        if(engineIsWhite != playsWhite){
            cancelPendingEngineMove();
        }
        this.engineIsWhite = playsWhite;
        this.flipBoard = playsWhite;
        if(isGameActive && isWhiteToMove == engineIsWhite){
            requestEngineMoveIfNeeded();
        }
    }

    public void setHumanVsHuman(boolean enabled){
        this.engineEnabled = !enabled && stockfishClient != null;
        if(enabled){
            engineThinking = false;
            shutdownEngine();
        } else if(stockfishClient == null){
            initializeEngineIntegration();
        }
        if(engineEnabled && isGameActive){
            requestEngineMoveIfNeeded();
        }
    }

    public boolean isGameActive(){
        return isGameActive;
    }

    public void startNewGame(){
        untimed = false;
        clearAnalysisReview();
        gameEndReason = null;
        gameEndMessage = null;
        loadPositionFromFEN(fenStartingPosition);
        isGameActive = true;
        lastResultTag = "*";
        resetClock();
        if(statusConsumer != null){
            statusConsumer.accept("Game started. White to move.");
        }
        if(stockfishClient != null){
            try{
                stockfishClient.newGame();
                configureEngine();
            } catch (IOException e){
                System.err.println("Unable to reset engine: " + e.getMessage());
            }
        }
        capturedByWhite.clear();
        capturedByBlack.clear();
        displayMoves.clear();
        sanHistory.clear();
        notifyMoveLog();
        notifyCaptures();
        if(engineIsWhite){
            requestEngineMoveIfNeeded();
        }
        notifyEvaluation();
        repaint();
    }

    /**
     * A fresh game without a clock, used for replaying imported games: moves are recorded as usual,
     * but neither clock starts or runs, in live play or in review. {@link #startNewGame()} is timed again.
     */
    public synchronized void startUntimedGame(){
        startNewGame();
        untimed = true;
        notifyClock();
    }

    public synchronized boolean isUntimed(){
        return untimed;
    }

    public void enableManualSetup(){
        isGameActive = true;
        isGameOver = false;
    }

    public Piece getPiece(int col, int row){

        for(Piece piece : pieceList){
            if(piece.col == col && piece.row == row){
                return piece;
            }
        }

        return null;
    }

    public void makeMove(Move move){
        makeMove(move, true);
    }

    private void makeMove(Move move, boolean triggerSideEffects){
        if(triggerSideEffects){
            settleClock();
        }
        if(triggerSideEffects && !isGameActive){
            return;
        }

        boolean wasWhiteToMove = isWhiteToMove;
        boolean enPassantCapture = isEnPassantCapture(move);
        boolean promotionMove = isPromotionMove(move);
        boolean kingSideCastle = isKingSideCastle(move);
        boolean queenSideCastle = isQueenSideCastle(move);
        boolean captureMove = move.capture != null || enPassantCapture;
        String sanCore = buildSanCore(move, captureMove, promotionMove, kingSideCastle, queenSideCastle);

        if(move.piece.name.equals("Pawn")){
            movePawn(move);
        } else if(move.piece.name.equals("King")){
            moveKing(move);
        }
        if(!move.piece.name.equals("Pawn")){
            // An en passant chance only exists for the move directly after a double pawn push.
            enPassantTile = -1;
        }

        move.piece.col = move.newCol;
        move.piece.row = move.newRow;
        move.piece.xPos = boardToPixelX(move.piece.col);
        move.piece.yPos = boardToPixelY(move.piece.row);

        move.piece.isFirstMove = false;

        capture(move.capture, triggerSideEffects);

        isWhiteToMove = !isWhiteToMove;

        lastMove = move;

        if(triggerSideEffects){
            updateGameState();
        }

        if(triggerSideEffects){
            String notation = sanCore;
            if(lastMoveDeliveredMate){
                notation += "#";
            } else if(lastMoveDeliveredCheck){
                notation += "+";
            }

            recordMove(move, notation);

            if(!clockStarted && wasWhiteToMove){
                startClock();
            }
            requestEngineMoveIfNeeded();
            notifyEvaluation();
            repaint();

            byte[] moveSoundToPlay = moveSound;
            if(move.isCastle){
                moveSoundToPlay = castleSound;
            } else if(move.isPromotion){
                moveSoundToPlay = promoteSound;
            } else if(move.capture != null){
                moveSoundToPlay = captureSound;
            }
            playSound(moveSoundToPlay);
        }
        updateCheckMarkers();
    }

    private void moveKing(Move move){

        if(Math.abs(move.piece.col - move.newCol) == 2){
            int rookStartCol = move.piece.col < move.newCol ? 7 : 0;
            int rookTargetCol = move.piece.col < move.newCol ? 5 : 3;

            Piece rook = getPiece(rookStartCol, move.piece.row);
            if(rook != null && "Rook".equals(rook.name)){
                rook.col = rookTargetCol;
                rook.row = move.piece.row;
                rook.xPos = boardToPixelX(rook.col);
                rook.yPos = boardToPixelY(rook.row);
                rook.isFirstMove = false;
                move.isCastle = true;
            }
        }

    }

    private void movePawn(Move move) {

        //en passant
        int colorIndex = move.piece.isWhite ? 1 : -1;

        if(getTileNum(move.newCol, move.newRow) == enPassantTile && move.newCol != move.oldCol){
            Piece passed = getPiece(move.newCol, move.newRow + colorIndex);
            if(passed != null && "Pawn".equals(passed.name) && passed.isWhite != move.piece.isWhite){
                move.capture = passed;
            }
        }

        if(Math.abs(move.piece.row - move.newRow) == 2){
            enPassantTile = getTileNum(move.newCol, move.newRow + colorIndex);
        } else {
            enPassantTile = -1;
        }

        //Promotion
        colorIndex = move.piece.isWhite ? 0 : 7;
        if(move.newRow == colorIndex){
            promotePawn(move);
        }
    }

    private void promotePawn(Move move) {
        pieceList.add(createPromotionPiece(move.promotion, move.newCol, move.newRow, move.piece.isWhite));
        removePiece(move.piece);
        move.isPromotion = true;
    }

    private Piece createPromotionPiece(char promotion, int col, int row, boolean isWhite){
        switch (promotion){
            case 'r':
                return new Rook(this, col, row, isWhite);
            case 'b':
                return new Bishop(this, col, row, isWhite);
            case 'n':
                return new Knight(this, col, row, isWhite);
            default:
                return new Queen(this, col, row, isWhite);
        }
    }

    static boolean isValidPromotion(char promotion){
        return promotion == 'q' || promotion == 'r' || promotion == 'b' || promotion == 'n';
    }

    public void capture(Piece piece, boolean notify){
        if(piece == null){
            return;
        }
        if(piece.isWhite){
            capturedByBlack.add(pieceIcon(piece));
        } else {
            capturedByWhite.add(pieceIcon(piece));
        }
        removePiece(piece);
        if(notify){
            notifyCaptures();
            playSound(captureSound);
        }
    }

    private void removePiece(Piece piece){
        pieceList.remove(piece);
    }

    public boolean isValidMove(Move move){

        if(isGameOver){
            return false;
        }

        if(!isInsideBoard(move.oldCol, move.oldRow) || !isInsideBoard(move.newCol, move.newRow)){
            return false;
        }

        if(move.piece.isWhite != isWhiteToMove){
            return false;
        }

        if(sameTeam(move.piece, move.capture)){
            return false;
        }

        if(move.capture != null && "King".equals(move.capture.name)){
            return false;
        }

        if(!move.piece.isValidMovement(move.newCol, move.newRow)){
            return false;
        }

        if(move.piece.moveCollidesWithPiece(move.newCol, move.newRow)){
            return false;
        }

        if(checkScanner.isKingChecked(move)){
            return false;
        }

        return true;
    }

    public boolean sameTeam(Piece p1, Piece p2){
        if(p1 == null || p2 == null){
            return false;
        }
        return p1.isWhite == p2.isWhite;
    }

    public int getTileNum(int col, int row){
        return row * cols + col;
    }

    Piece findKing(boolean isWhite){
        for(Piece piece : pieceList){
            if(isWhite == piece.isWhite && piece.name.equals("King")){
                return piece;
            }
        }
        return null;
    }

    public boolean isInsideBoard(int col, int row){
        return col >= 0 && col < cols && row >= 0 && row < rows;
    }

    public void loadPositionFromFEN(String fenString){
        pieceList.clear();
        moveHistory.clear();
        lastMove = null;
        isGameOver = false;
        capturedByWhite.clear();
        capturedByBlack.clear();
        displayMoves.clear();
        sanHistory.clear();
        lastResultTag = "*";
        String[] parts = fenString.split(" ");

        String position = parts[0];
        int row = 0;
        int col = 0;
        for(int i = 0; i < position.length(); i++){
            char ch = position.charAt(i);
            if(ch == '/'){
                row++;
                col = 0;
            } else if (Character.isDigit(ch)){
                col += Character.getNumericValue(ch);
            } else {
                boolean isWhite = Character.isUpperCase(ch);
                char pieceChar = Character.toLowerCase(ch);

                switch (pieceChar){
                    case 'r':
                        pieceList.add(new Rook(this, col, row, isWhite));
                        break;
                    case 'n':
                        pieceList.add(new Knight(this, col, row, isWhite));
                        break;
                    case 'b':
                        pieceList.add(new Bishop(this, col, row, isWhite));
                        break;
                    case 'q':
                        pieceList.add(new Queen(this, col, row, isWhite));
                        break;
                    case 'k':
                        pieceList.add(new King(this, col, row, isWhite));
                        break;
                    case 'p':
                        pieceList.add(new Pawn(this, col, row, isWhite));
                }
                col++;
            }
        }

        isWhiteToMove = parts[1].equals("w");

        Piece bqr = getPiece(0,0);
        if(bqr instanceof Rook){
            bqr.isFirstMove = parts[2].contains("q");
        }
        Piece bkr = getPiece(7,0);
        if(bkr instanceof Rook){
            bkr.isFirstMove = parts[2].contains("k");
        }
        Piece wqr = getPiece(0,7);
        if(wqr instanceof Rook){
            wqr.isFirstMove = parts[2].contains("Q");
        }
        Piece wkr = getPiece(7,7);
        if(wkr instanceof Rook){
            wkr.isFirstMove = parts[2].contains("K");
        }

        if(parts[3].equals("-")) {
            enPassantTile = -1;
        } else {
            enPassantTile = (7 - (parts[3].charAt(1) - '1')) * 8 + (parts[3].charAt(0) - 'a');
        }
        notifyCaptures();
        notifyEvaluation();
        updateCheckMarkers();
    }

    private void updateGameState(){
        if(!isGameActive){
            return;
        }

        lastMoveDeliveredCheck = false;
        lastMoveDeliveredMate = false;

        Piece king = findKing(isWhiteToMove);
        Move kingMove = new Move(this, king, king.col, king.row);
        boolean kingChecked = checkScanner.isKingChecked(kingMove);
        lastMoveDeliveredCheck = kingChecked;
        if(checkScanner.isGameOver(king)){
            if(kingChecked){
                lastMoveDeliveredMate = true;
                finishGame(GameEndReason.CHECKMATE, isWhiteToMove ? Boolean.FALSE : Boolean.TRUE);
            } else {
                finishGame(GameEndReason.STALEMATE, null);
            }
        } else if (insufficientMaterial(true) && insufficientMaterial(false)) {
            finishGame(GameEndReason.INSUFFICIENT_MATERIAL, null);
        } else if (kingChecked){
            playSound(checkSound);
            if(statusConsumer != null){
                statusConsumer.accept((isWhiteToMove ? "White" : "Black") + " is in check!");
            }
        } else if(statusConsumer != null){
            statusConsumer.accept(isWhiteToMove ? "White to move" : "Black to move");
        }
    }

    /** How a finished game ended. Only board rules and clocks decide this. */
    public enum GameEndReason {
        CHECKMATE("checkmate"),
        TIMEOUT("timeout"),
        STALEMATE("stalemate"),
        INSUFFICIENT_MATERIAL("insufficient-material");

        public final String code;

        GameEndReason(String code) {
            this.code = code;
        }
    }

    /** @param whiteWon TRUE/FALSE for a decisive result, null for a draw. */
    private void finishGame(GameEndReason reason, Boolean whiteWon){
        String winner = Boolean.TRUE.equals(whiteWon) ? "White" : "Black";
        String message = switch (reason) {
            case CHECKMATE -> winner + " won by checkmate";
            case TIMEOUT -> winner + " won on time";
            case STALEMATE -> "Draw by stalemate";
            case INSUFFICIENT_MATERIAL -> "Draw by insufficient material";
        };
        isGameOver = true;
        isGameActive = false;
        gameEndReason = reason;
        gameEndMessage = message;
        lastClockUpdateMillis = 0L;
        if(clockTimer != null){
            clockTimer.stop();
        }
        playSound(notifySound);
        lastResultTag = whiteWon == null ? "1/2-1/2" : (whiteWon ? "1-0" : "0-1");
        if(statusConsumer != null){
            statusConsumer.accept(message);
        }
        if(gameEndConsumer != null){
            gameEndConsumer.accept(message);
        }
        notifyEvaluation();
    }

    private void notifyEvaluation(){
        if(evaluationConsumer == null){
            return;
        }
        int whiteScore = materialScore(true);
        int blackScore = materialScore(false);
        int diff = whiteScore - blackScore;
        String evaluationText;
        if(diff > 0){
            evaluationText = "White +" + diff;
        } else if(diff < 0){
            evaluationText = "Black +" + Math.abs(diff);
        } else {
            evaluationText = "Material even";
        }
        evaluationConsumer.accept(evaluationText);
    }

    private int materialScore(boolean forWhite){
        return pieceList.stream()
            .filter(p -> p.isWhite == forWhite)
            .mapToInt(p -> p.value)
            .sum();
    }

    private void notifyCaptures(){
        if(captureConsumer == null){
            return;
        }
        captureConsumer.accept(formatCapturedList(capturedByWhite), formatCapturedList(capturedByBlack));
    }

    private void notifyMoveLog(){
        if(moveLogConsumer == null){
            return;
        }
        moveLogConsumer.accept(new ArrayList<>(displayMoves));
    }

    private void notifyClock(){
        if(clockConsumer == null){
            return;
        }
        settleClock();
        clockConsumer.accept(formatClock(whiteClockMillis), formatClock(blackClockMillis));
    }

    private String formatClock(long millis){
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long rem = seconds % 60;
        return String.format("%02d:%02d", minutes, rem);
    }

    private String formatCapturedList(ArrayList<String> captured){
        if(captured.isEmpty()){
            return "-";
        }
        return captured.stream()
            .sorted(this::compareSymbols)
            .collect(java.util.stream.Collectors.joining(" "));
    }

    private int compareSymbols(String a, String b){
        return Integer.compare(symbolValue(b), symbolValue(a));
    }

    private int symbolValue(String symbol){
        switch (symbol){
            case "♕":
            case "♛":
                return 9;
            case "♖":
            case "♜":
                return 5;
            case "♗":
            case "♝":
            case "♘":
            case "♞":
                return 3;
            case "♙":
            case "♟":
                return 1;
            default:
                return 0;
        }
    }

    private String pieceIcon(Piece piece){
        if(piece == null){
            return "";
        }
        switch (piece.name){
            case "Queen":
                return piece.isWhite ? "♕" : "♛";
            case "Rook":
                return piece.isWhite ? "♖" : "♜";
            case "Bishop":
                return piece.isWhite ? "♗" : "♝";
            case "Knight":
                return piece.isWhite ? "♘" : "♞";
            case "Pawn":
                return piece.isWhite ? "♙" : "♟";
            case "King":
                return piece.isWhite ? "♔" : "♚";
            default:
                return "";
        }
    }

    boolean insufficientMaterial(boolean isWhite){
        ArrayList<Piece> pieces = pieceList.stream()
            .filter(p -> p.isWhite == isWhite && !"King".equals(p.name))
            .collect(Collectors.toCollection(ArrayList::new));

        for(Piece piece : pieces){
            if(piece.name.equals("Queen") || piece.name.equals("Rook") || piece.name.equals("Pawn")){
                return false;
            }
        }

        if(pieces.isEmpty()){
            return true;
        }

        if(pieces.size() == 1){
            String name = pieces.get(0).name;
            return name.equals("Bishop") || name.equals("Knight");
        }

        if(pieces.size() <= 2){
            boolean allKnights = pieces.stream().allMatch(p -> p.name.equals("Knight"));
            if(allKnights){
                return true;
            }
        }

        boolean allBishops = pieces.stream().allMatch(p -> p.name.equals("Bishop"));
        if(allBishops){
            long distinctColors = pieces.stream()
                .map(p -> (p.col + p.row) % 2)
                .distinct()
                .count();
            return distinctColors == 1;
        }

        return false;
    }

    public Move getLastMove(){
        return lastMove;
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;

        //Painting board
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                g2d.setColor((c + r) % 2 == 0 ? new Color(240, 217, 181) : new Color(181, 139, 99));
                g2d.fillRect(drawX(c), drawY(r), tileSize, tileSize);
            }
        }

        if(lastMove != null){
            Color moveHighlightColor = analysisMode && analysisPointer >= 0
                ? qualityHighlightColor(currentQualityTag)
                : new Color(246, 246, 105, 160);
            g2d.setColor(moveHighlightColor);
            g2d.fillRect(drawX(lastMove.oldCol), drawY(lastMove.oldRow), tileSize, tileSize);
            g2d.fillRect(drawX(lastMove.newCol), drawY(lastMove.newRow), tileSize, tileSize);
        }

        if(selectedPiece != null){
            g2d.setColor(new Color(255, 255, 140, 160));
            g2d.fillRect(drawX(selectedPiece.col), drawY(selectedPiece.row), tileSize, tileSize);
        }

        // Paint legal moves (accurate Chess.com olive tone)
        if (selectedPiece != null) {
            Object oldAA = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Stroke oldStroke = g2d.getStroke();

            final Color moveDot  = new Color(127, 166, 80, 140);  // olive green
            final Color captureRing = new Color(127, 166, 80, 200);

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    Move preview = new Move(this, selectedPiece, c, r);
                    if (isValidMove(preview)) {

                        int x = drawX(c);
                        int y = drawY(r);

                        if (preview.capture == null) {
                            int diameter = tileSize / 3;
                            int circleX = x + (tileSize - diameter) / 2;
                            int circleY = y + (tileSize - diameter) / 2;

                            g2d.setColor(moveDot);
                            g2d.fillOval(circleX, circleY, diameter, diameter);

                        } else {
                            int padding = tileSize / 8;
                            int size = tileSize - padding * 2;

                            g2d.setColor(captureRing);
                            g2d.setStroke(new BasicStroke(Math.max(2f, tileSize / 12f)));
                            g2d.drawOval(x + padding, y + padding, size, size);
                        }
                    }
                }
            }

            g2d.setStroke(oldStroke);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }

        //Painting each piece
        for(Piece piece : pieceList){
            int oldX = piece.xPos;
            int oldY = piece.yPos;
            if(isDraggingPiece && piece == selectedPiece){
                piece.xPos = dragScreenX;
                piece.yPos = dragScreenY;
            } else {
                piece.xPos = drawX(piece.col);
                piece.yPos = drawY(piece.row);
            }
            piece.paint(g2d);
            piece.xPos = oldX;
            piece.yPos = oldY;
        }

        if(!checkMarkers.isEmpty()){
            for(MoveHighlight threat : checkMarkers){
                drawArrow(g2d, threat, new Color(214, 73, 73, 220));
            }
        }
        if(analysisMode && analysisPointer >= 0 && lastMove != null){
            MoveHighlight actualArrow = new MoveHighlight(lastMove.oldCol, lastMove.oldRow, lastMove.newCol, lastMove.newRow);
            drawArrow(g2d, actualArrow, qualityColor(currentQualityTag));
        }
        if(bestMoveArrow != null){
            drawArrow(g2d, bestMoveArrow, new Color(65, 190, 120, 220));
        }

        drawCoordinates(g2d);
        drawQualityBadge(g2d);
    }

    private Color qualityColor(String tag){
        if(tag == null){
            return new Color(80, 150, 255, 200);
        }
        switch (tag){
            case "Brilliant":
                return new Color(38, 194, 163, 220);
            case "Great":
                return new Color(116, 155, 191, 210);
            case "Best":
                return new Color(129, 182, 76, 210);
            case "Excellent":
                return new Color(129, 182, 76, 210);
            case "Good":
                return new Color(149, 183, 118, 210);
            case "Inaccuracy":
                return new Color(247, 192, 69, 210);
            case "Mistake":
                return new Color(255, 167, 89, 210);
            case "Severe Mistake":
                return new Color(217, 95, 14, 210);
            case "Blunder":
                return new Color(212, 63, 58, 210);
            case "Book":
                return new Color(168, 136, 101, 210);
            case "Miss":
                return new Color(255, 119, 105, 210);
            case "Mate":
                return new Color(186, 85, 211, 220);
            default:
                return new Color(80, 150, 255, 200);
        }
    }

    private Color qualityHighlightColor(String tag){
        Color base = qualityColor(tag);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 140);
    }

    private void drawArrow(Graphics2D g2d, MoveHighlight arrow, Color color){
        if(arrow == null){
            return;
        }
        Color paintColor = color != null ? color : new Color(255, 255, 255, 200);
        int fromX = drawX(arrow.fromCol) + tileSize / 2;
        int fromY = drawY(arrow.fromRow) + tileSize / 2;
        int toX = drawX(arrow.toCol) + tileSize / 2;
        int toY = drawY(arrow.toRow) + tileSize / 2;

        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int headLength = Math.max(tileSize / 2, 18);
        int headWidth = Math.max(tileSize / 3, 14);
        int shaftWidth = Math.max(tileSize / 7, 7);

        double dx = toX - fromX;
        double dy = toY - fromY;
        double length = Math.max(1, Math.hypot(dx, dy));
        double ux = dx / length;
        double uy = dy / length;
        double px = -uy;
        double py = ux;

        int shaftEndX = (int) (toX - ux * headLength);
        int shaftEndY = (int) (toY - uy * headLength);

        java.awt.geom.GeneralPath shaft = new java.awt.geom.GeneralPath();
        shaft.moveTo(fromX + px * shaftWidth, fromY + py * shaftWidth);
        shaft.lineTo(shaftEndX + px * shaftWidth, shaftEndY + py * shaftWidth);
        shaft.lineTo(shaftEndX - px * shaftWidth, shaftEndY - py * shaftWidth);
        shaft.lineTo(fromX - px * shaftWidth, fromY - py * shaftWidth);
        shaft.closePath();

        java.awt.Polygon head = new java.awt.Polygon();
        head.addPoint(toX, toY);
        head.addPoint((int) (shaftEndX + px * headWidth), (int) (shaftEndY + py * headWidth));
        head.addPoint((int) (shaftEndX - px * headWidth), (int) (shaftEndY - py * headWidth));

        java.awt.geom.AffineTransform shadowTx = java.awt.geom.AffineTransform.getTranslateInstance(2, 3);
        Shape shaftShadow = shadowTx.createTransformedShape(shaft);
        Shape headShadow = shadowTx.createTransformedShape(head);
        g.setColor(new Color(0, 0, 0, 50));
        g.fill(shaftShadow);
        g.fill(headShadow);

        Color start = new Color(paintColor.getRed(), paintColor.getGreen(), paintColor.getBlue(), 90);
        Color end = new Color(paintColor.getRed(), paintColor.getGreen(), paintColor.getBlue(), 210);
        g.setPaint(new GradientPaint(fromX, fromY, start, toX, toY, end));
        g.fill(shaft);
        g.fill(head);

        g.setStroke(new BasicStroke(Math.max(1.5f, tileSize / 24f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 80));
        g.draw(shaft);
        g.draw(head);
        g.dispose();
    }

    private void drawQualityBadge(Graphics2D g2d){
        if(!analysisMode || analysisPointer < 0 || currentQualityTag == null){
            return;
        }
        String label = currentQualityTag.toUpperCase();
        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Font font = getFont().deriveFont(Font.BOLD, 16f);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(label);
        int textHeight = fm.getAscent();
        int padding = 10;
        int badgeWidth = textWidth + padding * 2;
        int badgeHeight = textHeight + padding;
        int x = coordinateMargin + (cols * tileSize - badgeWidth) / 2;
        int y = Math.max(6, coordinateMargin / 2);
        Color base = qualityColor(currentQualityTag);
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 200);
        g.setColor(fill);
        g.fillRoundRect(x, y, badgeWidth, badgeHeight, 16, 16);
        g.setColor(new Color(0, 0, 0, 60));
        g.drawRoundRect(x, y, badgeWidth, badgeHeight, 16, 16);
        if(fill.getRed() * 0.299 + fill.getGreen() * 0.587 + fill.getBlue() * 0.114 < 140){
            g.setColor(Color.WHITE);
        } else {
            g.setColor(Color.BLACK);
        }
        g.drawString(label, x + padding, y + padding + textHeight - fm.getDescent());
        g.dispose();
    }

    private void updateCheckMarkers(){
        checkMarkers.clear();
        Piece king = findKing(isWhiteToMove);
        if(king == null){
            return;
        }
        for(Piece piece : pieceList){
            if(piece.isWhite == king.isWhite){
                continue;
            }
            if(attacksSquare(piece, king.col, king.row)){
                checkMarkers.add(new MoveHighlight(piece.col, piece.row, king.col, king.row));
            }
        }
        repaint();
    }

    private boolean attacksSquare(Piece attacker, int targetCol, int targetRow){
        int colDiff = targetCol - attacker.col;
        int rowDiff = targetRow - attacker.row;
        switch (attacker.name){
            case "Rook":
                if(attacker.col == targetCol || attacker.row == targetRow){
                    return isPathClear(attacker.col, attacker.row, targetCol, targetRow);
                }
                return false;
            case "Bishop":
                if(Math.abs(colDiff) == Math.abs(rowDiff)){
                    return isPathClear(attacker.col, attacker.row, targetCol, targetRow);
                }
                return false;
            case "Queen":
                if(attacker.col == targetCol || attacker.row == targetRow ||
                    Math.abs(colDiff) == Math.abs(rowDiff)){
                    return isPathClear(attacker.col, attacker.row, targetCol, targetRow);
                }
                return false;
            case "Knight":
                return (Math.abs(colDiff) == 1 && Math.abs(rowDiff) == 2) ||
                    (Math.abs(colDiff) == 2 && Math.abs(rowDiff) == 1);
            case "Pawn":
                int dir = attacker.isWhite ? -1 : 1;
                return rowDiff == dir && Math.abs(colDiff) == 1;
            case "King":
                return Math.max(Math.abs(colDiff), Math.abs(rowDiff)) == 1;
            default:
                return false;
        }
    }

    private boolean isPathClear(int fromCol, int fromRow, int toCol, int toRow){
        int stepCol = Integer.compare(toCol, fromCol);
        int stepRow = Integer.compare(toRow, fromRow);
        int c = fromCol + stepCol;
        int r = fromRow + stepRow;
        while(c != toCol || r != toRow){
            if(getPiece(c, r) != null){
                return false;
            }
            c += stepCol;
            r += stepRow;
        }
        return true;
    }

    private void drawCoordinates(Graphics2D g2d){
        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = getFont().deriveFont(Font.BOLD, 12f);
        g.setFont(font);
        Color text = new Color(230, 230, 230, 200);
        Color shadow = new Color(0, 0, 0, 120);

        for(int c = 0; c < cols; c++){
            int boardCol = screenToBoardCol(c);
            char file = (char) ('a' + boardCol);
            int x = coordinateMargin + c * tileSize + tileSize - 12;
            int topY = coordinateMargin - 6;
            int bottomY = coordinateMargin + boardHeightPx() + 16;
            g.setColor(shadow);
            g.drawString(String.valueOf(file), x + 1, bottomY + 1);
            g.setColor(text);
            g.drawString(String.valueOf(file), x, bottomY);
            g.setColor(shadow);
            g.drawString(String.valueOf(file), x + 1, topY + 1);
            g.setColor(text);
            g.drawString(String.valueOf(file), x, topY);
        }

        for(int r = 0; r < rows; r++){
            int boardRow = screenToBoardRow(r);
            String rank = String.valueOf(rows - boardRow);
            int leftX = coordinateMargin - 18;
            int rightX = coordinateMargin + boardWidthPx() + 6;
            int y = coordinateMargin + r * tileSize + 14;
            g.setColor(shadow);
            g.drawString(rank, leftX + 1, y + 1);
            g.setColor(text);
            g.drawString(rank, leftX, y);
            g.setColor(shadow);
            g.drawString(rank, rightX + 1, y + 1);
            g.setColor(text);
            g.drawString(rank, rightX, y);
        }
        g.dispose();
    }


    private Path resolveEnginePath(){
        if(engineBinaryPath != null){
            return engineBinaryPath;
        }
        String configuredPath = System.getenv("STOCKFISH_PATH");
        if(configuredPath == null){
            configuredPath = System.getProperty("stockfish.path");
        }
        if(configuredPath == null){
            return null;
        }

        Path enginePath = Paths.get(configuredPath);
        if(!Files.exists(enginePath)){
            System.err.println("Stockfish engine not found at " + enginePath.toAbsolutePath());
            return null;
        }
        engineBinaryPath = enginePath;
        return enginePath;
    }

    private void initializeEngineIntegration(){
        Path enginePath = resolveEnginePath();
        if(enginePath == null){
            return;
        }

        try{
            stockfishClient = new StockfishClient(enginePath);
            configureEngine();
            engineExecutor = Executors.newSingleThreadExecutor();
            engineEnabled = true;
            System.out.println("Stockfish ready on path " + enginePath.toAbsolutePath());
        } catch (IOException e){
            System.err.println("Unable to start Stockfish: " + e.getMessage());
        }
    }

    private void requestEngineMoveIfNeeded(){
        if(engineOnHold || !engineEnabled || stockfishClient == null || engineExecutor == null || !isGameActive){
            return;
        }
        if(isGameOver){
            shutdownEngine();
            return;
        }
        if(isWhiteToMove != engineIsWhite){
            return;
        }
        if(engineThinking){
            return;
        }
        engineThinking = true;
        notifyClock();
        final long requestId = ++engineRequestId;
        final int plyAtRequest = moveHistory.size();
        final String moves = String.join(" ", moveHistory);
        final Duration thinkTime = dynamicEngineThinkTime();
        final StockfishClient client = stockfishClient;
        engineExecutor.submit(() -> {
            String uciMove = null;
            try{
                uciMove = client.requestBestMove(moves, thinkTime);
            } catch (IOException | RuntimeException e){
                System.err.println("Engine failure: " + e.getMessage());
            }
            final String bestMove = uciMove;
            callbackExecutor.execute(() -> {
                synchronized (this) {
                    if(requestId != engineRequestId){
                        return; // superseded by a new game, side change or shutdown
                    }
                    engineThinking = false;
                    try{
                        boolean stillEngineTurn = engineEnabled && isGameActive && !analysisMode
                            && isWhiteToMove == engineIsWhite && moveHistory.size() == plyAtRequest;
                        if(bestMove != null && stillEngineTurn){
                            applyUciMove(bestMove);
                        }
                    } finally {
                        notifyClock();
                    }
                }
            });
        });
    }

    /**
     * Holds or releases engine play. Holding cancels any pending engine move (its result is discarded);
     * releasing lets the engine move immediately if it is its turn.
     */
    public synchronized void setEngineOnHold(boolean hold){
        if(hold == engineOnHold){
            return;
        }
        engineOnHold = hold;
        if(hold){
            cancelPendingEngineMove();
            notifyClock();
        } else {
            requestEngineMoveIfNeeded();
        }
    }

    private void cancelPendingEngineMove(){
        engineRequestId++;
        engineThinking = false;
    }

    private void updateEngineThinkTimeForElo(){
        long millis;
        if(engineElo <= 1000){
            millis = 700;
        } else if(engineElo <= 1200){
            millis = 900;
        } else if(engineElo <= 1400){
            millis = 1100;
        } else if(engineElo <= 1600){
            millis = 1350;
        } else if(engineElo <= 1800){
            millis = 1600;
        } else if(engineElo <= 2000){
            millis = 1900;
        } else if(engineElo <= 2200){
            millis = 2300;
        } else if(engineElo <= 2400){
            millis = 2700;
        } else {
            millis = 3200;
        }
        engineThinkTime = Duration.ofMillis(millis);
    }

    private void updateEngineSkillLevelForElo(){
        if(engineElo <= 900){
            engineSkillLevel = 1;
        } else if(engineElo <= 1100){
            engineSkillLevel = 3;
        } else if(engineElo <= 1300){
            engineSkillLevel = 5;
        } else if(engineElo <= 1500){
            engineSkillLevel = 7;
        } else if(engineElo <= 1700){
            engineSkillLevel = 9;
        } else if(engineElo <= 1900){
            engineSkillLevel = 11;
        } else if(engineElo <= 2100){
            engineSkillLevel = 13;
        } else if(engineElo <= 2300){
            engineSkillLevel = 15;
        } else if(engineElo <= 2500){
            engineSkillLevel = 17;
        } else {
            engineSkillLevel = 20;
        }
    }

    private Duration dynamicEngineThinkTime(){
        long baseMillis = engineThinkTime.toMillis();
        double timeFactor = timeControlFactor();
        long jitter = ThreadLocalRandom.current().nextLong(-220, 381);
        long openingAdjustment = moveHistory.size() < 10 ? -120 : 0;
        long endgameAdjustment = pieceList.size() <= 12 ? 250 : 0;
        long scaledBaseMillis = Math.round(baseMillis * timeFactor);
        long millis = Math.max(500, Math.min(5200, scaledBaseMillis + jitter + openingAdjustment + endgameAdjustment));
        return Duration.ofMillis(millis);
    }

    private double timeControlFactor(){
        if(timeControlMinutes <= 1){
            return 0.65;
        }
        if(timeControlMinutes <= 3){
            return 0.85;
        }
        if(timeControlMinutes <= 5){
            return 1.0;
        }
        if(timeControlMinutes <= 10){
            return 1.35;
        }
        if(timeControlMinutes <= 15){
            return 1.55;
        }
        if(timeControlMinutes <= 30){
            return 1.85;
        }
        return 2.1;
    }

    private void applyUciMove(String uciMove){
        Move engineMove = createMoveFromUci(uciMove);
        if(engineMove != null && isValidMove(engineMove)){
            makeMove(engineMove);
        }
    }

    private Move createMoveFromUci(String uci){
        if(uci == null || uci.length() < 4){
            return null;
        }
        int fromCol = uci.charAt(0) - 'a';
        int fromRow = rows - Character.getNumericValue(uci.charAt(1));
        int toCol = uci.charAt(2) - 'a';
        int toRow = rows - Character.getNumericValue(uci.charAt(3));

        if(!isInsideBoard(fromCol, fromRow) || !isInsideBoard(toCol, toRow)){
            return null;
        }
        Piece piece = getPiece(fromCol, fromRow);
        if(piece == null){
            return null;
        }
        Move move = new Move(this, piece, toCol, toRow);
        if(uci.length() >= 5){
            char promotion = Character.toLowerCase(uci.charAt(4));
            if(!isValidPromotion(promotion)){
                return null;
            }
            move.promotion = promotion;
        }
        return move;
    }

    private void recordMove(Move move, String notation){
        moveHistory.add(toUci(move.oldCol, move.oldRow, move.newCol, move.newRow, move.piece, move.promotion));
        if(move.piece.isWhite){
            int moveNumber = displayMoves.size() + 1;
            displayMoves.add(moveNumber + ". " + notation);
        } else {
            if(displayMoves.isEmpty()){
                displayMoves.add("1... " + notation);
            } else {
                int idx = displayMoves.size() - 1;
                displayMoves.set(idx, displayMoves.get(idx) + " " + notation);
            }
        }
        sanHistory.add(notation);
        notifyMoveLog();
    }

    private String toUci(int fromCol, int fromRow, int toCol, int toRow, Piece piece, char promotion){
        StringBuilder sb = new StringBuilder();
        sb.append(squareName(fromCol, fromRow));
        sb.append(squareName(toCol, toRow));
        if("Pawn".equals(piece.name)){
            int promotionRank = piece.isWhite ? 0 : 7;
            if(toRow == promotionRank){
                sb.append(promotion);
            }
        }
        return sb.toString();
    }

    private String squareName(int col, int row){
        char file = (char) ('a' + col);
        int rank = rows - row;
        return "" + file + rank;
    }

    private void prepareAnalysisReview(GameAnalyzer.Summary summary, java.util.List<String> movesUci, java.util.List<String> sanMoves){
        if(liveStateBackup == null){
            liveStateBackup = captureLiveGameState();
        }
        analysisMode = true;
        isGameActive = false;
        isGameOver = false;
        engineEnabled = false;
        cancelPendingEngineMove();
        bestMoveArrow = null;
        currentQualityTag = null;
        reviewUciMoves = new ArrayList<>(movesUci);
        reviewSanMoves = new ArrayList<>(sanMoves);
        reviewEntries = summary.entries;
        lastAnalysisSummary = summary;
        analysisSnapshots.clear();
        loadPositionFromFEN(fenStartingPosition);
        analysisSnapshots.add(captureSnapshot(null));
        for(String uci : reviewUciMoves){
            Move move = createMoveFromUci(uci);
            if(move == null || !isValidMove(move)){
                break;
            }
            makeMove(move, false);
            analysisSnapshots.add(captureSnapshot(move));
        }
        analysisPointer = -1;
        rebuildDisplayMovesForPointer();
        applySnapshotForPointer();
        notifyAnalysisFrame();
    }

    private AnalysisSnapshot captureSnapshot(Move move){
        java.util.List<PieceState> snapshotPieces = pieceList.stream()
            .map(p -> new PieceState(p.name, p.col, p.row, p.isWhite, p.isFirstMove))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        java.util.List<String> whiteCaps = new ArrayList<>(capturedByWhite);
        java.util.List<String> blackCaps = new ArrayList<>(capturedByBlack);
        MoveHighlight highlight = null;
        if(move != null){
            highlight = new MoveHighlight(move.oldCol, move.oldRow, move.newCol, move.newRow);
        }
        return new AnalysisSnapshot(snapshotPieces, whiteCaps, blackCaps, isWhiteToMove, highlight);
    }

    private void applySnapshotForPointer(){
        int snapshotIndex = analysisPointer + 1;
        if(snapshotIndex < 0 || snapshotIndex >= analysisSnapshots.size()){
            return;
        }
        AnalysisSnapshot snap = analysisSnapshots.get(snapshotIndex);
        pieceList.clear();
        for(PieceState state : snap.pieces){
            Piece recreated = createPieceByName(state.name, state.col, state.row, state.isWhite);
            if(recreated != null){
                recreated.isFirstMove = state.wasFirstMove;
                pieceList.add(recreated);
            }
        }
        capturedByWhite.clear();
        capturedByWhite.addAll(snap.whiteCaptures);
        capturedByBlack.clear();
        capturedByBlack.addAll(snap.blackCaptures);
        isWhiteToMove = snap.whiteToMove;
        if(snap.highlight != null){
            Piece destination = getPiece(snap.highlight.toCol, snap.highlight.toRow);
            if(destination != null){
                Move highlightMove = new Move(this, destination, snap.highlight.toCol, snap.highlight.toRow);
                highlightMove.oldCol = snap.highlight.fromCol;
                highlightMove.oldRow = snap.highlight.fromRow;
                lastMove = highlightMove;
            } else {
                lastMove = null;
            }
        } else {
            lastMove = null;
        }
        selectedPiece = null;
        stopDragging();
        notifyCaptures();
        notifyEvaluation();
        updateCheckMarkers();
    }

    private void rebuildDisplayMovesForPointer(){
        displayMoves.clear();
        sanHistory.clear();
        for(int i = 0; i <= analysisPointer && i < reviewSanMoves.size(); i++){
            String notation = reviewSanMoves.get(i);
            if(i % 2 == 0){
                int moveNumber = (i / 2) + 1;
                displayMoves.add(moveNumber + ". " + notation);
            } else {
                int idx = displayMoves.size() - 1;
                if(idx >= 0){
                    displayMoves.set(idx, displayMoves.get(idx) + " " + notation);
                }
            }
            sanHistory.add(notation);
        }
        notifyMoveLog();
    }

    private void notifyAnalysisFrame(){
        if(analysisFrameConsumer == null){
            return;
        }
        GameAnalyzer.Entry entry = (analysisPointer >= 0 && analysisPointer < reviewEntries.size())
            ? reviewEntries.get(analysisPointer)
            : null;
        String san = (analysisPointer >= 0 && analysisPointer < reviewSanMoves.size())
            ? reviewSanMoves.get(analysisPointer)
            : null;

        currentQualityTag = entry != null ? entry.qualityTag : null;
        if(entry != null && entry.bestMove != null && shouldShowBestArrow(entry)){
            bestMoveArrow = highlightFromUci(entry.bestMove);
        } else {
            bestMoveArrow = null;
        }

        boolean finalPly = analysisPointer >= 0 && analysisPointer == reviewSanMoves.size() - 1;
        analysisFrameConsumer.accept(new AnalysisFrame(
            analysisPointer,
            reviewSanMoves.size(),
            san,
            entry,
            lastAnalysisSummary,
            san != null && san.endsWith("#"),
            finalPly ? gameEndReason : null,
            finalPly ? gameEndMessage : null
        ));
        repaint();
    }

    private Piece createPieceByName(String name, int col, int row, boolean isWhite){
        switch (name){
            case "Rook":
                return new Rook(this, col, row, isWhite);
            case "Knight":
                return new Knight(this, col, row, isWhite);
            case "Bishop":
                return new Bishop(this, col, row, isWhite);
            case "Queen":
                return new Queen(this, col, row, isWhite);
            case "King":
                return new King(this, col, row, isWhite);
            case "Pawn":
                return new Pawn(this, col, row, isWhite);
            default:
                return null;
        }
    }

    private MoveHighlight highlightFromUci(String uci){
        if(uci == null || uci.length() < 4){
            return null;
        }
        int fromCol = uci.charAt(0) - 'a';
        int fromRow = rows - Character.getNumericValue(uci.charAt(1));
        int toCol = uci.charAt(2) - 'a';
        int toRow = rows - Character.getNumericValue(uci.charAt(3));
        if(!isInsideBoard(fromCol, fromRow) || !isInsideBoard(toCol, toRow)){
            return null;
        }
        return new MoveHighlight(fromCol, fromRow, toCol, toRow);
    }

    private boolean shouldShowBestArrow(GameAnalyzer.Entry entry){
        if(entry == null){
            return false;
        }
        if(entry.bestMove == null || entry.playedMove == null){
            return false;
        }
        return !entry.bestMove.equals(entry.playedMove);
    }

    private void clearAnalysisReview(){
        analysisRequestId++;
        analysisMode = false;
        analysisSnapshots.clear();
        analysisPointer = -1;
        reviewEntries = java.util.List.of();
        reviewSanMoves = java.util.List.of();
        reviewUciMoves = java.util.List.of();
        lastAnalysisSummary = null;
        bestMoveArrow = null;
        currentQualityTag = null;
        liveStateBackup = null;
        if(analysisFrameConsumer != null){
            analysisFrameConsumer.accept(new AnalysisFrame(-1, 0, null, null, null));
        }
    }


    private boolean isPromotionMove(Move move){
        if(!"Pawn".equals(move.piece.name)){
            return false;
        }
        return move.newRow == 0 || move.newRow == rows - 1;
    }

    private boolean isEnPassantCapture(Move move){
        if(!"Pawn".equals(move.piece.name)){
            return false;
        }
        if(enPassantTile == -1){
            return false;
        }
        return getTileNum(move.newCol, move.newRow) == enPassantTile && getPiece(move.newCol, move.newRow) == null;
    }

    private boolean isKingSideCastle(Move move){
        return "King".equals(move.piece.name) && (move.newCol - move.oldCol) == 2;
    }

    private boolean isQueenSideCastle(Move move){
        return "King".equals(move.piece.name) && (move.oldCol - move.newCol) == 2;
    }

    private String buildSanCore(Move move, boolean isCapture, boolean isPromotion, boolean kingSideCastle, boolean queenSideCastle){
        if(kingSideCastle){
            return "O-O";
        }
        if(queenSideCastle){
            return "O-O-O";
        }
        StringBuilder sb = new StringBuilder();
        boolean isPawn = "Pawn".equals(move.piece.name);
        if(!isPawn){
            sb.append(pieceLetter(move.piece.name));
            sb.append(disambiguate(move));
        } else if(isCapture){
            sb.append((char)('a' + move.oldCol));
        }
        if(isCapture){
            sb.append('x');
        }
        sb.append(squareName(move.newCol, move.newRow));
        if(isPromotion){
            sb.append('=').append(Character.toUpperCase(move.promotion));
        }
        return sb.toString();
    }

    private String pieceLetter(String name){
        switch (name){
            case "Knight":
                return "N";
            case "Bishop":
                return "B";
            case "Rook":
                return "R";
            case "Queen":
                return "Q";
            case "King":
                return "K";
            default:
                return "";
        }
    }

    private String disambiguate(Move move){
        if("Pawn".equals(move.piece.name)){
            return "";
        }
        boolean conflict = false;
        boolean sameFile = false;
        boolean sameRank = false;
        for(Piece other : pieceList){
            if(other == move.piece){
                continue;
            }
            if(!other.name.equals(move.piece.name)){
                continue;
            }
            if(other.isWhite != move.piece.isWhite){
                continue;
            }
            Move alternative = new Move(this, other, move.newCol, move.newRow);
            if(isValidMove(alternative)){
                conflict = true;
                if(other.col == move.oldCol){
                    sameFile = true;
                }
                if(other.row == move.oldRow){
                    sameRank = true;
                }
            }
        }
        if(!conflict){
            return "";
        }
        char fileChar = (char) ('a' + move.oldCol);
        String rank = Integer.toString(rows - move.oldRow);
        if(!sameFile && !sameRank){
            return String.valueOf(fileChar);
        }
        if(sameFile && !sameRank){
            return rank;
        }
        if(!sameFile && sameRank){
            return String.valueOf(fileChar);
        }
        return "" + fileChar + rank;
    }

    public String getPgn(String resultTag){
        String resolvedResult = resultTag == null ? "*" : resultTag;
        String date = LocalDate.now().toString().replace('-', '.');
        StringBuilder sb = new StringBuilder();
        sb.append("[Event \"Casual Game\"]\n");
        sb.append("[Site \"Local\"]\n");
        sb.append("[Date \"").append(date).append("\"]\n");
        sb.append("[White \"").append(getPlayerName(true)).append("\"]\n");
        sb.append("[Black \"").append(getPlayerName(false)).append("\"]\n");
        sb.append("[Result \"").append(resolvedResult).append("\"]\n\n");
        if(!displayMoves.isEmpty()){
            sb.append(String.join(" ", displayMoves)).append(' ');
        }
        sb.append(resolvedResult).append('\n');
        return sb.toString();
    }

    private String getPlayerName(boolean white){
        if(engineEnabled){
            if(white == engineIsWhite){
                return "Stockfish";
            }
        }
        return "Player";
    }

    public int screenToBoardCol(int screenCol){
        return flipBoard ? cols - 1 - screenCol : screenCol;
    }

    public int screenToBoardRow(int screenRow){
        return flipBoard ? rows - 1 - screenRow : screenRow;
    }

    public int boardToScreenCol(int col){
        return applyFlip(col);
    }

    public int boardToScreenRow(int row){
        return applyFlipRow(row);
    }

    public int boardToPixelX(int col){
        return coordinateMargin + boardToScreenCol(col) * tileSize;
    }

    public int boardToPixelY(int row){
        return coordinateMargin + boardToScreenRow(row) * tileSize;
    }

    public int pixelToBoardCol(int pixelX){
        int adjusted = pixelX - coordinateMargin;
        if(adjusted < 0 || adjusted >= cols * tileSize){
            return -1;
        }
        return screenToBoardCol(adjusted / tileSize);
    }

    public int pixelToBoardRow(int pixelY){
        int adjusted = pixelY - coordinateMargin;
        if(adjusted < 0 || adjusted >= rows * tileSize){
            return -1;
        }
        return screenToBoardRow(adjusted / tileSize);
    }

    public String getLastResultTag(){
        return lastResultTag;
    }

    public synchronized GameEndReason getGameEndReason(){
        return gameEndReason;
    }

    public synchronized String getGameEndMessage(){
        return gameEndMessage;
    }

    public synchronized java.util.List<PieceView> getPieceViews(){
        return pieceList.stream()
            .map(piece -> new PieceView(piece.name, piece.isWhite ? "white" : "black", pieceIcon(piece), piece.col, piece.row))
            .collect(java.util.stream.Collectors.toList());
    }

    public synchronized java.util.List<SquareView> getLegalDestinations(int col, int row){
        if(!isInsideBoard(col, row)){
            return java.util.List.of();
        }

        Piece piece = getPiece(col, row);
        if(piece == null || piece.isWhite != isWhiteToMove || isGameOver || !isGameActive || isEngineTurn()){
            return java.util.List.of();
        }

        java.util.List<SquareView> destinations = new java.util.ArrayList<>();
        for(int targetRow = 0; targetRow < rows; targetRow++){
            for(int targetCol = 0; targetCol < cols; targetCol++){
                Move preview = new Move(this, piece, targetCol, targetRow);
                if(isValidMove(preview)){
                    boolean capture = preview.capture != null || isEnPassantCapture(preview);
                    destinations.add(new SquareView(targetCol, targetRow, capture));
                }
            }
        }
        return destinations;
    }

    public synchronized boolean playMove(int fromCol, int fromRow, int toCol, int toRow){
        return playMove(fromCol, fromRow, toCol, toRow, 'q');
    }

    /** Plays a human move; rejected while the game is inactive, under review, or when it is the engine's turn. */
    public synchronized boolean playMove(int fromCol, int fromRow, int toCol, int toRow, char promotion){
        if(!isInsideBoard(fromCol, fromRow) || !isInsideBoard(toCol, toRow)){
            return false;
        }
        if(!isValidPromotion(promotion)){
            return false;
        }
        settleClock();
        if(!isGameActive || analysisMode || isEngineTurn()){
            return false;
        }

        Piece piece = getPiece(fromCol, fromRow);
        if(piece == null){
            return false;
        }
        Move move = new Move(this, piece, toCol, toRow);
        move.promotion = promotion;
        if(!isValidMove(move)){
            return false;
        }
        makeMove(move);
        return true;
    }

    public synchronized boolean playUciMove(String uciMove){
        if(!isGameActive){
            return false;
        }
        Move move = createMoveFromUci(uciMove);
        if(move == null || !isValidMove(move)){
            return false;
        }
        makeMove(move);
        return true;
    }

    public synchronized java.util.List<String> getDisplayMovesSnapshot(){
        return new java.util.ArrayList<>(displayMoves);
    }

    public synchronized java.util.List<String> getMoveHistorySnapshot(){
        return new java.util.ArrayList<>(moveHistory);
    }

    public synchronized String getCapturedByWhiteText(){
        return formatCapturedList(capturedByWhite);
    }

    public synchronized String getCapturedByBlackText(){
        return formatCapturedList(capturedByBlack);
    }

    public synchronized String getWhiteClockText(){
        settleClock();
        return formatClock(whiteClockMillis);
    }

    public synchronized String getBlackClockText(){
        settleClock();
        return formatClock(blackClockMillis);
    }

    public synchronized boolean isWhiteToMove(){
        return isWhiteToMove;
    }

    public synchronized boolean isGameOver(){
        return isGameOver;
    }

    public synchronized boolean isClockStarted(){
        return clockStarted;
    }

    public synchronized boolean isEngineEnabled(){
        return engineEnabled;
    }

    public synchronized boolean isEngineWhite(){
        return engineIsWhite;
    }

    /** True while engine play is on and the side to move belongs to the engine. */
    public synchronized boolean isEngineTurn(){
        return engineEnabled && !engineOnHold && isGameActive && !analysisMode && isWhiteToMove == engineIsWhite;
    }

    public boolean isEngineThinking(){
        return engineThinking;
    }

    public synchronized int getEngineElo(){
        return engineElo;
    }

    public synchronized long getInitialClockMinutes(){
        return Math.max(1, Duration.ofMillis(initialClockMillis).toMinutes());
    }

    public synchronized MoveView getLastMoveView(){
        if(lastMove == null){
            return null;
        }
        return new MoveView(lastMove.oldCol, lastMove.oldRow, lastMove.newCol, lastMove.newRow);
    }

    public static final class PieceView {
        public final String name;
        public final String color;
        public final String symbol;
        public final int col;
        public final int row;

        public PieceView(String name, String color, String symbol, int col, int row) {
            this.name = name;
            this.color = color;
            this.symbol = symbol;
            this.col = col;
            this.row = row;
        }
    }

    public static final class SquareView {
        public final int col;
        public final int row;
        public final boolean capture;

        public SquareView(int col, int row, boolean capture) {
            this.col = col;
            this.row = row;
            this.capture = capture;
        }
    }

    public static final class MoveView {
        public final int fromCol;
        public final int fromRow;
        public final int toCol;
        public final int toRow;

        public MoveView(int fromCol, int fromRow, int toCol, int toRow) {
            this.fromCol = fromCol;
            this.fromRow = fromRow;
            this.toCol = toCol;
            this.toRow = toRow;
        }
    }

    public void analyzeGame(java.util.function.Consumer<java.util.List<String>> consumer){
        analyzeGame(consumer, null);
    }

    /** Progress of a running review: moves classified so far (results fill in as they arrive). */
    public static final class AnalysisProgress {
        public final int done;
        public final int total;
        public final java.util.List<GameAnalyzer.Entry> entries;

        public AnalysisProgress(int done, int total, java.util.List<GameAnalyzer.Entry> entries) {
            this.done = done;
            this.total = total;
            this.entries = entries;
        }
    }

    public void setAnalysisDepth(int depth){
        this.analysisDepth = Math.max(1, Math.min(30, depth));
    }

    public void setAnalysisMultiPv(int multiPv){
        this.analysisMultiPv = Math.max(1, Math.min(5, multiPv));
    }

    public void setAnalysisCache(GameAnalyzer.ResultCache cache){
        this.analysisCache = cache;
    }

    /**
     * Reviews the game in the background. {@code progress} (optional) receives every classified move as it
     * arrives; {@code consumer} receives the final report. Both run through the callback executor.
     */
    public void analyzeGame(java.util.function.Consumer<java.util.List<String>> consumer,
                            java.util.function.Consumer<AnalysisProgress> progress){
        if(analysisMode){
            restoreLiveGameState();
        }
        Path enginePath = resolveEnginePath();
        if(enginePath == null){
            consumer.accept(java.util.List.of("Engine path not configured. Set STOCKFISH_PATH to enable analysis."));
            return;
        }
        final long requestId = ++analysisRequestId;
        final String endMessage = gameEndMessage;
        final int depth = analysisDepth;
        final int multiPv = analysisMultiPv;
        final GameAnalyzer.ResultCache cache = analysisCache;
        List<String> movesCopy = new ArrayList<>(moveHistory);
        List<String> sanCopy = new ArrayList<>(sanHistory);
        analysisExecutor.submit(() -> {
            GameAnalyzer.Summary summary = null;
            List<String> report;
            try{
                GameAnalyzer analyzer = new GameAnalyzer(enginePath, depth, multiPv).withCache(cache);
                summary = analyzer.analyze(movesCopy, progress == null ? null : (done, total, entries) ->
                    callbackExecutor.execute(() -> {
                        synchronized (this) {
                            if(requestId == analysisRequestId){
                                progress.accept(new AnalysisProgress(done, total, entries));
                            }
                        }
                    }));
                report = GameReviewFormatter.buildReport(summary, sanCopy, endMessage);
            } catch (Exception e){
                report = java.util.List.of("Analysis failed: " + e.getMessage());
            }
            final GameAnalyzer.Summary result = summary;
            final List<String> lines = report;
            callbackExecutor.execute(() -> {
                synchronized (this) {
                    if(result == null){
                        consumer.accept(lines);
                    } else if(requestId != analysisRequestId || analysisMode || !moveHistory.equals(movesCopy)){
                        consumer.accept(java.util.List.of("The game changed while the analysis was running. Press Analyze again."));
                    } else {
                        prepareAnalysisReview(result, movesCopy, sanCopy);
                        consumer.accept(lines);
                    }
                }
            });
        });
    }

    public void exitAnalysisReview(){
        if(analysisMode){
            restoreLiveGameState();
        }
    }

    public boolean isAnalysisMode(){
        return analysisMode;
    }

    public void updateDragPosition(int screenX, int screenY){
        isDraggingPiece = true;
        dragScreenX = screenX;
        dragScreenY = screenY;
    }

    public void stopDragging(){
        isDraggingPiece = false;
    }

    private void resetClock(){
        cancelPendingEngineMove();
        whiteClockMillis = initialClockMillis;
        blackClockMillis = initialClockMillis;
        lastClockUpdateMillis = 0L;
        clockStarted = false;
        if(clockTimer != null){
            clockTimer.stop();
        }
        notifyClock();
    }

    private void startClock(){
        if(clockStarted || untimed){
            return;
        }
        clockStarted = true;
        lastClockUpdateMillis = System.currentTimeMillis();
        if(clockTimer != null){
            clockTimer.stop();
        }
        if(serverMode){
            // Server clocks are settled on every read/move instead of by an EDT timer.
            notifyClock();
            return;
        }
        clockTimer = new javax.swing.Timer(1000, e -> tickClock());
        clockTimer.start();
        notifyClock();
    }

    private void tickClock(){
        settleClock();
        notifyClock();
    }

    private synchronized void settleClock(){
        if(!isGameActive || isGameOver || untimed){
            lastClockUpdateMillis = 0L;
            return;
        }
        if(!clockStarted){
            lastClockUpdateMillis = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if(lastClockUpdateMillis <= 0L){
            lastClockUpdateMillis = now;
            return;
        }
        long elapsed = Math.max(0L, now - lastClockUpdateMillis);
        if(elapsed <= 0L){
            return;
        }
        lastClockUpdateMillis = now;
        if(isWhiteToMove){
            whiteClockMillis = Math.max(0, whiteClockMillis - elapsed);
            if(whiteClockMillis == 0){
                finishGame(GameEndReason.TIMEOUT, Boolean.FALSE);
                return;
            }
        } else {
            blackClockMillis = Math.max(0, blackClockMillis - elapsed);
            if(blackClockMillis == 0){
                finishGame(GameEndReason.TIMEOUT, Boolean.TRUE);
            }
        }
    }

    private void shutdownEngine(){
        cancelPendingEngineMove();
        engineEnabled = false;
        if(engineExecutor != null){
            engineExecutor.shutdownNow();
            engineExecutor = null;
        }
        if(stockfishClient != null){
            try{
                stockfishClient.close();
            } catch (IOException ignored){
            }
            stockfishClient = null;
        }
    }

    /** Releases the clock timer, engine process and worker threads. The board must not be used afterwards. */
    public synchronized void dispose(){
        cancelPendingEngineMove();
        analysisRequestId++;
        if(clockTimer != null){
            clockTimer.stop();
        }
        shutdownEngine();
        analysisExecutor.shutdownNow();
    }

    private void configureEngine(){
        if(stockfishClient == null){
            return;
        }
        try{
            stockfishClient.setSkillLevel(engineSkillLevel);
            stockfishClient.setLimitStrength(true);
            stockfishClient.setTargetElo(engineElo);
        } catch (IOException e){
            System.err.println("Unable to configure engine: " + e.getMessage());
        }
    }

    private void loadSounds(){
        moveSound = loadSoundData("sounds/move-self.mp3");
        captureSound = loadSoundData("sounds/capture.mp3");
        promoteSound = loadSoundData("sounds/promote.mp3");
        castleSound = loadSoundData("sounds/castle.mp3");
        checkSound = loadSoundData("sounds/move-check.mp3");
        notifySound = loadSoundData("sounds/notify.mp3");
    }

    private byte[] loadSoundData(String resource){
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if(is == null){
                System.err.println("Missing sound: " + resource);
                return null;
            }
            return is.readAllBytes();
        } catch (Exception e){
            System.err.println("Unable to load sound " + resource + ": " + e.getMessage());
            return null;
        }
    }

    private void playSound(byte[] data){
        if(!soundEnabled || data == null){
            return;
        }
        byte[] copy = Arrays.copyOf(data, data.length);
        new Thread(() -> {
            try (BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(copy))) {
                Player player = new Player(bis);
                player.play();
            } catch (Exception e){
                System.err.println("Unable to play sound: " + e.getMessage());
                soundEnabled = false;
            }
        }).start();
    }


    private int drawX(int col){
        return coordinateMargin + applyFlip(col) * tileSize;
    }

    private int drawY(int row){
        return coordinateMargin + applyFlipRow(row) * tileSize;
    }

    private int boardWidthPx(){
        return cols * tileSize;
    }

    private int boardHeightPx(){
        return rows * tileSize;
    }

    private int applyFlip(int col){
        return flipBoard ? cols - 1 - col : col;
    }

    private int applyFlipRow(int row){
        return flipBoard ? rows - 1 - row : row;
    }

    public void setAnalysisFrameConsumer(java.util.function.Consumer<AnalysisFrame> consumer){
        this.analysisFrameConsumer = consumer;
    }

    public void stepAnalysis(int delta){
        if(!analysisMode || analysisSnapshots.isEmpty()){
            return;
        }
        int maxPointer = reviewUciMoves.size() - 1;
        int newPointer = Math.max(-1, Math.min(maxPointer, analysisPointer + delta));
        if(newPointer == analysisPointer){
            return;
        }
        analysisPointer = newPointer;
        applySnapshotForPointer();
        rebuildDisplayMovesForPointer();
        notifyAnalysisFrame();
        repaint();
    }

    /** Jumps the review to a ply (-1 = starting position); out-of-range values are clamped. */
    public void jumpToAnalysisPly(int ply){
        if(!analysisMode || analysisSnapshots.isEmpty()){
            return;
        }
        stepAnalysis(ply - analysisPointer);
    }

    public static final class AnalysisFrame {
        public final int plyIndex;
        public final int totalPlies;
        public final String san;
        public final GameAnalyzer.Entry entry;
        public final GameAnalyzer.Summary summary;
        /** True only if this move checkmated according to the board rules (SAN ends with '#'). */
        public final boolean deliveredMate;
        /** Set on the final ply of a finished game: how it actually ended (e.g. on time), else null. */
        public final GameEndReason gameEndReason;
        public final String gameEndMessage;

        public AnalysisFrame(int plyIndex, int totalPlies, String san,
                             GameAnalyzer.Entry entry, GameAnalyzer.Summary summary) {
            this(plyIndex, totalPlies, san, entry, summary, false, null, null);
        }

        public AnalysisFrame(int plyIndex, int totalPlies, String san,
                             GameAnalyzer.Entry entry, GameAnalyzer.Summary summary,
                             boolean deliveredMate, GameEndReason gameEndReason, String gameEndMessage) {
            this.plyIndex = plyIndex;
            this.totalPlies = totalPlies;
            this.san = san;
            this.entry = entry;
            this.summary = summary;
            this.deliveredMate = deliveredMate;
            this.gameEndReason = gameEndReason;
            this.gameEndMessage = gameEndMessage;
        }

        public boolean hasMove(){
            return plyIndex >= 0 && entry != null;
        }

        public int moveNumber(){
            return plyIndex < 0 ? 0 : (plyIndex / 2) + 1;
        }

        public boolean isWhiteMove(){
            return entry != null ? entry.isWhite : (plyIndex % 2 == 0);
        }
    }

    private static final class AnalysisSnapshot {
        final java.util.List<PieceState> pieces;
        final java.util.List<String> whiteCaptures;
        final java.util.List<String> blackCaptures;
        final boolean whiteToMove;
        final MoveHighlight highlight;

        AnalysisSnapshot(java.util.List<PieceState> pieces,
                         java.util.List<String> whiteCaptures,
                         java.util.List<String> blackCaptures,
                         boolean whiteToMove,
                         MoveHighlight highlight) {
            this.pieces = pieces;
            this.whiteCaptures = whiteCaptures;
            this.blackCaptures = blackCaptures;
            this.whiteToMove = whiteToMove;
            this.highlight = highlight;
        }
    }

    private static final class PieceState {
        final String name;
        final int col;
        final int row;
        final boolean isWhite;
        final boolean wasFirstMove;

        PieceState(String name, int col, int row, boolean isWhite, boolean wasFirstMove){
            this.name = name;
            this.col = col;
            this.row = row;
            this.isWhite = isWhite;
            this.wasFirstMove = wasFirstMove;
        }
    }

    private static final class MoveHighlight {
        final int fromCol;
        final int fromRow;
        final int toCol;
        final int toRow;

        MoveHighlight(int fromCol, int fromRow, int toCol, int toRow){
            this.fromCol = fromCol;
            this.fromRow = fromRow;
            this.toCol = toCol;
            this.toRow = toRow;
        }
    }

    private static final class StoredMove {
        final int oldCol;
        final int oldRow;
        final int newCol;
        final int newRow;

        StoredMove(int oldCol, int oldRow, int newCol, int newRow){
            this.oldCol = oldCol;
            this.oldRow = oldRow;
            this.newCol = newCol;
            this.newRow = newRow;
        }
    }

    private static final class LiveGameState {
        final java.util.List<PieceState> pieces;
        final java.util.List<String> capturedByWhite;
        final java.util.List<String> capturedByBlack;
        final java.util.List<String> moveHistory;
        final java.util.List<String> displayMoves;
        final java.util.List<String> sanHistory;
        final StoredMove lastMove;
        final boolean isWhiteToMove;
        final boolean isGameActive;
        final boolean isGameOver;
        final boolean engineEnabled;
        final boolean engineIsWhite;
        final boolean flipBoard;
        final int enPassantTile;
        final long whiteClockMillis;
        final long blackClockMillis;
        final boolean clockStarted;
        final String lastResultTag;
        final boolean lastMoveDeliveredCheck;
        final boolean lastMoveDeliveredMate;

        LiveGameState(java.util.List<PieceState> pieces,
                      java.util.List<String> capturedByWhite,
                      java.util.List<String> capturedByBlack,
                      java.util.List<String> moveHistory,
                      java.util.List<String> displayMoves,
                      java.util.List<String> sanHistory,
                      StoredMove lastMove,
                      boolean isWhiteToMove,
                      boolean isGameActive,
                      boolean isGameOver,
                      boolean engineEnabled,
                      boolean engineIsWhite,
                      boolean flipBoard,
                      int enPassantTile,
                      long whiteClockMillis,
                      long blackClockMillis,
                      boolean clockStarted,
                      String lastResultTag,
                      boolean lastMoveDeliveredCheck,
                      boolean lastMoveDeliveredMate){
            this.pieces = pieces;
            this.capturedByWhite = capturedByWhite;
            this.capturedByBlack = capturedByBlack;
            this.moveHistory = moveHistory;
            this.displayMoves = displayMoves;
            this.sanHistory = sanHistory;
            this.lastMove = lastMove;
            this.isWhiteToMove = isWhiteToMove;
            this.isGameActive = isGameActive;
            this.isGameOver = isGameOver;
            this.engineEnabled = engineEnabled;
            this.engineIsWhite = engineIsWhite;
            this.flipBoard = flipBoard;
            this.enPassantTile = enPassantTile;
            this.whiteClockMillis = whiteClockMillis;
            this.blackClockMillis = blackClockMillis;
            this.clockStarted = clockStarted;
            this.lastResultTag = lastResultTag;
            this.lastMoveDeliveredCheck = lastMoveDeliveredCheck;
            this.lastMoveDeliveredMate = lastMoveDeliveredMate;
        }
    }

}
