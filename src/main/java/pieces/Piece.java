package pieces;

import main.Board;

public class Piece {

    public int col, row;

    public boolean isWhite;
    public String name;
    public int value;

    public boolean isFirstMove = true;

    Board board;

    public Piece(Board board) {
        this.board = board;
    }

    public boolean isValidMovement(int col, int row){
        return true;
    }

    public boolean moveCollidesWithPiece(int col, int row){
        return false;
    }

}
