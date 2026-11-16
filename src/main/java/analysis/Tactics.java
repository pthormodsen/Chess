package analysis;

import com.github.bhlangonijr.chesslib.Bitboard;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small static tactics toolkit on top of chesslib, used to explain and classify moves:
 * SAN conversion, material counting, static exchange evaluation (SEE), hanging pieces and forks.
 * All methods leave the given board unchanged.
 */
public final class Tactics {

    private Tactics() {}

    /** One half-move of a rendered line: UCI, SAN and the position after it. */
    public record LinePly(String uci, String san, String fen) {}

    /** Renders a UCI line from {@code fen} as SAN plus the FEN after each move; stops at the first illegal move. */
    public static List<LinePly> renderLine(String fen, List<String> uciMoves) {
        List<LinePly> plies = new ArrayList<>();
        if (fen == null || uciMoves == null || uciMoves.isEmpty()) {
            return plies;
        }
        try {
            Board board = new Board();
            board.loadFromFen(fen);
            List<String> san = toSan(board, uciMoves);
            for (int i = 0; i < san.size(); i++) {
                Move move = parseLegal(board, uciMoves.get(i));
                if (move == null) {
                    break;
                }
                board.doMove(move);
                plies.add(new LinePly(uciMoves.get(i), san.get(i), board.getFen()));
            }
        } catch (RuntimeException e) {
            // A malformed line renders as far as it is valid.
        }
        return plies;
    }

    /** A capture the side to move can make, and what it nets after the best recapture sequence. */
    public record Capture(Move move, Square square, PieceType victim, int gain) {}

    public static int value(PieceType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case PAWN -> 1;
            case KNIGHT, BISHOP -> 3;
            case ROOK -> 5;
            case QUEEN -> 9;
            case KING -> 100;
            default -> 0;
        };
    }

    public static String pieceName(PieceType type) {
        if (type == null || type == PieceType.NONE) {
            return "piece";
        }
        return type.name().toLowerCase(Locale.ROOT);
    }

    public static String squareName(Square square) {
        return square.value().toLowerCase(Locale.ROOT);
    }

    /** Parses a UCI move for the side to move; null if it is not legal in this position. */
    public static Move parseLegal(Board board, String uci) {
        if (uci == null || uci.length() < 4) {
            return null;
        }
        try {
            Move move = new Move(uci, board.getSideToMove());
            for (Move legal : board.legalMoves()) {
                if (legal.equals(move)) {
                    return legal;
                }
            }
        } catch (RuntimeException ignored) {
            // malformed square names
        }
        return null;
    }

    /** SAN for a sequence of UCI moves from this position, stopping at the first illegal move. */
    public static List<String> toSan(Board board, List<String> uciMoves) {
        List<String> san = new ArrayList<>();
        if (uciMoves == null || uciMoves.isEmpty()) {
            return san;
        }
        Board copy = board.clone();
        MoveList list = new MoveList(board.getFen());
        for (String uci : uciMoves) {
            Move move = parseLegal(copy, uci);
            if (move == null) {
                break;
            }
            list.add(move);
            copy.doMove(move);
        }
        if (list.isEmpty()) {
            return san;
        }
        try {
            for (String value : list.toSanArray()) {
                san.add(value);
            }
        } catch (Exception e) {
            san.clear();
        }
        return san;
    }

    public static String toSan(Board board, String uci) {
        List<String> san = toSan(board, uci == null ? List.of() : List.of(uci));
        return san.isEmpty() ? null : san.get(0);
    }

    /** Material of {@code side} minus the opponent's, in pawns (kings excluded). */
    public static int materialBalance(Board board, Side side) {
        int total = 0;
        for (Square square : Square.values()) {
            if (square == Square.NONE) {
                continue;
            }
            Piece piece = board.getPiece(square);
            if (piece == null || piece == Piece.NONE || piece.getPieceType() == PieceType.KING) {
                continue;
            }
            int value = value(piece.getPieceType());
            total += piece.getPieceSide() == side ? value : -value;
        }
        return total;
    }

    /**
     * Material for {@code side} after following {@code line} (UCI) for at least {@code plies} half-moves,
     * extended while the last move was a capture so exchanges are not cut in half. Stops at illegal moves.
     */
    public static int materialAfterLine(Board board, List<String> line, int plies, Side side) {
        Board copy = board.clone();
        int played = 0;
        boolean lastWasCapture = false;
        for (String uci : line == null ? List.<String>of() : line) {
            if (played >= plies && !lastWasCapture) {
                break;
            }
            if (played >= plies + 4) {
                break;
            }
            Move move = parseLegal(copy, uci);
            if (move == null) {
                break;
            }
            lastWasCapture = isCapture(copy, move);
            copy.doMove(move);
            played++;
        }
        return materialBalance(copy, side);
    }

    public static boolean isCapture(Board board, Move move) {
        Piece target = board.getPiece(move.getTo());
        if (target != null && target != Piece.NONE) {
            return true;
        }
        Piece mover = board.getPiece(move.getFrom());
        return mover != null && mover.getPieceType() == PieceType.PAWN
            && move.getFrom().getFile() != move.getTo().getFile();
    }

    /** Value of what {@code move} captures (en passant counts as a pawn). */
    public static int capturedValue(Board board, Move move) {
        Piece target = board.getPiece(move.getTo());
        if (target != null && target != Piece.NONE) {
            return value(target.getPieceType());
        }
        return isCapture(board, move) ? 1 : 0;
    }

    /**
     * Static exchange evaluation: what {@code side} wins by starting a capture sequence on {@code target},
     * always recapturing with the least valuable piece and stopping when that stops paying. Never negative,
     * because the side may simply not capture. Pins are ignored (the classic SEE approximation).
     */
    public static int see(Board board, Square target, Side side) {
        Piece victim = board.getPiece(target);
        if (victim == null || victim == Piece.NONE) {
            return 0;
        }
        long occupied = board.getBitboard();
        int[] gain = new int[40];
        int depth = 0;
        gain[0] = value(victim.getPieceType());
        Side toMove = side;
        Square from = leastValuableAttacker(board, target, toMove, occupied);
        if (from == null) {
            return 0;
        }
        while (from != null && depth < gain.length - 1) {
            depth++;
            int attackerValue = value(board.getPiece(from).getPieceType());
            gain[depth] = attackerValue - gain[depth - 1];
            occupied &= ~from.getBitboard();
            toMove = toMove.flip();
            from = leastValuableAttacker(board, target, toMove, occupied);
        }
        while (--depth > 0) {
            gain[depth - 1] = -Math.max(-gain[depth - 1], gain[depth]);
        }
        return Math.max(0, gain[0]);
    }

    private static Square leastValuableAttacker(Board board, Square target, Side side, long occupied) {
        long attackers = board.squareAttackedBy(target, side, occupied) & occupied & board.getBitboard(side);
        Square best = null;
        int bestValue = Integer.MAX_VALUE;
        for (Square square : Bitboard.bbToSquareList(attackers)) {
            int value = value(board.getPiece(square).getPieceType());
            if (value < bestValue) {
                bestValue = value;
                best = square;
            }
        }
        return best;
    }

    /**
     * The most profitable legal capture for the side to move: victim value minus what the opponent then wins
     * back on that square. Null when no capture gains anything.
     */
    public static Capture bestCapture(Board board) {
        Side side = board.getSideToMove();
        Capture best = null;
        for (Move move : board.legalMoves()) {
            if (!isCapture(board, move)) {
                continue;
            }
            Piece victim = board.getPiece(move.getTo());
            PieceType victimType = victim == null || victim == Piece.NONE ? PieceType.PAWN : victim.getPieceType();
            int won = capturedValue(board, move);
            Board after = board.clone();
            after.doMove(move);
            int lost = see(after, move.getTo(), side.flip());
            int gain = won - lost;
            if (gain > 0 && (best == null || gain > best.gain)) {
                best = new Capture(move, move.getTo(), victimType, gain);
            }
        }
        return best;
    }

    /** True when the side to move is attacking the opponent's king. */
    public static boolean givesCheck(Board boardAfterMove) {
        return boardAfterMove.isKingAttacked();
    }

    /**
     * Pieces of {@code victims} attacked by the piece standing on {@code from}: the king, or pieces worth more
     * than the attacker, or undefended pieces. Two or more of those is a fork.
     */
    public static List<Square> forkTargets(Board board, Square from) {
        Piece attacker = board.getPiece(from);
        List<Square> targets = new ArrayList<>();
        if (attacker == null || attacker == Piece.NONE) {
            return targets;
        }
        Side attackerSide = attacker.getPieceSide();
        Side victims = attackerSide.flip();
        int attackerValue = value(attacker.getPieceType());
        long occupied = board.getBitboard();
        for (Square square : Bitboard.bbToSquareList(board.getBitboard(victims))) {
            long attackersOfSquare = board.squareAttackedBy(square, attackerSide, occupied);
            if ((attackersOfSquare & from.getBitboard()) == 0) {
                continue;
            }
            PieceType type = board.getPiece(square).getPieceType();
            if (type == PieceType.PAWN) {
                continue;
            }
            boolean defended = board.squareAttackedBy(square, victims, occupied & ~square.getBitboard()) != 0;
            if (type == PieceType.KING || value(type) > attackerValue || !defended) {
                targets.add(square);
            }
        }
        return targets;
    }

    /**
     * The opponent's piece (not a pawn) that the side to move can win most profitably right now,
     * i.e. a piece the opponent left hanging. Null when nothing worth at least two pawns is en prise.
     */
    public static Capture worstHangingPiece(Board board) {
        Side side = board.getSideToMove().flip();
        Capture best = null;
        for (Move move : board.legalMoves()) {
            Piece victim = board.getPiece(move.getTo());
            if (victim == null || victim == Piece.NONE || victim.getPieceType() == PieceType.PAWN) {
                continue;
            }
            Board after = board.clone();
            after.doMove(move);
            int gain = value(victim.getPieceType()) - see(after, move.getTo(), side);
            if (gain >= 2 && (best == null || gain > best.gain)) {
                best = new Capture(move, move.getTo(), victim.getPieceType(), gain);
            }
        }
        return best;
    }
}
