package main;

import org.junit.jupiter.api.Test;
import pieces.Bishop;
import pieces.King;
import pieces.Knight;
import pieces.Pawn;
import pieces.Queen;
import pieces.Rook;

import java.lang.reflect.Field;

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
        board.enableManualSetup();

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

    @Test
    void blackClockRunsWhileEngineIsThinking() throws Exception {
        Board board = createBoardWithKings(4, 7, 4, 0);
        board.pieceList.add(new Rook(board, 0, 7, true));

        King whiteKing = (King) board.getPiece(4, 7);
        Move move = new Move(board, whiteKing, 4, 6);
        assertTrue(board.isValidMove(move));
        board.makeMove(move);

        setPrivateField(board, "engineThinking", true);
        setPrivateField(board, "lastClockUpdateMillis", System.currentTimeMillis() - 2500);

        assertEquals("09:57", board.getBlackClockText());
    }

    @Test
    void cannotCaptureKing() {
        Board board = createBoardWithKings(0, 7, 4, 0);
        Queen queen = new Queen(board, 4, 6, true);
        board.pieceList.add(queen);

        Move captureKing = new Move(board, queen, 4, 0);

        assertFalse(board.isValidMove(captureKing));
        assertFalse(board.playMove(4, 6, 4, 0));
        assertSame(queen, board.getPiece(4, 6));
        assertNotNull(board.getPiece(4, 0));
    }

    @Test
    void rejectsMovesOutsideBoard() {
        Board board = createBoardWithKings(4, 7, 4, 0);
        Queen queen = new Queen(board, 3, 6, true);
        board.pieceList.add(queen);

        Move offBoardMove = new Move(board, queen, -1, 6);

        assertFalse(board.isValidMove(offBoardMove));
        assertFalse(board.playMove(3, 6, -1, 6));
        assertFalse(board.playMove(-1, 6, 3, 6));
        assertSame(queen, board.getPiece(3, 6));
        assertTrue(board.getLegalDestinations(-1, 6).isEmpty());
    }

    @Test
    void enPassantCannotExposeKingToRook() throws Exception {
        Board board = createBoardWithKings(7, 3, 7, 0);
        board.pieceList.add(new Rook(board, 0, 3, false));
        Pawn blackPawn = new Pawn(board, 1, 1, false);
        Pawn whitePawn = new Pawn(board, 2, 3, true);
        board.pieceList.add(blackPawn);
        board.pieceList.add(whitePawn);

        setPrivateField(board, "isWhiteToMove", false);
        Move blackDoublePush = new Move(board, blackPawn, 1, 3);
        assertTrue(board.isValidMove(blackDoublePush));
        board.makeMove(blackDoublePush);

        Move illegalEnPassant = new Move(board, whitePawn, 1, 2);

        assertFalse(board.isValidMove(illegalEnPassant));
    }

    @Test
    void enPassantExpiresAfterAnyOtherMove() {
        // Regression: the en passant square used to survive non-pawn moves, allowing a late capture.
        Board board = startedServerBoard();
        for (String uci : new String[]{"e2e4", "g8f6", "e4e5", "d7d5", "g1f3", "b8c6"}) {
            assertTrue(board.playUciMove(uci), uci);
        }

        assertFalse(board.playUciMove("e5d6"));
        assertNotNull(board.getPiece(3, 3), "black d5 pawn must still be on the board");
        assertTrue(board.getLegalDestinations(4, 3).stream().noneMatch(square -> square.col == 3 && square.row == 2));
    }

    @Test
    void enPassantCannotCaptureOwnPiece() {
        // Regression: a white knight standing on the double-pushed pawn's square was "captured" en passant.
        Board board = startedServerBoard();
        for (String uci : new String[]{"e2e4", "a7a6", "e4e5", "a6a5", "b1c3", "d7d5", "c3d5", "a8a7"}) {
            assertTrue(board.playUciMove(uci), uci);
        }

        assertFalse(board.playUciMove("e5d6"));
        assertTrue(board.getPiece(3, 3) instanceof Knight, "white knight on d5 must survive");
        assertTrue(board.getPiece(3, 3).isWhite);
        assertEquals("-", board.getCapturedByBlackText());
    }

    @Test
    void enPassantStillAllowedImmediatelyAfterDoublePush() {
        Board board = startedServerBoard();
        for (String uci : new String[]{"e2e4", "a7a6", "e4e5", "d7d5"}) {
            assertTrue(board.playUciMove(uci), uci);
        }

        assertTrue(board.playUciMove("e5d6"));
        assertNull(board.getPiece(3, 3));
        assertTrue(board.getDisplayMovesSnapshot().get(2).startsWith("3. exd6"));
    }

    @Test
    void promotionHonoursRequestedPiece() {
        Board board = startedServerBoard();
        for (String uci : new String[]{"a2a4", "b7b5", "a4b5", "a7a6", "b5a6", "b8c6", "a6a7", "a8b8"}) {
            assertTrue(board.playUciMove(uci), uci);
        }

        assertTrue(board.playMove(0, 1, 0, 0, 'n'));

        assertTrue(board.getPiece(0, 0) instanceof Knight);
        assertEquals("a7a8n", board.getMoveHistorySnapshot().get(8));
        assertTrue(board.getDisplayMovesSnapshot().get(4).contains("a8=N"));
    }

    @Test
    void uciPromotionLetterIsApplied() {
        Board board = startedServerBoard();
        for (String uci : new String[]{"a2a4", "b7b5", "a4b5", "a7a6", "b5a6", "b8c6", "a6a7", "a8b8"}) {
            assertTrue(board.playUciMove(uci), uci);
        }

        assertTrue(board.playUciMove("a7b8r"));

        assertTrue(board.getPiece(1, 0) instanceof Rook);
        assertEquals("a7b8r", board.getMoveHistorySnapshot().get(8));
        assertFalse(board.playMove(0, 6, 0, 5, 'k'), "invalid promotion letters are rejected");
    }

    @Test
    void humanCannotMoveForTheEngine() throws Exception {
        Board board = startedServerBoard();
        setPrivateField(board, "engineEnabled", true);
        setPrivateField(board, "engineIsWhite", false);

        assertTrue(board.playMove(4, 6, 4, 4));  // e2e4 by the human (white)
        assertTrue(board.isEngineTurn());
        assertFalse(board.playMove(4, 1, 4, 3), "e7e5 belongs to the engine");
        assertTrue(board.getLegalDestinations(4, 1).isEmpty());
        assertEquals(1, board.getMoveHistorySnapshot().size());
    }

    @Test
    void movesAreRejectedDuringReviewOrAfterGameEnds() throws Exception {
        Board board = startedServerBoard();
        setPrivateField(board, "analysisMode", true);
        assertFalse(board.playMove(4, 6, 4, 4));

        setPrivateField(board, "analysisMode", false);
        setPrivateField(board, "isGameActive", false);
        assertFalse(board.playMove(4, 6, 4, 4));
        assertTrue(board.getMoveHistorySnapshot().isEmpty());
    }

    @Test
    void serverBoardDoesNotStartEngineEagerly() {
        Board board = new Board(Runnable::run);
        assertFalse(board.isEngineEnabled());
        board.dispose();
    }

    private Board startedServerBoard() {
        Board board = new Board(Runnable::run);
        board.startNewGame();
        return board;
    }

    private Board createBoardWithKings(int whiteCol, int whiteRow, int blackCol, int blackRow) {
        Board board = new Board();
        board.pieceList.clear();
        board.pieceList.add(new King(board, whiteCol, whiteRow, true));
        board.pieceList.add(new King(board, blackCol, blackRow, false));
        board.enableManualSetup();
        return board;
    }

    private void setPrivateField(Board board, String fieldName, Object value) throws Exception {
        Field field = Board.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(board, value);
    }
}
