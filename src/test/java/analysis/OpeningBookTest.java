package analysis;

import com.github.bhlangonijr.chesslib.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpeningBookTest {

    private static Board play(String... uci) {
        Board board = new Board();
        for (String move : uci) {
            board.doMove(Tactics.parseLegal(board, move));
        }
        return board;
    }

    @Test
    void loadsTheBundledBook() {
        assertTrue(OpeningBook.standard().size() > 3000);
    }

    @Test
    void namesMainLinesAndRecognisesTranspositions() {
        OpeningBook book = OpeningBook.standard();
        assertEquals("B20", book.opening(play("e2e4", "c7c5")).eco());
        assertTrue(book.opening(play("e2e4", "c7c5")).name().startsWith("Sicilian Defense"));

        // Queen's Gambit Declined reached via 1.c4 instead of 1.d4.
        Board transposed = play("c2c4", "e7e6", "d2d4", "d7d5");
        Board direct = play("d2d4", "d7d5", "c2c4", "e7e6");
        assertTrue(book.isBook(transposed));
        assertEquals(book.opening(direct), book.opening(transposed));
    }

    @Test
    void offbeatMovesLeaveTheBook() {
        assertFalse(OpeningBook.standard().isBook(play("e2e4", "e7e5", "e1e2", "e8e7", "e2e1")));
    }
}
