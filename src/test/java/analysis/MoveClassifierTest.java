package analysis;

import org.junit.jupiter.api.Test;

import static analysis.MoveClassifier.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MoveClassifierTest {

    /** A quiet, balanced move with no special circumstances. */
    private static Input move(double winBest, double winAfter, boolean playedBest) {
        return new Input(winBest, winAfter, null, playedBest, false, 0, false, null, false, null, null);
    }

    @Test
    void lossThresholdsMatchChessComBands() {
        assertEquals(BEST, classify(move(55, 52, true)).tag());
        assertEquals(EXCELLENT, classify(move(55, 53.5, false)).tag());
        assertEquals(GOOD, classify(move(55, 51, false)).tag());
        assertEquals(INACCURACY, classify(move(55, 47, false)).tag());
        assertEquals(MISTAKE, classify(move(55, 40, false)).tag());
        assertEquals(BLUNDER, classify(move(55, 20, false)).tag());
    }

    @Test
    void bestMoveCountsAsZeroLossEvenWithSearchNoise() {
        Result result = classify(move(60, 54, true));
        assertEquals(BEST, result.tag());
        assertEquals(0, result.loss());
    }

    @Test
    void bookWinsWhileInTheory() {
        Input book = new Input(52, 40, null, false, true, 0, false, null, false, null, null);
        assertEquals(BOOK, classify(book).tag());
    }

    @Test
    void greatNeedsAnOnlyMove() {
        Input onlyMove = new Input(55, 55, 30.0, true, false, 0, false, null, false, null, null);
        Input manyGoodMoves = new Input(55, 55, 52.0, true, false, 0, false, null, false, null, null);
        Input recapture = new Input(55, 55, 10.0, true, false, 0, true, null, false, null, null);
        Input alreadyMating = new Input(100, 100, 60.0, true, false, 0, false, 2, true, null, null);

        assertEquals(GREAT, classify(onlyMove).tag());
        assertEquals(BEST, classify(manyGoodMoves).tag());
        assertEquals(BEST, classify(recapture).tag(), "taking back a piece is not a great find");
        assertEquals(BEST, classify(alreadyMating).tag(), "continuing a forced mate is just best");
    }

    @Test
    void brilliantNeedsASoundSacrificeNotAnEvalJump() {
        Input soundSac = new Input(65, 66, 50.0, true, false, 3, false, null, false, null, null);
        Input unsoundSac = new Input(45, 44, 30.0, true, false, 3, false, null, false, null, null);
        Input noSacrifice = new Input(40, 90, 30.0, true, false, 0, false, null, false, null, null);
        Input cashingIn = new Input(99, 99, 98.0, true, false, 5, false, null, false, null, null);
        Input sacrificeIntoMate = new Input(99, 100, 98.0, true, false, 9, false, 2, true, null, null);

        assertEquals(BRILLIANT, classify(soundSac).tag());
        assertEquals(GREAT, classify(unsoundSac).tag(), "the mover ends up worse: no brilliancy");
        assertEquals(GREAT, classify(noSacrifice).tag(), "a big eval jump alone is not brilliant");
        assertEquals(BEST, classify(cashingIn).tag(), "giving material back when completely winning");
        assertEquals(BRILLIANT, classify(sacrificeIntoMate).tag());
    }

    @Test
    void missWhenFailingToPunishTheOpponentsError() {
        // Opponent blundered (40 -> 85 for us); we play a move that only returns to roughly where we were.
        Input miss = new Input(85, 45, null, false, false, 0, false, null, false, 40.0, 42.0);
        // Same error but now we are much worse than before the opponent's mistake: a plain blunder.
        Input blunder = new Input(85, 20, null, false, false, 0, false, null, false, 40.0, 42.0);
        // No error by the opponent: a normal mistake.
        Input mistake = new Input(60, 45, null, false, false, 0, false, null, false, 1.0, 59.0);

        assertEquals(MISS, classify(miss).tag());
        assertEquals(BLUNDER, classify(blunder).tag());
        assertEquals(MISTAKE, classify(mistake).tag());
    }

    @Test
    void missingAShortMateIsAMissEvenIfStillWinning() {
        Input missedMate = new Input(100, 98.5, null, false, false, 0, false, 2, false, null, null);
        Input keptLongerMate = new Input(100, 100, null, false, false, 0, false, 2, true, null, null);

        assertEquals(MISS, classify(missedMate).tag());
        assertEquals(EXCELLENT, classify(keptLongerMate).tag());
    }
}
