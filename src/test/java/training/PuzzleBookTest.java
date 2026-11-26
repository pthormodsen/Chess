package training;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PuzzleBookTest {
    private static final String FEN = "r6k/pp2r2p/4Rp1Q/3p4/8/1N1P2R1/PqP2bPP/7K b - - 0 24";

    private static Puzzle puzzle(String id, int rating, String... themes) {
        return new Puzzle(id, FEN, List.of("f2g3", "e6e7", "b2b1", "b3c1"), rating, List.of(themes));
    }

    @Test
    void parsesLichessStyleLine() {
        Puzzle puzzle = PuzzleBook.parse("00sHx\t" + FEN + "\tf2g3 e6e7 b2b1 b3c1 b1c1 h6c1\t1760\tcrushing hangingPiece long middlegame");
        assertEquals("00sHx", puzzle.id());
        assertEquals(6, puzzle.moves().size());
        assertEquals(1760, puzzle.rating());
        assertTrue(puzzle.themes().contains("hangingPiece"));
        // Black to move in the FEN makes the first (opponent) move, so the solver plays White.
        assertEquals("white", puzzle.solverColor());
    }

    @Test
    void rejectsMalformedLines() {
        assertNull(PuzzleBook.parse("id\tfen"));
        assertNull(PuzzleBook.parse("x\t" + FEN + "\te2e4\t1500\tfork"));
        assertNull(PuzzleBook.parse("x\t" + FEN + "\te2e4 zz99\t1500\tfork"));
        assertNull(PuzzleBook.parse("x\t" + FEN + "\te2e4 e7e5\tabc\tfork"));
    }

    @Test
    void picksNearTheRatingAndHonoursThemeAndExclusions() {
        PuzzleBook book = new PuzzleBook(List.of(
            puzzle("low", 800, "fork"),
            puzzle("mid", 1500, "fork"),
            puzzle("mid2", 1520, "pin"),
            puzzle("high", 2400, "mateIn2")));
        Random random = new Random(1);

        assertEquals("low", book.pick(820, null, Set.of(), random).orElseThrow().id());
        assertEquals("mid2", book.pick(1500, "pin", Set.of(), random).orElseThrow().id());
        // No pin near 2400: the window widens instead of failing.
        assertEquals("mid2", book.pick(2400, "pin", Set.of(), random).orElseThrow().id());
        assertNotEquals("mid", book.pick(1500, null, Set.of("mid"), random).orElseThrow().id());
        // Everything with the theme excluded: repeats rather than coming back empty.
        assertEquals("high", book.pick(2400, "mateIn2", Set.of("high"), random).orElseThrow().id());
        assertTrue(book.pick(1500, "noSuchTheme", Set.of(), random).isEmpty());
        assertEquals(2, book.themeCounts().get("fork"));
    }

    @Test
    void bundledPuzzlesAreLegalAndSolverMovesMatchTheSide() {
        PuzzleBook book = PuzzleBook.standard();
        assertTrue(book.size() > 1000, "bundled puzzle collection should be present");
        assertFalse(book.themeCounts().isEmpty());
        for (Puzzle puzzle : book.all()) {
            Board board = new Board();
            board.loadFromFen(puzzle.fen());
            Side solver = puzzle.solverColor().equals("white") ? Side.WHITE : Side.BLACK;
            for (int i = 0; i < puzzle.moves().size(); i++) {
                Move move = new Move(puzzle.moves().get(i), board.getSideToMove());
                if (!board.legalMoves().contains(move)) {
                    fail("Illegal move " + puzzle.moves().get(i) + " in puzzle " + puzzle.id());
                }
                assertEquals(i % 2 == 1, board.getSideToMove() == solver, "turn order in " + puzzle.id());
                board.doMove(move);
            }
        }
    }
}
