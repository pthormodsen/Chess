package main;

import org.junit.jupiter.api.Test;
import pieces.Bishop;
import pieces.King;
import pieces.Pawn;
import pieces.Rook;

import static org.junit.jupiter.api.Assertions.*;

class BoardTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void lastMoveTracksOriginAndDestination() {
        Board board = createBoardWithKings(4, 7, 4, 0);

        King whiteKing = (King) board.getPiece(4, 7);
        Move move = new Move(board, whiteKing, 4, 6);
        assertTrue(board.isValidMove(move));

        board.makeMove(move);

        Move last = board.getLastMove();
        assertNotNull(last);
        assertEquals(4, last.oldCol);
        assertEquals(7, last.oldRow);
        assertEquals(4, last.newCol);
        assertEquals(6, last.newRow);
    }

    @Test
    void castlingMovesRookAndMarksItAsMoved() {
        Board board = new Board();
        board.pieceList.clear();

        King whiteKing = new King(board, 4, 7, true);
        Rook rook = new Rook(board, 7, 7, true);
        King blackKing = new King(board, 4, 0, false);

        board.pieceList.add(whiteKing);
        board.pieceList.add(rook);
        board.pieceList.add(blackKing);

        Move castleMove = new Move(board, whiteKing, 6, 7);
        assertTrue(board.isValidMove(castleMove));
        board.makeMove(castleMove);

        assertSame(rook, board.getPiece(5, 7));
        assertEquals(5, rook.col);
        assertEquals(7, rook.row);
        assertFalse(rook.isFirstMove);
        assertEquals(6, board.getPiece(6, 7).col);
    }

    @Test
    void insufficientMaterialSingleMinorPiecesCountsAsDrawMaterial() {
        Board board = new Board();
        board.pieceList.clear();

        board.pieceList.add(new King(board, 4, 7, true));
        board.pieceList.add(new King(board, 4, 0, false));
        board.pieceList.add(new Bishop(board, 2, 0, true));  // dark square
        board.pieceList.add(new Bishop(board, 4, 2, false)); // same color square

        assertTrue(board.insufficientMaterial(true));
        assertTrue(board.insufficientMaterial(false));
    }

    @Test
    void insufficientMaterialOppositeColorBishopsRequiresPlay() {
        Board board = new Board();
        board.pieceList.clear();

        board.pieceList.add(new King(board, 4, 7, true));
        board.pieceList.add(new King(board, 4, 0, false));
        board.pieceList.add(new Bishop(board, 2, 0, true));  // dark square
        board.pieceList.add(new Bishop(board, 5, 2, true));  // light square

        assertFalse(board.insufficientMaterial(true));
        assertTrue(board.insufficientMaterial(false));
    }

    @Test
    void enPassantCaptureRemovesPassedPawn() {
        Board board = createBoardWithKings(0, 7, 7, 0);

        Pawn whitePawn = new Pawn(board, 4, 6, true);
        Pawn blackPawn = new Pawn(board, 5, 4, false);
        board.pieceList.add(whitePawn);
        board.pieceList.add(blackPawn);

        Move whiteDoublePush = new Move(board, whitePawn, 4, 4);
        assertTrue(board.isValidMove(whiteDoublePush));
        board.makeMove(whiteDoublePush);

        Move blackEnPassant = new Move(board, blackPawn, 4, 5);
        assertTrue(board.isValidMove(blackEnPassant));
        board.makeMove(blackEnPassant);

        assertNull(board.getPiece(4, 4));
        assertSame(blackPawn, board.getPiece(4, 5));
        assertEquals(4, blackPawn.col);
        assertEquals(5, blackPawn.row);
    }

    private Board createBoardWithKings(int whiteCol, int whiteRow, int blackCol, int blackRow) {
        Board board = new Board();
        board.pieceList.clear();
        board.pieceList.add(new King(board, whiteCol, whiteRow, true));
        board.pieceList.add(new King(board, blackCol, blackRow, false));
        return board;
    }
}
