package training;

import java.util.List;

/**
 * One tactics puzzle in the Lichess format: {@code fen} is the position <em>before</em> the opponent's move,
 * {@code moves} (UCI) starts with that move and then alternates between the solver and the opponent.
 * The solver plays the side to move after the first move, and every solver move must match.
 */
public record Puzzle(String id, String fen, List<String> moves, int rating, List<String> themes) {
    public Puzzle {
        moves = List.copyOf(moves);
        themes = List.copyOf(themes);
    }

    /** "white" or "black": the side the solver plays (the opposite of the side to move in {@link #fen}). */
    public String solverColor() {
        String[] parts = fen.split(" ");
        return parts.length > 1 && parts[1].equals("w") ? "black" : "white";
    }
}
