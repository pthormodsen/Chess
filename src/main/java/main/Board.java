package main;

import pieces.*;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.function.Consumer;

import uci.StockfishClient;

public class Board extends JPanel {

    public String fenStartingPosition = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public int tileSize = 85;
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
    private final ArrayList<String> capturedByWhite = new ArrayList<>();
    private final ArrayList<String> capturedByBlack = new ArrayList<>();
    private StockfishClient stockfishClient;
    private ExecutorService engineExecutor;
    private boolean engineEnabled = false;
    private boolean engineIsWhite = false;
    private int engineSkillLevel = 8;
    private int engineElo = 1200;
    private Duration engineThinkTime = Duration.ofMillis(700);
    private Consumer<String> statusConsumer;
    private Consumer<String> gameEndConsumer;
    private Consumer<String> evaluationConsumer;
    private java.util.function.BiConsumer<String, String> captureConsumer;


    public Board() {
        this.setPreferredSize(new Dimension(cols * tileSize, rows * tileSize));
        this.addMouseListener(input);
        this.addMouseMotionListener(input);
        loadPositionFromFEN(fenStartingPosition);
        initializeEngineIntegration();
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

    public void setEngineSkillLevel(int level) {
        this.engineSkillLevel = Math.max(0, Math.min(20, level));
        configureEngine();
    }

    public void setEngineElo(int elo){
        this.engineElo = Math.max(800, Math.min(2800, elo));
        configureEngine();
    }

    public boolean isGameActive(){
        return isGameActive;
    }

    public void startNewGame(){
        loadPositionFromFEN(fenStartingPosition);
        isGameActive = true;
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
        if(!isGameActive){
            return;
        }

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

            capture(move.capture);

            recordMove(move);

            isWhiteToMove = !isWhiteToMove;

            lastMove = move;

            updateGameState();

            requestEngineMoveIfNeeded();
            notifyEvaluation();
            repaint();
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
    }

    public void capture(Piece piece){
        if(piece == null){
            return;
        }
        if(piece.isWhite){
            capturedByBlack.add(pieceIcon(piece));
        } else {
            capturedByWhite.add(pieceIcon(piece));
        }
        removePiece(piece);
        notifyCaptures();
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

        Piece king = findKing(isWhiteToMove);
        if(checkScanner.isGameOver(king)){
            String message;
            if(checkScanner.isKingChecked(new Move(this, king, king.col, king.row))){
                message = isWhiteToMove ? "Black wins!" : "White wins!";
            } else {
                message = "Stalemate!";
            }
            finishGame(message);
        } else if (insufficientMaterial(true) && insufficientMaterial(false)) {
            finishGame("Draw: Insufficient material!");
        } else if(statusConsumer != null){
            statusConsumer.accept(isWhiteToMove ? "White to move" : "Black to move");
        }
    }

    private void finishGame(String message){
        isGameOver = true;
        isGameActive = false;
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
                g2d.fillRect(c * tileSize, r * tileSize, tileSize, tileSize);
            }
        }

        if(lastMove != null){
            g2d.setColor(new Color(246, 246, 105, 160));
            g2d.fillRect(lastMove.oldCol * tileSize, lastMove.oldRow * tileSize, tileSize, tileSize);
            g2d.fillRect(lastMove.newCol * tileSize, lastMove.newRow * tileSize, tileSize, tileSize);
        }

        if(selectedPiece != null){
            g2d.setColor(new Color(255, 255, 140, 160));
            g2d.fillRect(selectedPiece.col * tileSize, selectedPiece.row * tileSize, tileSize, tileSize);
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

                        int x = c * tileSize;
                        int y = r * tileSize;

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
            piece.paint(g2d);
        }
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

    private void recordMove(Move move){
        moveHistory.add(toUci(move.oldCol, move.oldRow, move.newCol, move.newRow, move.piece));
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

    private void shutdownEngine(){
        engineEnabled = false;
        if(engineExecutor != null){
            engineExecutor.shutdownNow();
        }
        if(stockfishClient != null){
            try{
                stockfishClient.close();
            } catch (IOException ignored){
            }
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


}
