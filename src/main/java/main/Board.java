package main;

import pieces.*;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.stream.Collectors;

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

    public Board() {
        this.setPreferredSize(new Dimension(cols * tileSize, rows * tileSize));
        this.addMouseListener(input);
        this.addMouseMotionListener(input);
        loadPositionFromFEN(fenStartingPosition);
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

            isWhiteToMove = !isWhiteToMove;

            lastMove = move;

            updateGameState();
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
        capture(move.piece);
    }

    public void capture(Piece piece){
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
    }

    private void updateGameState(){
        Piece king = findKing(isWhiteToMove);
        if(checkScanner.isGameOver(king)){
            if(checkScanner.isKingChecked(new Move(this, king, king.col, king.row))){
                System.out.println(isWhiteToMove ? "Black wins!" : "White wins!");
            } else {
                System.out.println("Stalemate");
            }
            isGameOver = true;
        } else if (insufficientMaterial(true) && insufficientMaterial(false)) {
            System.out.println("Insufficient material!");
            isGameOver = true;
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

        //Paint legal moves
        if(selectedPiece != null)
        for(int r = 0; r < rows; r++){
            for(int c = 0; c < cols; c++){
                if(isValidMove(new Move(this, selectedPiece, c, r))){

                    g2d.setColor(new Color(68,180,57,190));
                    g2d.fillRect(c * tileSize, r * tileSize, tileSize, tileSize);

                }
            }
        }

        //Painting each piece
        for(Piece piece : pieceList){
            piece.paint(g2d);
        }
    }


}
