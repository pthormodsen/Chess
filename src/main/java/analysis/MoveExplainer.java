package analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a classified move into one or two short sentences a player can act on ("Hangs the knight on f6",
 * "Allows a fork", "Misses mate in 2"), using the engine lines plus static checks on the board.
 */
public final class MoveExplainer {

    private MoveExplainer() {}

    /**
     * @param before        position before the move (mover to move)
     * @param after         position after the move (opponent to move)
     * @param played        the move that was played
     * @param tag           its classification
     * @param bestLine      engine's best line from {@code before} (UCI)
     * @param replyLine     engine's best line from {@code after}, i.e. the opponent's best reply first (UCI)
     * @param moverMateBefore mate-in-N the mover had before the move, else null
     * @param moverMateAfter  mate-in-N the mover still has after the move, else null
     * @param opponentMateAfter mate-in-N the opponent has after the move, else null
     * @param sacrificed    the piece left en prise by a sacrifice, or null
     * @param recapture     the move takes back on the square where the opponent just captured
     * @param opening       opening name for book moves, or null
     * @param evalText      evaluation before → after, e.g. "+0.4 → -1.8", used as a last resort
     */
    public record Context(
        Board before,
        Board after,
        Move played,
        String tag,
        List<String> bestLine,
        List<String> replyLine,
        Integer moverMateBefore,
        Integer moverMateAfter,
        Integer opponentMateAfter,
        Tactics.Capture sacrificed,
        boolean recapture,
        String opening,
        String evalText
    ) {}

    public static String explain(Context c) {
        try {
            return explainUnchecked(c);
        } catch (RuntimeException e) {
            return null; // explanations are a nicety; never fail an analysis over one
        }
    }

    private static String explainUnchecked(Context c) {
        if (c.after().isMated()) {
            return "Checkmate.";
        }
        if (c.after().isStaleMate()) {
            return "Stalemate – the game is drawn.";
        }
        String tag = c.tag() == null ? "" : c.tag();
        String bestSan = c.bestLine().isEmpty() ? null : Tactics.toSan(c.before(), c.bestLine().get(0));
        String replySan = c.replyLine().isEmpty() ? null : Tactics.toSan(c.after(), c.replyLine().get(0));
        Side mover = c.before().getSideToMove();

        switch (tag) {
            case MoveClassifier.BOOK:
                return c.opening() == null ? "A book move – well-known opening theory." : "A book move in the " + c.opening() + ".";
            case MoveClassifier.BRILLIANT:
                return brilliant(c);
            case MoveClassifier.GREAT:
                return withGain(c, "The only good move here – every alternative is much worse.");
            case MoveClassifier.BEST:
                return withGain(c, "The best move in the position.");
            case MoveClassifier.EXCELLENT:
                return withGain(c, bestSan == null ? "Nearly as good as the best move." : "Nearly as good as the best move, " + bestSan + ".");
            case MoveClassifier.GOOD:
                return bestSan == null ? "A decent move, but there was something better." : "A decent move, but " + bestSan + " was better.";
            default:
                break;
        }

        List<String> reasons = new ArrayList<>();
        if (c.opponentMateAfter() != null) {
            reasons.add("Allows mate in " + c.opponentMateAfter() + (replySan == null ? "." : ", starting with " + replySan + "."));
        } else if (c.moverMateBefore() != null && c.moverMateAfter() == null) {
            reasons.add("Misses mate in " + c.moverMateBefore() + (bestSan == null ? "." : " with " + bestSan + "."));
        }

        if (reasons.isEmpty()) {
            String tactic = allowedTactic(c, replySan, mover);
            if (tactic != null) {
                reasons.add(tactic);
            }
        }
        if (reasons.isEmpty()) {
            String missed = missedGain(c, bestSan);
            if (missed != null) {
                reasons.add(missed);
            }
        }
        if (reasons.isEmpty()) {
            if (MoveClassifier.MISS.equals(tag)) {
                reasons.add("Misses the chance to punish the opponent's mistake" + (bestSan == null ? "." : " – " + bestSan + " was much stronger."));
            } else {
                String verdict = switch (tag) {
                    case MoveClassifier.BLUNDER -> "A serious error that changes the course of the game";
                    case MoveClassifier.MISTAKE -> "This noticeably worsens the position";
                    default -> "A small slip";
                };
                String evalPart = c.evalText() == null ? "" : " (" + c.evalText() + ")";
                reasons.add(verdict + evalPart + (bestSan == null ? "." : ". " + bestSan + " was better."));
            }
        } else if (bestSan != null && !reasons.get(0).contains(bestSan)) {
            reasons.add(bestSan + " was better.");
        }
        return String.join(" ", reasons);
    }

    private static String brilliant(Context c) {
        Tactics.Capture sac = c.sacrificed();
        if (sac == null) {
            return "A brilliant sacrifice that the opponent cannot safely accept.";
        }
        if (c.moverMateAfter() != null) {
            return "A brilliant " + Tactics.pieceName(sac.victim()) + " sacrifice that forces mate in " + c.moverMateAfter() + ".";
        }
        return "A brilliant sacrifice: the " + Tactics.pieceName(sac.victim()) + " on " + Tactics.squareName(sac.square())
            + " is offered, but taking it does not pay off.";
    }

    /** Adds what a good move achieves (mate, winning material) when it is clear-cut. */
    private static String withGain(Context c, String fallback) {
        if (c.before().legalMoves().size() == 1) {
            return "Forced – the only legal move.";
        }
        if (c.moverMateAfter() != null) {
            return "Keeps a forced mate in " + c.moverMateAfter() + ".";
        }
        Tactics.Capture capture = Tactics.bestCapture(c.before());
        if (capture != null && capture.gain() >= 2 && capture.move().equals(c.played())) {
            if (c.recapture()) {
                return "Recaptures the " + Tactics.pieceName(capture.victim()) + ".";
            }
            return "Wins the " + Tactics.pieceName(capture.victim()) + " on " + Tactics.squareName(capture.square()) + ".";
        }
        if (Tactics.givesCheck(c.after()) && c.after().legalMoves().size() == 1) {
            return fallback + " It is check with only one reply.";
        }
        return fallback;
    }

    /** What the opponent's best reply exploits: a hanging piece, a fork, or a material-winning sequence. */
    private static String allowedTactic(Context c, String replySan, Side mover) {
        if (c.replyLine().isEmpty()) {
            return null;
        }
        Move reply = Tactics.parseLegal(c.after(), c.replyLine().get(0));
        if (reply == null) {
            return null;
        }
        int materialNow = Tactics.materialBalance(c.after(), mover);
        int materialLater = Tactics.materialAfterLine(c.after(), c.replyLine(), 4, mover);
        boolean losesMaterial = materialLater <= materialNow - 2;

        Tactics.Capture hanging = Tactics.worstHangingPiece(c.after());
        if (hanging != null && hanging.move().equals(reply)) {
            return "Hangs the " + Tactics.pieceName(hanging.victim()) + " on " + Tactics.squareName(hanging.square()) + ".";
        }

        Board afterReply = c.after().clone();
        afterReply.doMove(reply);
        List<Square> forked = Tactics.forkTargets(afterReply, reply.getTo());
        if (forked.size() >= 2 && (losesMaterial || forked.stream().anyMatch(sq -> isKing(afterReply, sq)))) {
            Piece forker = afterReply.getPiece(reply.getTo());
            return "Allows a " + (forker.getPieceType() == PieceType.KNIGHT ? "knight fork" : "fork") + " with " + replySan
                + ", hitting the " + describeTargets(afterReply, forked) + ".";
        }

        if (losesMaterial) {
            return "Loses material after " + replySan + ".";
        }
        return null;
    }

    /** The best move would have won material, but the played move does not. */
    private static String missedGain(Context c, String bestSan) {
        if (bestSan == null || c.bestLine().isEmpty()) {
            return null;
        }
        Side mover = c.before().getSideToMove();
        // Compare like with like: the best line versus the played move followed by the opponent's best reply.
        int withBest = Tactics.materialAfterLine(c.before(), c.bestLine(), 5, mover);
        int withPlayed = Tactics.materialAfterLine(c.after(), c.replyLine(), 4, mover);
        if (withBest < withPlayed + 2) {
            return null;
        }
        Tactics.Capture capture = Tactics.bestCapture(c.before());
        Move best = Tactics.parseLegal(c.before(), c.bestLine().get(0));
        if (capture != null && best != null && capture.move().equals(best)) {
            return "Misses " + bestSan + ", winning the " + Tactics.pieceName(capture.victim()) + ".";
        }
        return "Misses " + bestSan + ", which wins material.";
    }

    private static boolean isKing(Board board, Square square) {
        Piece piece = board.getPiece(square);
        return piece != null && piece.getPieceType() == PieceType.KING;
    }

    private static String describeTargets(Board board, List<Square> squares) {
        List<String> names = squares.stream()
            .sorted((a, b) -> Integer.compare(
                Tactics.value(board.getPiece(b).getPieceType()), Tactics.value(board.getPiece(a).getPieceType())))
            .limit(2)
            .map(sq -> Tactics.pieceName(board.getPiece(sq).getPieceType()))
            .toList();
        return String.join(" and ", names);
    }
}
