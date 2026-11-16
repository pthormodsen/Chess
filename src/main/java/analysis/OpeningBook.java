package analysis;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Opening theory used for "Book" moves and the opening name of a game.
 *
 * <p>Data: {@code analysis/openings.tsv} from the lichess-org/chess-openings project (public domain, CC0).
 * Every position reached along a catalogued line counts as book; positions that end a named line also carry
 * that name. Positions are keyed without move counters, so transpositions are recognised.
 */
public final class OpeningBook {

    public record Opening(String eco, String name) {}

    private static final String RESOURCE = "/analysis/openings.tsv";

    private final Set<String> bookPositions;
    private final Map<String, Opening> namedPositions;

    OpeningBook(Set<String> bookPositions, Map<String, Opening> namedPositions) {
        this.bookPositions = bookPositions;
        this.namedPositions = namedPositions;
    }

    private static final class Holder {
        static final OpeningBook INSTANCE = load();
    }

    /** The bundled book, loaded once on first use (an empty book if the resource is missing). */
    public static OpeningBook standard() {
        return Holder.INSTANCE;
    }

    public boolean isBook(Board board) {
        return bookPositions.contains(key(board));
    }

    public Opening opening(Board board) {
        return namedPositions.get(key(board));
    }

    public int size() {
        return namedPositions.size();
    }

    static String key(Board board) {
        String[] parts = board.getFen().split(" ");
        // Pieces, side to move, castling rights. The en-passant field is left out: chesslib only sets it
        // when a capture is possible, which already shows up as a different position for practical purposes.
        return parts.length >= 3 ? parts[0] + " " + parts[1] + " " + parts[2] : board.getFen();
    }

    private static OpeningBook load() {
        Set<String> book = new HashSet<>();
        Map<String, Opening> named = new HashMap<>();
        try (InputStream in = OpeningBook.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return new OpeningBook(book, named);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] columns = line.split("\t");
                if (columns.length < 3) {
                    continue;
                }
                addLine(book, named, new Opening(columns[0].trim(), columns[1].trim()), columns[2].trim());
            }
        } catch (IOException e) {
            // A missing book only disables "Book" tags and opening names.
        }
        return new OpeningBook(book, named);
    }

    private static void addLine(Set<String> book, Map<String, Opening> named, Opening opening, String pgn) {
        try {
            MoveList moves = new MoveList();
            moves.loadFromSan(pgn.replaceAll("\\d+\\.", " ").trim());
            Board board = new Board();
            for (Move move : moves) {
                board.doMove(move);
                book.add(key(board));
            }
            if (!moves.isEmpty()) {
                // The most specific (longest) name wins if two lines reach the same position.
                named.merge(key(board), opening, (old, candidate) ->
                    candidate.name().length() > old.name().length() ? candidate : old);
            }
        } catch (Exception ignored) {
            // Skip a malformed line rather than losing the whole book.
        }
    }
}
