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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import javazoom.jl.player.Player;

import uci.StockfishClient;

public class Board extends JPanel {

    public String fenStartingPosition = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public int tileSize = 75;
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
    private boolean flipBoard = false;
    private int engineSkillLevel = 8;
    private int engineElo = 1200;
    private Duration engineThinkTime = Duration.ofMillis(500);
    private long initialClockMillis = Duration.ofMinutes(10).toMillis();
    private long whiteClockMillis = initialClockMillis;
    private long blackClockMillis = initialClockMillis;
    private javax.swing.Timer clockTimer;
    private String lastResultTag = "*";
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


    public Board() {
        this.setPreferredSize(new Dimension(cols * tileSize, rows * tileSize));
        this.setBackground(new Color(43, 43, 43));
        this.setOpaque(true);
        this.addMouseListener(input);
        this.addMouseMotionListener(input);
        loadPositionFromFEN(fenStartingPosition);
        initializeEngineIntegration();
        loadSounds();
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
        lastResultTag = liveStateBackup.lastResultTag;
        lastMoveDeliveredCheck = liveStateBackup.lastMoveDeliveredCheck;
        lastMoveDeliveredMate = liveStateBackup.lastMoveDeliveredMate;
        lastMove = rebuildStoredMove(liveStateBackup.lastMove);

        notifyCaptures();
        notifyEvaluation();
        notifyMoveLog();
        notifyClock();
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
        configureEngine();
    }

    public void setEnginePlaysWhite(boolean playsWhite){
        this.engineIsWhite = playsWhite;
        this.flipBoard = playsWhite;
        if(isGameActive && isWhiteToMove == engineIsWhite){
            requestEngineMoveIfNeeded();
        }
    }

    public void setHumanVsHuman(boolean enabled){
        this.engineEnabled = !enabled && stockfishClient != null;
        if(enabled){
            shutdownEngine();
        } else if(stockfishClient == null){
            initializeEngineIntegration();
        }
    }

    public boolean isGameActive(){
        return isGameActive;
    }

    public void startNewGame(){
        clearAnalysisReview();
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
        if(triggerSideEffects && !isGameActive){
            return;
        }

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

        move.piece.col = move.newCol;
        move.piece.row = move.newRow;
        move.piece.xPos = move.newCol * tileSize;
        move.piece.yPos = move.newRow * tileSize;

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
    }

    private void moveKing(Move move){

        if(Math.abs(move.piece.col - move.newCol) == 2){
            int rookStartCol = move.piece.col < move.newCol ? 7 : 0;
            int rookTargetCol = move.piece.col < move.newCol ? 5 : 3;

            Piece rook = getPiece(rookStartCol, move.piece.row);
            if(rook != null && "Rook".equals(rook.name)){
                rook.col = rookTargetCol;
                rook.row = move.piece.row;
                rook.xPos = rook.col * tileSize;
                rook.yPos = rook.row * tileSize;
                rook.isFirstMove = false;
                move.isCastle = true;
            }
        }

    }

    private void movePawn(Move move) {

        //en passant
        int colorIndex = move.piece.isWhite ? 1 : -1;

        if(getTileNum(move.newCol, move.newRow) == enPassantTile){
            move.capture = getPiece(move.newCol, move.newRow + colorIndex);
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
        pieceList.add(new Queen(this, move.newCol, move.newRow, move.piece.isWhite));
        removePiece(move.piece);
        move.isPromotion = true;
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

        if(move.piece.isWhite != isWhiteToMove){
            return false;
        }

        if(sameTeam(move.piece, move.capture)){
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
            String message;
            if(kingChecked){
                lastMoveDeliveredMate = true;
                message = isWhiteToMove ? "Black wins!" : "White wins!";
            } else {
                message = "Stalemate!";
            }
            finishGame(message);
        } else if (insufficientMaterial(true) && insufficientMaterial(false)) {
            finishGame("Draw: Insufficient material!");
        } else if (kingChecked){
            playSound(checkSound);
            if(statusConsumer != null){
                statusConsumer.accept((isWhiteToMove ? "White" : "Black") + " is in check!");
            }
        } else if(statusConsumer != null){
            statusConsumer.accept(isWhiteToMove ? "White to move" : "Black to move");
        }
    }

    private void finishGame(String message){
        isGameOver = true;
        isGameActive = false;
        if(clockTimer != null){
            clockTimer.stop();
        }
        playSound(notifySound);
        if(message.contains("White wins")){
            lastResultTag = "1-0";
        } else if(message.contains("Black wins")){
            lastResultTag = "0-1";
        } else {
            lastResultTag = "1/2-1/2";
        }
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
                int drawCol = flipBoard ? cols - 1 - c : c;
                int drawRow = flipBoard ? rows - 1 - r : r;
                g2d.setColor((c + r) % 2 == 0 ? new Color(240, 217, 181) : new Color(181, 139, 99));
                g2d.fillRect(drawCol * tileSize, drawRow * tileSize, tileSize, tileSize);
            }
        }

        if(lastMove != null){
            Color moveHighlightColor = analysisMode && analysisPointer >= 0
                ? qualityHighlightColor(currentQualityTag)
                : new Color(246, 246, 105, 160);
            g2d.setColor(moveHighlightColor);
            g2d.fillRect(applyFlip(lastMove.oldCol) * tileSize, applyFlipRow(lastMove.oldRow) * tileSize, tileSize, tileSize);
            g2d.fillRect(applyFlip(lastMove.newCol) * tileSize, applyFlipRow(lastMove.newRow) * tileSize, tileSize, tileSize);
        }

        if(selectedPiece != null){
            g2d.setColor(new Color(255, 255, 140, 160));
            g2d.fillRect(applyFlip(selectedPiece.col) * tileSize, applyFlipRow(selectedPiece.row) * tileSize, tileSize, tileSize);
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

                        int x = applyFlip(c) * tileSize;
                        int y = applyFlipRow(r) * tileSize;

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

        if(analysisMode && analysisPointer >= 0 && lastMove != null){
            MoveHighlight actualArrow = new MoveHighlight(lastMove.oldCol, lastMove.oldRow, lastMove.newCol, lastMove.newRow);
            drawArrow(g2d, actualArrow, qualityColor(currentQualityTag));
        }
        if(bestMoveArrow != null){
            drawArrow(g2d, bestMoveArrow, new Color(65, 190, 120, 220));
        }

        drawQualityBadge(g2d);

        //Painting each piece
        for(Piece piece : pieceList){
            int oldX = piece.xPos;
            int oldY = piece.yPos;
            if(isDraggingPiece && piece == selectedPiece){
                piece.xPos = dragScreenX;
                piece.yPos = dragScreenY;
            } else {
                piece.xPos = applyFlip(piece.col) * tileSize;
                piece.yPos = applyFlipRow(piece.row) * tileSize;
            }
            piece.paint(g2d);
            piece.xPos = oldX;
            piece.yPos = oldY;
        }
    }

    private Color qualityColor(String tag){
        if(tag == null){
            return new Color(80, 150, 255, 200);
        }
        switch (tag){
            case "Brilliant":
                return new Color(0, 191, 255, 220);
            case "Great":
                return new Color(42, 199, 183, 210);
            case "Best":
                return new Color(69, 184, 84, 210);
            case "Excellent":
                return new Color(116, 191, 67, 210);
            case "Good":
                return new Color(102, 178, 255, 210);
            case "Inaccuracy":
                return new Color(224, 207, 53, 210);
            case "Mistake":
                return new Color(236, 151, 31, 210);
            case "Blunder":
                return new Color(212, 63, 58, 210);
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
        int fromX = applyFlip(arrow.fromCol) * tileSize + tileSize / 2;
        int fromY = applyFlipRow(arrow.fromRow) * tileSize + tileSize / 2;
        int toX = applyFlip(arrow.toCol) * tileSize + tileSize / 2;
        int toY = applyFlipRow(arrow.toRow) * tileSize + tileSize / 2;

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
        int x = (cols * tileSize - badgeWidth) / 2;
        int y = 8;
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


    private void initializeEngineIntegration(){
        String configuredPath = System.getenv("STOCKFISH_PATH");
        if(configuredPath == null){
            configuredPath = System.getProperty("stockfish.path");
        }
        if(configuredPath == null){
            return;
        }

        Path enginePath = Paths.get(configuredPath);
        if(!Files.exists(enginePath)){
            System.err.println("Stockfish engine not found at " + enginePath.toAbsolutePath());
            return;
        }

        try{
            engineBinaryPath = enginePath;
            stockfishClient = new StockfishClient(enginePath);
            configureEngine();
            engineExecutor = Executors.newSingleThreadExecutor();
            engineEnabled = true;
            engineIsWhite = false;
            System.out.println("Stockfish ready on path " + enginePath.toAbsolutePath());
        } catch (IOException e){
            System.err.println("Unable to start Stockfish: " + e.getMessage());
        }
    }

    private void requestEngineMoveIfNeeded(){
        if(!engineEnabled || stockfishClient == null || engineExecutor == null || !isGameActive){
            return;
        }
        if(isGameOver){
            shutdownEngine();
            return;
        }
        if(isWhiteToMove != engineIsWhite){
            return;
        }
        final String moves = String.join(" ", moveHistory);
        engineExecutor.submit(() -> {
            try{
                String uciMove = stockfishClient.requestBestMove(moves, engineThinkTime);
                if(uciMove != null){
                    SwingUtilities.invokeLater(() -> applyUciMove(uciMove));
                }
            } catch (IOException e){
                System.err.println("Engine failure: " + e.getMessage());
            }
        });
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

        Piece piece = getPiece(fromCol, fromRow);
        if(piece == null){
            return null;
        }
        return new Move(this, piece, toCol, toRow);
    }

    private void recordMove(Move move, String notation){
        moveHistory.add(toUci(move.oldCol, move.oldRow, move.newCol, move.newRow, move.piece));
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

    private String toUci(int fromCol, int fromRow, int toCol, int toRow, Piece piece){
        StringBuilder sb = new StringBuilder();
        sb.append(squareName(fromCol, fromRow));
        sb.append(squareName(toCol, toRow));
        if("Pawn".equals(piece.name)){
            int promotionRank = piece.isWhite ? 0 : 7;
            if(toRow == promotionRank){
                sb.append('q');
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

        analysisFrameConsumer.accept(new AnalysisFrame(
            analysisPointer,
            reviewSanMoves.size(),
            san,
            entry,
            lastAnalysisSummary
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
        String severity = entry.severity;
        if(severity != null && severity.startsWith("Mate")){
            return true;
        }
        if(entry.bestMove == null || entry.playedMove == null){
            return false;
        }
        return !entry.bestMove.equals(entry.playedMove);
    }

    private void clearAnalysisReview(){
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
            sb.append("=Q");
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

    public String getLastResultTag(){
        return lastResultTag;
    }

    public void analyzeGame(java.util.function.Consumer<java.util.List<String>> consumer){
        if(analysisMode){
            restoreLiveGameState();
        }
        if(engineBinaryPath == null){
            consumer.accept(java.util.List.of("Engine path not configured. Set STOCKFISH_PATH to enable analysis."));
            return;
        }
        List<String> movesCopy = new ArrayList<>(moveHistory);
        List<String> sanCopy = new ArrayList<>(sanHistory);
        analysisExecutor.submit(() -> {
            try{
                GameAnalyzer analyzer = new GameAnalyzer(engineBinaryPath, Duration.ofMillis(800));
                GameAnalyzer.Summary summary = analyzer.analyze(movesCopy);
                List<String> report = GameReviewFormatter.buildReport(summary, sanCopy);
                SwingUtilities.invokeLater(() -> {
                    prepareAnalysisReview(summary, movesCopy, sanCopy);
                    consumer.accept(report);
                });
            } catch (IOException e){
                SwingUtilities.invokeLater(() -> consumer.accept(java.util.List.of("Analysis failed: " + e.getMessage())));
            }
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
        whiteClockMillis = initialClockMillis;
        blackClockMillis = initialClockMillis;
        if(clockTimer != null){
            clockTimer.stop();
        }
        clockTimer = new javax.swing.Timer(1000, e -> tickClock());
        clockTimer.start();
        notifyClock();
    }

    private void tickClock(){
        if(!isGameActive){
            return;
        }
        if(isWhiteToMove){
            whiteClockMillis = Math.max(0, whiteClockMillis - 1000);
            if(whiteClockMillis == 0){
                finishGame("Black wins on time");
                notifyClock();
                return;
            }
        } else {
            blackClockMillis = Math.max(0, blackClockMillis - 1000);
            if(blackClockMillis == 0){
                finishGame("White wins on time");
                notifyClock();
                return;
            }
        }
        notifyClock();
    }

    private void shutdownEngine(){
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

    public static final class AnalysisFrame {
        public final int plyIndex;
        public final int totalPlies;
        public final String san;
        public final GameAnalyzer.Entry entry;
        public final GameAnalyzer.Summary summary;

        public AnalysisFrame(int plyIndex, int totalPlies, String san,
                             GameAnalyzer.Entry entry, GameAnalyzer.Summary summary) {
            this.plyIndex = plyIndex;
            this.totalPlies = totalPlies;
            this.san = san;
            this.entry = entry;
            this.summary = summary;
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
            this.lastResultTag = lastResultTag;
            this.lastMoveDeliveredCheck = lastMoveDeliveredCheck;
            this.lastMoveDeliveredMate = lastMoveDeliveredMate;
        }
    }

}
