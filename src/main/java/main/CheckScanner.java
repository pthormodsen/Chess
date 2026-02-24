package main;

import pieces.Piece;

public class CheckScanner {

    private final Board board;

    public CheckScanner(Board board) {
        this.board = board;
    }

    public boolean isKingChecked(Move move) {

        Piece king = board.findKing(move.piece.isWhite);
        if (king == null) return false;

        int kingCol = king.col;
        int kingRow = king.row;

        if ("King".equals(move.piece.name)) {
            kingCol = move.newCol;
            kingRow = move.newRow;
        }

        return  hitByRook(move, king, kingCol, kingRow, 0, 1)  ||
            hitByRook(move, king, kingCol, kingRow, 1, 0)  ||
            hitByRook(move, king, kingCol, kingRow, 0, -1) ||
            hitByRook(move, king, kingCol, kingRow, -1, 0) ||

            hitByBishop(move, king, kingCol, kingRow, -1, -1) ||
            hitByBishop(move, king, kingCol, kingRow, 1, -1)  ||
            hitByBishop(move, king, kingCol, kingRow, 1, 1)   ||
            hitByBishop(move, king, kingCol, kingRow, -1, 1)  ||

            hitByKnight(move, king, kingCol, kingRow) ||
            hitByPawn(move, king, kingCol, kingRow)   ||
            hitByKing(move, king, kingCol, kingRow);
    }


    private Piece pieceAt(int col, int row, Move move) {
        if (col < 0 || col > 7 || row < 0 || row > 7) return null;

        if (col == move.newCol && row == move.newRow) return move.piece;

        if (col == move.piece.col && row == move.piece.row) return null;

        if (move.capture != null && col == move.capture.col && row == move.capture.row) return null;

        return board.getPiece(col, row);
    }

    private boolean hitByRook(Move move, Piece king, int kingCol, int kingRow, int colVal, int rowVal) {
        for (int i = 1; i < 8; i++) {
            int c = kingCol + (i * colVal);
            int r = kingRow + (i * rowVal);

            Piece piece = pieceAt(c, r, move);
            if (piece != null) {
                if (!board.sameTeam(piece, king) &&
                    ("Rook".equals(piece.name) || "Queen".equals(piece.name))) {
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private boolean hitByBishop(Move move, Piece king, int kingCol, int kingRow, int colVal, int rowVal) {
        for (int i = 1; i < 8; i++) {
            int c = kingCol + (i * colVal);
            int r = kingRow + (i * rowVal);

            Piece piece = pieceAt(c, r, move);
            if (piece != null) {
                if (!board.sameTeam(piece, king) &&
                    ("Bishop".equals(piece.name) || "Queen".equals(piece.name))) {
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private boolean hitByKnight(Move move, Piece king, int kingCol, int kingRow) {
        return checkKnight(pieceAt(kingCol - 1, kingRow - 2, move), king) ||
            checkKnight(pieceAt(kingCol + 1, kingRow - 2, move), king) ||
            checkKnight(pieceAt(kingCol + 2, kingRow - 1, move), king) ||
            checkKnight(pieceAt(kingCol + 2, kingRow + 1, move), king) ||
            checkKnight(pieceAt(kingCol + 1, kingRow + 2, move), king) ||
            checkKnight(pieceAt(kingCol - 1, kingRow + 2, move), king) ||
            checkKnight(pieceAt(kingCol - 2, kingRow + 1, move), king) ||
            checkKnight(pieceAt(kingCol - 2, kingRow - 1, move), king);
    }

    private boolean checkKnight(Piece p, Piece king) {
        return p != null && !board.sameTeam(p, king) && "Knight".equals(p.name);
    }

    private boolean hitByKing(Move move, Piece king, int kingCol, int kingRow) {
        return checkKing(pieceAt(kingCol - 1, kingRow - 1, move), king) ||
            checkKing(pieceAt(kingCol + 1, kingRow - 1, move), king) ||
            checkKing(pieceAt(kingCol,     kingRow - 1, move), king) ||
            checkKing(pieceAt(kingCol - 1, kingRow,     move), king) ||
            checkKing(pieceAt(kingCol + 1, kingRow,     move), king) ||
            checkKing(pieceAt(kingCol - 1, kingRow + 1, move), king) ||
            checkKing(pieceAt(kingCol + 1, kingRow + 1, move), king) ||
            checkKing(pieceAt(kingCol,     kingRow + 1, move), king);
    }

    private boolean checkKing(Piece p, Piece king) {
        return p != null && !board.sameTeam(p, king) && "King".equals(p.name);
    }

    private boolean hitByPawn(Move move, Piece king, int kingCol, int kingRow) {
        int colorVal = king.isWhite ? -1 : 1;

        return checkPawn(pieceAt(kingCol + 1, kingRow + colorVal, move), king) ||
            checkPawn(pieceAt(kingCol - 1, kingRow + colorVal, move), king);
    }

    private boolean checkPawn(Piece p, Piece king) {
        return p != null && !board.sameTeam(p, king) && "Pawn".equals(p.name);
    }

    public boolean isGameOver(Piece king){
        for (Piece piece :  board.pieceList){
            if(board.sameTeam(piece, king)){
                board.selectedPiece = piece == king ? king : null;
                for(int row  = 0; row < board.rows; row++){
                    for(int col  = 0; col < board.cols; col++){
                        Move move = new Move(board, piece, col, row);
                            if(board.isValidMove(move)){
                                return false;
                            }
                    }
                }
            }
        }
        return true;
    }

}