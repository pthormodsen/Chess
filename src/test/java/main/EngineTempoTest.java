package main;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTempoTest {

    private static double averageMillis(List<String> moves, int elo) {
        Random random = new Random(42);
        return IntStream.range(0, 400)
            .mapToLong(i -> EngineTempo.thinkTime(moves, elo, random).toMillis())
            .average().orElseThrow();
    }

    @Test
    void staysWithinBounds() {
        Random random = new Random(7);
        List<String> middlegame = List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d3", "f8c5", "c2c3", "d7d6");
        for (int i = 0; i < 1000; i++) {
            long millis = EngineTempo.thinkTime(middlegame, 2800, random).toMillis();
            assertTrue(millis >= EngineTempo.MIN_MILLIS && millis <= EngineTempo.MAX_MILLIS, "got " + millis);
        }
    }

    @Test
    void openingIsFasterThanMiddlegame() {
        double opening = averageMillis(List.of("e2e4"), 1500);
        double middlegame = averageMillis(List.of("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "d2d3", "f8c5",
            "c2c3", "d7d6", "e1g1", "e8g8", "b1d2", "a7a6", "c4b3", "c8e6", "f1e1", "e6b3", "d1b3"), 1500);
        assertTrue(opening < middlegame * 0.7, opening + " vs " + middlegame);
    }

    @Test
    void recaptureIsQuick() {
        // 1.e4 d5 2.exd5: Black's Qxd5 recapture.
        double recapture = averageMillis(List.of("e2e4", "d7d5", "e4d5"), 2000);
        assertTrue(recapture < EngineTempo.baseMillis(2000) * 0.4, "recapture took " + recapture);
    }

    @Test
    void forcedReplyIsQuick() {
        // 1.e3 f6 2.Qh5+: g6 is Black's only legal move.
        List<String> moves = List.of("e2e3", "f7f6", "d1h5");
        long millis = EngineTempo.thinkTime(moves, 2800, new Random(1)).toMillis();
        assertTrue(millis < 500, "forced reply took " + millis);
    }
}
