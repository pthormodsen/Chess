package analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import uci.StockfishClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameAnalyzerTest {

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
