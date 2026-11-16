package analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TacticsTest {

    private static Board fen(String fen) {
        Board board = new Board();
        board.loadFromFen(fen);
        return board;
    }

    @Test
    void sanConversionStopsAtIllegalMoves() {
        Board board = new Board();
        assertEquals(List.of("e4", "e5", "Nf3"), Tactics.toSan(board, List.of("e2e4", "e7e5", "g1f3", "e2e4")));
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", board.getFen(), "board untouched");
    }

    @Test
    void seeSeesThroughExchanges() {
        // White Rxd5 wins an undefended knight: +3.
        Board free = fen("4k3/8/8/3n4/8/8/8/3RK3 w - - 0 1");
        assertEquals(3, Tactics.see(free, Square.D5, Side.WHITE));
        // Pawn-defended knight attacked by a rook: taking loses the rook, so SEE is 0 (just don't take).
        Board defended = fen("4k3/8/4p3/3n4/8/8/8/3RK3 w - - 0 1");
        assertEquals(0, Tactics.see(defended, Square.D5, Side.WHITE));
    }

    @Test
    void findsHangingPieceButNotDefendedOnes() {
        // Black to move; White's knight on e5 is undefended and attacked by the d6 pawn... and the queen.
        Board board = fen("4k3/8/3p4/4N3/8/8/8/4K3 b - - 0 1");
        Tactics.Capture hanging = Tactics.worstHangingPiece(board);
        assertNotNull(hanging);
        assertEquals(PieceType.KNIGHT, hanging.victim());
        assertEquals(Square.E5, hanging.square());

        Board defended = fen("4k3/8/3p4/4N3/3P4/8/8/4K3 b - - 0 1");
        assertNotNull(Tactics.worstHangingPiece(defended), "pawn takes knight still wins 2");
        Board rookDefended = fen("4k3/8/8/4N3/8/8/8/4RK2 b - - 0 1");
        assertNull(Tactics.worstHangingPiece(rookDefended), "nothing attacks the knight");
    }

    @Test
    void detectsKnightForkOfKingAndRook() {
        // White knight on c7 checks the king on e8 and attacks the rook on a8.
        Board board = fen("r3k3/2N5/8/8/8/8/8/4K3 b - - 0 1");
        List<Square> targets = Tactics.forkTargets(board, Square.C7);
        assertTrue(targets.contains(Square.E8));
        assertTrue(targets.contains(Square.A8));
    }

    @Test
    void rendersBestLineWithPositions() {
        List<Tactics.LinePly> line = Tactics.renderLine(new Board().getFen(), List.of("e2e4", "e7e5", "zzzz"));
        assertEquals(2, line.size());
        assertEquals("e4", line.get(0).san());
        assertEquals("e5", line.get(1).san());
        assertTrue(line.get(1).fen().startsWith("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w"));
    }

    @Test
    void materialAfterLineFinishesExchanges() {
        // 1.Rxd5 exd5: playing only one ply would show White a knight up; the full exchange shows a rook-for-knight loss.
        Board board = fen("4k3/8/4p3/3n4/8/8/8/3RK3 w - - 0 1");
        int material = Tactics.materialAfterLine(board, List.of("d1d5", "e6d5"), 1, Side.WHITE);
        assertEquals(-1, material, "rook (5) for knight (3) with a black pawn left: 0 - 1 = -1");
    }
}
