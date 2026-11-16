package analysis;

import java.util.List;

/**
 * The one place that turns engine numbers into chess.com-style move classifications.
 *
 * <p>Everything is measured in the mover's win chance (0–100, from {@link GameAnalyzer#winPercent}), so a
 * one-pawn swing matters much more in a balanced position than when one side is already up a queen.
 * Losses below are "win-percentage points lost compared with the engine's best move":
 * <ul>
 *   <li>Best – the engine's top move (or a move the engine scores identically)</li>
 *   <li>Excellent &lt; 2, Good &lt; 5, Inaccuracy &lt; 10, Mistake &lt; 20, Blunder ≥ 20</li>
 *   <li>Brilliant – a best/near-best move that sacrifices material and is sound</li>
 *   <li>Great – the only good move: the engine's second choice is at least 10 points worse</li>
 *   <li>Miss – failing to punish the opponent's error (or a short forced mate), without making things
 *       worse than before that error</li>
 *   <li>Book – still inside opening theory</li>
 * </ul>
 */
public final class MoveClassifier {

    public static final String BRILLIANT = "Brilliant";
    public static final String GREAT = "Great";
    public static final String BEST = "Best";
    public static final String EXCELLENT = "Excellent";
    public static final String GOOD = "Good";
    public static final String BOOK = "Book";
    public static final String INACCURACY = "Inaccuracy";
    public static final String MISTAKE = "Mistake";
    public static final String MISS = "Miss";
    public static final String BLUNDER = "Blunder";

    /** Display order, best to worst, used for summaries. */
    public static final List<String> ORDER = List.of(
        BRILLIANT, GREAT, BEST, EXCELLENT, GOOD, BOOK, INACCURACY, MISTAKE, MISS, BLUNDER);

    static final double EXCELLENT_LIMIT = 2;
    static final double GOOD_LIMIT = 5;
    static final double INACCURACY_LIMIT = 10;
    static final double MISTAKE_LIMIT = 20;
    /** "Only move": the second-best line must be at least this much worse. */
    static final double GREAT_MARGIN = 10;
    /** A missed forced mate is reported as a Miss only for short mates. */
    static final int MISSED_MATE_MAX = 5;

    private MoveClassifier() {}

    /**
     * Facts about one move, all win chances from the mover's perspective (0–100).
     *
     * @param winBest                 win chance after the engine's best move (the position's evaluation)
     * @param winAfter                win chance after the move that was played
     * @param winSecondBest           win chance after the engine's second choice, or null if unknown / only one move
     * @param playedBest              the move is the engine's top choice (or scored identically to it)
     * @param book                    the position after the move is still opening theory
     * @param sacrifice               material (pawns) the move leaves en prise, net of what it captured
     * @param obviousMove             a recapture, a free capture or a reply to check: never a "great find"
     * @param moverMateBefore         mate-in-N the mover had before the move (N &gt; 0), else null
     * @param moverMateAfter          the mover still has a forced mate after the move
     * @param opponentLastLoss        how much the opponent's previous move cost them, null at the start
     * @param winBeforeOpponentMove   the mover's win chance before the opponent's previous move, null at the start
     */
    public record Input(
        double winBest,
        double winAfter,
        Double winSecondBest,
        boolean playedBest,
        boolean book,
        int sacrifice,
        boolean obviousMove,
        Integer moverMateBefore,
        boolean moverMateAfter,
        Double opponentLastLoss,
        Double winBeforeOpponentMove
    ) {}

    /** @param loss win-percentage points lost versus the best move (0 for the best move itself). */
    public record Result(String tag, double loss) {}

    public static Result classify(Input in) {
        double loss = in.playedBest() ? 0 : Math.max(0, in.winBest() - in.winAfter());

        if (in.book()) {
            return new Result(BOOK, loss);
        }

        boolean missedMate = in.moverMateBefore() != null && in.moverMateBefore() <= MISSED_MATE_MAX
            && !in.moverMateAfter() && !in.playedBest();

        if (loss < EXCELLENT_LIMIT && !missedMate) {
            if (isBrilliant(in, loss)) {
                return new Result(BRILLIANT, loss);
            }
            if (in.playedBest()) {
                return new Result(isGreat(in) ? GREAT : BEST, loss);
            }
            return new Result(EXCELLENT, loss);
        }

        if (missedMate) {
            // Throwing away a mate is a Miss while the position stays winning, otherwise it is graded normally.
            if (in.winAfter() >= 50 || loss < MISTAKE_LIMIT) {
                return new Result(MISS, loss);
            }
        } else if (loss >= INACCURACY_LIMIT && isMiss(in)) {
            return new Result(MISS, loss);
        }
        return new Result(byLoss(loss), loss);
    }

    static String byLoss(double loss) {
        if (loss < EXCELLENT_LIMIT) return EXCELLENT;
        if (loss < GOOD_LIMIT) return GOOD;
        if (loss < INACCURACY_LIMIT) return INACCURACY;
        if (loss < MISTAKE_LIMIT) return MISTAKE;
        return BLUNDER;
    }

    /**
     * A sound sacrifice: gives up at least two pawns of material, the engine still likes the result for the
     * mover, and the game was not already trivially won (where "sacrifices" are just cashing in) – unless the
     * sacrifice itself forces mate, the classic brilliancy.
     */
    private static boolean isBrilliant(Input in, double loss) {
        return in.sacrifice() >= 2
            && loss < EXCELLENT_LIMIT
            && in.winAfter() >= 50
            && (in.winBest() < 97 || in.moverMateAfter());
    }

    /** Winning (2), roughly equal (1) or losing (0), by win chance. */
    static int outcome(double win) {
        return win >= 65 ? 2 : win > 35 ? 1 : 0;
    }

    /** The only good move, excluding obvious moves and positions whose outcome does not hinge on it. */
    private static boolean isGreat(Input in) {
        // Continuing a forced mate, or recapturing, is just the best move, not a "great find".
        if (in.winSecondBest() == null || in.obviousMove() || in.moverMateBefore() != null) {
            return false;
        }
        // The only move that keeps the result: every alternative drops from winning to equal, or equal to losing.
        boolean onlyMove = in.winBest() - in.winSecondBest() >= GREAT_MARGIN;
        return onlyMove && outcome(in.winBest()) > outcome(in.winSecondBest());
    }

    /**
     * The opponent just erred and this move gives the gift back: it is much worse than the best move, yet the
     * mover is no worse off than before the opponent's error. Losing more than that is a Mistake/Blunder.
     */
    private static boolean isMiss(Input in) {
        if (in.opponentLastLoss() == null || in.winBeforeOpponentMove() == null) {
            return false;
        }
        return in.opponentLastLoss() >= INACCURACY_LIMIT
            && in.winAfter() >= in.winBeforeOpponentMove() - 5;
    }

    /** Accuracy cap per class, so a lucky small win-chance loss never makes a Blunder look accurate. */
    static double accuracyCap(String tag) {
        if (tag == null) {
            return 100;
        }
        return switch (tag) {
            case EXCELLENT -> 96;
            case GOOD -> 91;
            case INACCURACY -> 78;
            case MISTAKE, MISS -> 58;
            case BLUNDER -> 28;
            default -> 100;
        };
    }
}
