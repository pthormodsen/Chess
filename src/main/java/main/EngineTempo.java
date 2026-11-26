package main;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * How Stockfish plays at a given Elo: its skill level and how long it "thinks". In untimed games (all web games
 * vs Stockfish) the pace is human-like: quick in the opening,
 * with forced replies and obvious recaptures, slower in tense middlegame positions, now and then a longer
 * think. Strength comes from the Elo/skill setting; this only paces the moves.
 */
public final class EngineTempo {

    static final long MIN_MILLIS = 250;
    static final long MAX_MILLIS = 3000;

    private EngineTempo() {
    }

    /** Think time for the side to move after {@code uciMoves} (from the start position). */
    public static Duration thinkTime(List<String> uciMoves, int elo, Random random) {
        com.github.bhlangonijr.chesslib.Board position = new com.github.bhlangonijr.chesslib.Board();
        Square lastTarget = null;
        boolean lastWasCapture = false;
        try {
            for (String uci : uciMoves) {
                Move move = new Move(uci, position.getSideToMove());
                lastWasCapture = position.getPiece(move.getTo()) != Piece.NONE
                    || (move.getTo() == position.getEnPassant() && position.getPiece(move.getFrom()).getPieceType()
                        == com.github.bhlangonijr.chesslib.PieceType.PAWN);
                lastTarget = move.getTo();
                position.doMove(move);
            }
        } catch (RuntimeException e) {
            return Duration.ofMillis(Math.round(baseMillis(elo) * jitter(random)));
        }

        List<Move> legal = position.legalMoves();
        if (legal.size() <= 1) {
            return Duration.ofMillis(MIN_MILLIS + random.nextInt(200)); // nothing to think about
        }

        int ply = uciMoves.size();
        double factor = 1.0;
        boolean quick = false;
        if (ply < 8) {
            factor *= 0.45; // opening moves come almost from memory
            quick = true;
        } else if (ply < 16) {
            factor *= 0.75;
        }

        final Square recaptureSquare = lastTarget;
        boolean canRecapture = lastWasCapture && legal.stream().anyMatch(move -> move.getTo() == recaptureSquare);
        if (canRecapture) {
            factor *= 0.5; // the obvious recapture
            quick = true;
        } else if (position.isKingAttacked() || legal.size() <= 5) {
            factor *= 0.7; // few options
        } else {
            long captures = legal.stream().filter(move -> position.getPiece(move.getTo()) != Piece.NONE).count();
            if (captures >= 4) {
                factor *= 1.25; // lots of tension on the board
            }
        }

        factor *= jitter(random);
        if (!quick && random.nextDouble() < 0.08) {
            factor *= 1.8; // a longer think now and then
        }
        long millis = Math.round(baseMillis(elo) * factor);
        return Duration.ofMillis(Math.max(MIN_MILLIS, Math.min(MAX_MILLIS, millis)));
    }

    /** Think time in timed games (the desktop app): the Elo's base time scaled by the time control, with some noise. */
    static Duration timedThinkTime(int elo, int timeControlMinutes, int ply, int pieceCount, Random random) {
        long scaledBaseMillis = Math.round(timedBaseMillis(elo) * timeControlFactor(timeControlMinutes));
        long jitter = -220 + (long) (random.nextDouble() * 601);
        long openingAdjustment = ply < 10 ? -120 : 0;
        long endgameAdjustment = pieceCount <= 12 ? 250 : 0;
        long millis = Math.max(500, Math.min(5200, scaledBaseMillis + jitter + openingAdjustment + endgameAdjustment));
        return Duration.ofMillis(millis);
    }

    static long timedBaseMillis(int elo) {
        if(elo <= 1000) return 700;
        if(elo <= 1200) return 900;
        if(elo <= 1400) return 1100;
        if(elo <= 1600) return 1350;
        if(elo <= 1800) return 1600;
        if(elo <= 2000) return 1900;
        if(elo <= 2200) return 2300;
        if(elo <= 2400) return 2700;
        return 3200;
    }

    private static double timeControlFactor(int minutes) {
        if(minutes <= 1) return 0.65;
        if(minutes <= 3) return 0.85;
        if(minutes <= 5) return 1.0;
        if(minutes <= 10) return 1.35;
        if(minutes <= 15) return 1.55;
        if(minutes <= 30) return 1.85;
        return 2.1;
    }

    /** Stockfish's Skill Level (0–20) for a target Elo, used alongside UCI_Elo. */
    public static int skillLevelForElo(int elo) {
        if(elo <= 900) return 1;
        if(elo <= 1100) return 3;
        if(elo <= 1300) return 5;
        if(elo <= 1500) return 7;
        if(elo <= 1700) return 9;
        if(elo <= 1900) return 11;
        if(elo <= 2100) return 13;
        if(elo <= 2300) return 15;
        if(elo <= 2500) return 17;
        return 20;
    }

    /** Typical think time: about 0.45 s at 800 Elo up to 1.45 s at 2800. */
    static long baseMillis(int elo) {
        int clamped = Math.max(800, Math.min(2800, elo));
        return 450 + Math.round((clamped - 800) * 0.5);
    }

    /** Log-normal spread around 1: most moves near the typical time, a few clearly faster or slower. */
    private static double jitter(Random random) {
        return Math.exp(random.nextGaussian() * 0.35);
    }
}
