package training;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The bundled puzzle collection: {@code training/puzzles.tsv}, a rating-stratified sample of the Lichess puzzle
 * database (CC0), built by {@code scripts/build-puzzles.py}. Puzzles are kept sorted by rating so a pick near a
 * target rating is a binary search plus a scan of that band.
 */
public final class PuzzleBook {

    private static final String RESOURCE = "/training/puzzles.tsv";
    private static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    /** Rating windows tried in order until one has an unseen puzzle (the last one takes anything). */
    private static final int[] WINDOWS = {75, 150, 300, 600, Integer.MAX_VALUE / 2};

    private final List<Puzzle> byRating;
    private final Map<String, Puzzle> byId;
    private final Map<String, Integer> themeCounts;

    PuzzleBook(Collection<Puzzle> puzzles) {
        List<Puzzle> sorted = new ArrayList<>(puzzles);
        sorted.sort(Comparator.comparingInt(Puzzle::rating).thenComparing(Puzzle::id));
        this.byRating = List.copyOf(sorted);
        Map<String, Puzzle> ids = new HashMap<>();
        Map<String, Integer> themes = new TreeMap<>();
        for (Puzzle puzzle : sorted) {
            ids.put(puzzle.id(), puzzle);
            for (String theme : puzzle.themes()) {
                themes.merge(theme, 1, Integer::sum);
            }
        }
        this.byId = Map.copyOf(ids);
        this.themeCounts = Collections.unmodifiableMap(themes);
    }

    private static final class Holder {
        static final PuzzleBook INSTANCE = load();
    }

    /** The bundled collection, loaded once on first use (empty if the resource is missing). */
    public static PuzzleBook standard() {
        return Holder.INSTANCE;
    }

    public int size() {
        return byRating.size();
    }

    /** Every puzzle, by rating. */
    List<Puzzle> all() {
        return byRating;
    }

    public Optional<Puzzle> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Theme name → number of puzzles with it, alphabetically. */
    public Map<String, Integer> themeCounts() {
        return themeCounts;
    }

    /**
     * A random puzzle close to {@code rating}, with {@code theme} if given, avoiding {@code exclude} where possible.
     * The rating window widens until something matches; empty only if no puzzle has the theme at all.
     */
    public Optional<Puzzle> pick(int rating, String theme, Set<String> exclude, Random random) {
        for (int window : WINDOWS) {
            List<Puzzle> candidates = candidates(rating - window, rating + window, theme, exclude);
            if (!candidates.isEmpty()) {
                return Optional.of(candidates.get(random.nextInt(candidates.size())));
            }
        }
        // Everything with this theme has been seen recently: repeat rather than come back empty.
        if (!exclude.isEmpty()) {
            return pick(rating, theme, Set.of(), random);
        }
        return Optional.empty();
    }

    private List<Puzzle> candidates(int low, int high, String theme, Set<String> exclude) {
        List<Puzzle> result = new ArrayList<>();
        for (int i = firstAtLeast(low); i < byRating.size() && byRating.get(i).rating() <= high; i++) {
            Puzzle puzzle = byRating.get(i);
            if ((theme == null || puzzle.themes().contains(theme)) && !exclude.contains(puzzle.id())) {
                result.add(puzzle);
            }
        }
        return result;
    }

    private int firstAtLeast(int rating) {
        int low = 0;
        int high = byRating.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (byRating.get(mid).rating() < rating) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    static PuzzleBook load() {
        List<Puzzle> puzzles = new ArrayList<>();
        try (InputStream in = PuzzleBook.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return new PuzzleBook(puzzles);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null) {
                Puzzle puzzle = parse(line);
                if (puzzle != null) {
                    puzzles.add(puzzle);
                }
            }
        } catch (IOException e) {
            // A missing collection only disables puzzles.
        }
        return new PuzzleBook(puzzles);
    }

    /** One TSV line (id, fen, moves, rating, themes), or null if it is malformed. */
    static Puzzle parse(String line) {
        String[] columns = line.split("\t");
        if (columns.length < 4) {
            return null;
        }
        List<String> moves = Arrays.asList(columns[2].trim().split(" "));
        if (moves.size() < 2 || !moves.stream().allMatch(move -> UCI.matcher(move).matches())) {
            return null;
        }
        if (columns[1].trim().split(" ").length < 4) {
            return null;
        }
        try {
            int rating = Integer.parseInt(columns[3].trim());
            List<String> themes = columns.length > 4 && !columns[4].isBlank()
                ? Arrays.asList(columns[4].trim().split(" "))
                : List.of();
            return new Puzzle(columns[0].trim(), columns[1].trim(), moves, rating, themes);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
