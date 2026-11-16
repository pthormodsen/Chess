package uci;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockfishClientTest {

    @Test
    void parsesMultiPvCentipawnLine() {
        StockfishClient.Line line = StockfishClient.parseInfoLine(
            "info depth 14 seldepth 19 multipv 2 score cp -35 nodes 143536 nps 1510905 time 95 pv d2d3 b7b5 a4b3");

        assertNotNull(line);
        assertEquals(2, line.multipv);
        assertEquals(14, line.depth);
        assertEquals(-35, line.scoreCp);
        assertNull(line.mate);
        assertEquals(List.of("d2d3", "b7b5", "a4b3"), line.pv);
    }

    @Test
    void keepsMateSeparateFromCentipawns() {
        StockfishClient.Line line = StockfishClient.parseInfoLine("info depth 20 score mate -3 pv e8f7 d1h5");

        assertNotNull(line);
        assertEquals(1, line.multipv, "lines without multipv are the best line");
        assertEquals(-3, line.mate);
        assertEquals(0, line.scoreCp);
    }

    @Test
    void ignoresBoundsAndNonScoreLines() {
        assertNull(StockfishClient.parseInfoLine("info depth 10 score cp 31 lowerbound pv e2e4"));
        assertNull(StockfishClient.parseInfoLine("info depth 10 score cp 31 upperbound pv e2e4"));
        assertNull(StockfishClient.parseInfoLine("info string NNUE evaluation using nn-1.nnue"));
        assertNull(StockfishClient.parseInfoLine("info depth 3 currmove e2e4 currmovenumber 1"));
        assertNull(StockfishClient.parseInfoLine("bestmove e2e4"));
    }
}
