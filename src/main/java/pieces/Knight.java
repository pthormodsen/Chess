package pieces;

import main.Board;

public class Knight extends Piece{

    public Knight(Board board, int col, int row, boolean isWhite) {
        super(board);
        this.col = col;
        this.row = row;
        this.isWhite = isWhite;
        this.name = "Knight";
        this.value = 3;
    }

    public boolean isValidMovement(int col, int row){
        return Math.abs(col - this.col) * Math.abs(row - this.row) == 2;
    }

}
