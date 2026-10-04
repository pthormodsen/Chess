package main;

import analysis.GameAnalyzer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import support.FakeUciEngine;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression tests: the way a game ended (clock, checkmate, stalemate) comes from the board,
 * and engine mate scores / huge evaluations never relabel a played move as "mate".
 */
class GameResultReviewTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireFakeEngine() {
        assumeTrue(System.getenv("STOCKFISH_PATH") == null, "STOCKFISH_PATH is set; skipping fake-engine tests");
    }

    @AfterEach
    void clearEngine() {
        System.clearProperty("stockfish.path");
    }

    @Test
    void whiteWinsOnTimeAfterNonMatingMove() throws Exception {
        // Engine claims a forced mate for every position, as in the reported game: must not become "mate".
        Board board = playedBoard(FakeUciEngine.write(tempDir, "mate 3", "e2e4"), "e2e4", "e7e5", "g1f3");

        flagFall(board, false);

        assertEquals(Board.GameEndReason.TIMEOUT, board.getGameEndReason());
        assertEquals("White won on time", board.getGameEndMessage());
        assertEquals("1-0", board.getLastResultTag());
        assertFalse(board.getDisplayMovesSnapshot().get(1).contains("#"));

        Board.AnalysisFrame last = reviewFinalPly(board);
        assertEquals("Nf3", last.san);
        assertFalse(last.deliveredMate, "a timeout game's last move did not deliver mate");
        assertNotEquals("Mate", last.entry.qualityTag);
        assertEquals(Board.GameEndReason.TIMEOUT, last.gameEndReason);
        assertEquals("White won on time", last.gameEndMessage);
        assertNotNull(last.entry.mateAfter, "engine mate score is kept as evaluation info");
    }

    @Test
    void blackWinsOnTimeAfterNonMatingMove() throws Exception {
        Board board = playedBoard(FakeUciEngine.write(tempDir, "mate 2", "d2d4"), "e2e4", "e7e5");

        flagFall(board, true);

        assertEquals(Board.GameEndReason.TIMEOUT, board.getGameEndReason());
        assertEquals("Black won on time", board.getGameEndMessage());
        assertEquals("0-1", board.getLastResultTag());

        Board.AnalysisFrame last = reviewFinalPly(board);
        assertEquals("e5", last.san);
        assertFalse(last.deliveredMate);
        assertNotEquals("Mate", last.entry.qualityTag);
        assertEquals("Black won on time", last.gameEndMessage);
    }

    @Test
    void genuineCheckmateStillSaysMate() throws Exception {
        // Fool's mate. The fake engine even reports a centipawn score: the board alone decides it is mate.
        Board board = playedBoard(FakeUciEngine.write(tempDir, "cp 35", "e2e4"), "f2f3", "e7e5", "g2g4", "d8h4");

        assertEquals(Board.GameEndReason.CHECKMATE, board.getGameEndReason());
        assertEquals("Black won by checkmate", board.getGameEndMessage());
        assertEquals("0-1", board.getLastResultTag());

        Board.AnalysisFrame last = reviewFinalPly(board);
        assertEquals("Qh4#", last.san);
        assertTrue(last.deliveredMate);
        assertEquals(Board.GameEndReason.CHECKMATE, last.gameEndReason);
        assertEquals("Black won by checkmate", last.gameEndMessage);
    }

    @Test
    void hugeCentipawnAdvantageIsNotMate() throws Exception {
        // +15.81 and a tablebase-sized +120 must stay plain evaluations.
        for (String score : List.of("cp 1581", "cp 12000")) {
            Board board = playedBoard(FakeUciEngine.write(tempDir, score, "e2e4"), "e2e4", "e7e5", "g1f3");

            Board.AnalysisFrame last = reviewFinalPly(board);
            assertFalse(last.deliveredMate, score);
            assertNull(last.entry.mateBefore, score);
            assertNull(last.entry.mateAfter, score);
            assertNotEquals("Mate", last.entry.qualityTag, score);
            assertNull(last.gameEndReason, "game is still running: " + score);
            assertTrue(Math.abs(last.entry.evalAfter) > 15, score);
            for (GameAnalyzer.Entry entry : last.summary.entries) {
                assertNotEquals("Mate", entry.qualityTag, score);
            }
        }
    }

    @Test
    void stalemateIsADrawNotMate() throws Exception {
        // Sam Loyd's ten-move stalemate.
        Board board = playedBoard(FakeUciEngine.write(tempDir, "mate 1", "e2e4"),
            "e2e3", "a7a5", "d1h5", "a8a6", "h5a5", "h7h5", "h2h4", "a6h6", "a5c7", "f7f6",
            "c7d7", "e8f7", "d7b7", "d8d3", "b7b8", "d3h7", "b8c8", "f7g6", "c8e6");

        assertEquals(Board.GameEndReason.STALEMATE, board.getGameEndReason());
        assertEquals("Draw by stalemate", board.getGameEndMessage());
        assertEquals("1/2-1/2", board.getLastResultTag());

        Board.AnalysisFrame last = reviewFinalPly(board);
        assertFalse(last.san.contains("#"));
        assertFalse(last.deliveredMate);
        assertNotEquals("Mate", last.entry.qualityTag);
        assertEquals("Draw by stalemate", last.gameEndMessage);
    }

    @Test
    void insufficientMaterialIsADrawNotMate() {
        Board board = new Board(Runnable::run);
        board.pieceList.clear();
        board.pieceList.add(new pieces.King(board, 3, 7, true));    // Kd1
        board.pieceList.add(new pieces.King(board, 4, 0, false));   // Ke8
        board.pieceList.add(new pieces.Knight(board, 3, 6, false)); // undefended Nd2
        board.enableManualSetup();

        assertTrue(board.playMove(3, 7, 3, 6)); // Kxd2 leaves bare kings

        assertEquals(Board.GameEndReason.INSUFFICIENT_MATERIAL, board.getGameEndReason());
        assertEquals("Draw by insufficient material", board.getGameEndMessage());
        assertEquals("1/2-1/2", board.getLastResultTag());
        assertFalse(board.getDisplayMovesSnapshot().get(0).contains("#"));
    }

    private Board playedBoard(Path engine, String... uciMoves) {
        System.setProperty("stockfish.path", engine.toString());
        Board board = new Board(Runnable::run);
        board.startNewGame();
        for (String uci : uciMoves) {
            assertTrue(board.playUciMove(uci), uci);
        }
        return board;
    }

    /** Runs the side-to-move's clock down to zero and lets the board notice it. */
    private void flagFall(Board board, boolean whiteFlags) throws Exception {
        assertEquals(whiteFlags, board.isWhiteToMove());
        setField(board, whiteFlags ? "whiteClockMillis" : "blackClockMillis", 5L);
        setField(board, "lastClockUpdateMillis", System.currentTimeMillis() - 1_000);
        (whiteFlags ? (Runnable) board::getWhiteClockText : (Runnable) board::getBlackClockText).run();
        assertTrue(board.isGameOver());
    }

    private Board.AnalysisFrame reviewFinalPly(Board board) throws Exception {
        AtomicReference<Board.AnalysisFrame> frame = new AtomicReference<>();
        board.setAnalysisFrameConsumer(frame::set);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<String>> report = new AtomicReference<>();
        board.analyzeGame(lines -> {
            report.set(lines);
            done.countDown();
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "analysis finished");
        assertTrue(board.isAnalysisMode(), "review started: " + report.get());
        board.stepAnalysis(10_000);
        Board.AnalysisFrame last = frame.get();
        assertNotNull(last);
        assertEquals(last.totalPlies - 1, last.plyIndex);
        if (board.getGameEndMessage() != null) {
            assertTrue(report.get().contains("Result: " + board.getGameEndMessage()), report.get().toString());
        }
        return last;
    }

    private static void setField(Board board, String name, Object value) throws Exception {
        Field field = Board.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(board, value);
    }
}
