package analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoveExplainerTest {

    private static String explain(String fen, String played, String tag, List<String> bestLine, List<String> replyLine,
                                  Integer moverMateBefore, Integer opponentMateAfter) {
        Board before = new Board();
        before.loadFromFen(fen);
        Move move = Tactics.parseLegal(before, played);
        assertNotNull(move, played);
        Board after = before.clone();
        after.doMove(move);
        return MoveExplainer.explain(new MoveExplainer.Context(before, after, move, tag, bestLine, replyLine,
            moverMateBefore, null, opponentMateAfter, null, false, null, "+0.3 → -2.5"));
    }

    @Test
    void hangsAPiece() {
        // 1...Nf6-g4?? puts the knight where the h3 pawn takes it for free.
        String text = explain("4k3/8/5n2/8/8/7P/8/4K3 b - - 0 1", "f6g4", MoveClassifier.BLUNDER,
            List.of("e8e7"), List.of("h3g4"), null, null);
        assertTrue(text.startsWith("Hangs the knight on g4"), text);
    }

    @Test
    void allowsAKnightFork() {
        // Black plays Kd8-e8?? and Nb5-c7+ forks king and rook.
        String text = explain("r2k4/8/8/1N6/8/8/8/4K3 b - - 0 1", "d8e8", MoveClassifier.MISTAKE,
            List.of("d8d7"), List.of("b5c7", "e8d7", "c7a8"), null, null);
        assertTrue(text.startsWith("Allows a knight fork with Nc7+"), text);
        assertTrue(text.contains("king and rook"), text);
    }

    @Test
    void allowsMate() {
        // 1...a6?? ignores the back rank.
        String text = explain("6k1/p4ppp/8/8/8/8/5PPP/3R2K1 b - - 0 1", "a7a6", MoveClassifier.BLUNDER,
            List.of("h7h6"), List.of("d1d8"), null, 1);
        assertTrue(text.startsWith("Allows mate in 1, starting with Rd8#"), text);
    }

    @Test
    void missesMate() {
        String text = explain("6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1", "g1f1", MoveClassifier.MISS,
            List.of("d1d8"), List.of("h7h6"), 1, null);
        assertTrue(text.startsWith("Misses mate in 1 with Rd8#"), text);
    }

    @Test
    void bookMoveNamesTheOpening() {
        Board before = new Board();
        Move move = Tactics.parseLegal(before, "e2e4");
        Board after = before.clone();
        after.doMove(move);
        String text = MoveExplainer.explain(new MoveExplainer.Context(before, after, move, MoveClassifier.BOOK,
            List.of(), List.of(), null, null, null, null, false, "King's Pawn Game", null));
        assertEquals("A book move in the King's Pawn Game.", text);
    }
}
