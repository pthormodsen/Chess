package pieces;

import main.Board;
import java.awt.image.BufferedImage;

public class Queen extends Piece {

    public Queen(Board board, int col, int row, boolean isWhite) {
        super(board);
        this.col = col;
        this.row = row;
        this.xPos = col * board.tileSize;
        this.yPos = row * board.tileSize;
        this.isWhite = isWhite;
        this.name = "Queen";

        this.sprite = sheet.getSubimage(1 * sheetScale, isWhite ? 0 : sheetScale,
            sheetScale, sheetScale).getScaledInstance(board.tileSize, board.tileSize, BufferedImage.SCALE_SMOOTH);
    }

    @Override
    public boolean isValidMovement(int col, int row) {
        // Rook-like OR Bishop-like
        boolean rookMove = (this.col == col || this.row == row);
        boolean bishopMove = (Math.abs(this.col - col) == Math.abs(this.row - row));
        return rookMove || bishopMove;
    }

    @Override
    public boolean moveCollidesWithPiece(int col, int row) {

        // Left
        if (this.col > col && this.row == row) {
            for (int c = this.col - 1; c > col; c--) {
                if (board.getPiece(c, this.row) != null) return true;
            }
        }

        // Right
        if (this.col < col && this.row == row) {
            for (int c = this.col + 1; c < col; c++) {
                if (board.getPiece(c, this.row) != null) return true;
            }
        }

        // Up
        if (this.row > row && this.col == col) {
            for (int r = this.row - 1; r > row; r--) {
                if (board.getPiece(this.col, r) != null) return true;
            }
        }

        // Down
        if (this.row < row && this.col == col) {
            for (int r = this.row + 1; r < row; r++) {
                if (board.getPiece(this.col, r) != null) return true;
            }
        }

        // Up left
        if (this.col > col && this.row > row) {
            for (int i = 1; i < Math.abs(this.col - col); i++) {
                if (board.getPiece(this.col - i, this.row - i) != null) return true;
            }
        }

        // Up right
        if (this.col < col && this.row > row) {
            for (int i = 1; i < Math.abs(this.col - col); i++) {
                if (board.getPiece(this.col + i, this.row - i) != null) return true;
            }
        }

        // Down left
        if (this.col > col && this.row < row) {
            for (int i = 1; i < Math.abs(this.col - col); i++) {
                if (board.getPiece(this.col - i, this.row + i) != null) return true;
            }
        }

        // Down right
        if (this.col < col && this.row < row) {
            for (int i = 1; i < Math.abs(this.col - col); i++) {
                if (board.getPiece(this.col + i, this.row + i) != null) return true;
            }
        }

        return false;
    }
}