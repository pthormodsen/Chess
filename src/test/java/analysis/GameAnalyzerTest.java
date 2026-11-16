package analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import support.FakeUciEngine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import java.util.List;

import uci.StockfishClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsEveryMoveAsItIsClassifiedAndCachesTheResult() throws Exception {
        Path engine = FakeUciEngine.write(tempDir, "cp 20", "e2e4");
        Map<String, GameAnalyzer.Summary> store = new HashMap<>();
        GameAnalyzer.ResultCache cache = new GameAnalyzer.ResultCache() {
            public GameAnalyzer.Summary get(String key) { return store.get(key); }
            public void put(String key, GameAnalyzer.Summary summary) { store.put(key, summary); }
        };
        List<String> moves = List.of("e2e4", "e7e5", "g1f3", "a7a5");
        List<Integer> progress = new ArrayList<>();

        GameAnalyzer.Summary summary = new GameAnalyzer(engine, 6, 2).withCache(cache)
            .analyze(moves, (done, total, entries) -> {
                assertEquals(4, total);
                assertEquals(done, entries.size());
                progress.add(done);
            });

        assertEquals(List.of(1, 2, 3, 4), progress);
        assertEquals("e4", summary.entries.get(0).san);
        assertEquals("Book", summary.entries.get(0).qualityTag);
        assertEquals("Book", summary.entries.get(2).qualityTag);
        assertTrue(summary.entries.get(3).fenBefore.startsWith("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b"));
        assertNotNull(summary.openingName);
        assertEquals(2, summary.white.counts.get("Book"));

        // A second request for the same game never starts the engine (this path does not exist).
        GameAnalyzer.Summary cached = new GameAnalyzer(Path.of("/nonexistent/stockfish"), 6, 2).withCache(cache).analyze(moves);
        assertSame(summary, cached);
    }

    @Test
    void mateScoresAreNotFlattenedIntoCentipawns() throws Exception {
        // Every position: side to move mates in 2. White's win chance is 100 / 0, never a "+100 pawns" guess.
        Path engine = FakeUciEngine.write(tempDir, "mate 2", "e2e4");
        GameAnalyzer.Summary summary = new GameAnalyzer(engine, 6, 1).analyze(List.of("e2e4"));
        GameAnalyzer.Entry entry = summary.entries.get(0);

        assertEquals(2, entry.mateBefore);
        assertEquals(-2, entry.mateAfter);
        assertEquals(100.0, entry.winBefore);
        assertEquals(0.0, entry.winAfter);
    }

    @Test
    void blackToMoveLosingScoreConvertsToWhiteAdvantage() {
        StockfishClient.AnalysisResult result =
            new StockfishClient.AnalysisResult("0000", -10000, -1, List.of());

        assertEquals(100.0, GameAnalyzer.toWhitePerspective(result, false));
    }

    @Test
    void scholarMateKeepsMateScoreFromWhitePerspective() {
        GameAnalyzer.Summary summary = new GameAnalyzer.Summary(List.of(
            new GameAnalyzer.Entry(0, true, "e2e4", "e2e4", List.of(), 0.3, 0.2, 0.1, "Good", "Best"),
            new GameAnalyzer.Entry(1, false, "e7e5", "e7e5", List.of(), 0.2, 0.4, 0.2, "Good", "Best"),
            new GameAnalyzer.Entry(2, true, "d1h5", "d1h5", List.of(), 0.4, 0.7, -0.3, "Good", "Great"),
            new GameAnalyzer.Entry(3, false, "b8c6", "g8f6", List.of(), 0.7, 3.0, 2.3, "Inaccuracy", "Inaccuracy"),
            new GameAnalyzer.Entry(4, true, "f1c4", "f1c4", List.of(), 3.0, 4.0, -1.0, "Good", "Great"),
            new GameAnalyzer.Entry(5, false, "g8f6", "d8e7", List.of(), 0.0, 100.0, 100.0, "Blunder", "Blunder"),
            new GameAnalyzer.Entry(6, true, "h5f7", "h5f7", List.of(), 100.0, 100.0, 0.0, "Mate in 1", "Mate")
        ));

        GameAnalyzer.Entry mate = summary.entries.get(6);

        assertEquals("Mate", mate.qualityTag);
        assertTrue(mate.loss <= 0.01);
        assertEquals(1, summary.blunders);
        assertTrue(summary.whiteAccuracy > summary.blackAccuracy);
    }

    @Test
    void blundersMakeAccuracyDropSubstantially() {
        GameAnalyzer.Summary summary = new GameAnalyzer.Summary(List.of(
            new GameAnalyzer.Entry(0, true, "e2e4", "e2e4", List.of(), 0.3, 0.2, 0.1, "Good", "Best"),
            new GameAnalyzer.Entry(1, false, "e7e5", "e7e5", List.of(), 0.2, 0.1, 0.1, "Good", "Best"),
            new GameAnalyzer.Entry(2, true, "d1h5", "d1h5", List.of(), 0.1, 0.4, -0.3, "Good", "Great"),
            new GameAnalyzer.Entry(3, false, "g8f6", "d8e7", List.of(), 0.0, 8.0, 8.0, "Blunder", "Blunder")
        ));

        assertEquals(1, summary.blunders);
        assertTrue(summary.blackAccuracy < 55);
        assertTrue(summary.accuracyScore < 80);
    }

    @Test
    void repeatedBlundersCannotStillShowNearPerfectAccuracy() {
        List<GameAnalyzer.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double loss = i < 12 ? 8.0 : 0.1;
            String tag = i < 12 ? "Blunder" : "Best";
            boolean whiteMove = i % 2 == 0;
            double evalAfter = whiteMove ? -loss : loss;
            entries.add(new GameAnalyzer.Entry(i, whiteMove, "a2a3", "b1c3", List.of(), 0.0, evalAfter, loss, tag, tag));
        }

        GameAnalyzer.Summary summary = new GameAnalyzer.Summary(entries);

        assertEquals(12, summary.blunders);
        assertTrue(summary.accuracyScore < 75);
    }
}
