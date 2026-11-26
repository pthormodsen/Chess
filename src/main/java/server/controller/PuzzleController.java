package server.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import training.Puzzle;
import training.PuzzleBook;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Puzzles are stateless and need no game session: the server only picks them, the browser checks the moves
 * (the solution is part of the response). Your puzzle rating lives in the browser too.
 */
@RestController
@RequestMapping("/api/puzzles")
public class PuzzleController {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9]{1,16}");
    private static final Pattern THEME = Pattern.compile("[A-Za-z0-9]{1,40}");
    private static final int MAX_EXCLUDED = 200;

    private final PuzzleBook book;

    public PuzzleController() {
        this(PuzzleBook.standard());
    }

    PuzzleController(PuzzleBook book) {
        this.book = book;
    }

    public record PuzzleView(String id, String fen, List<String> moves, int rating, List<String> themes, String color) {
        static PuzzleView of(Puzzle puzzle) {
            return new PuzzleView(puzzle.id(), puzzle.fen(), puzzle.moves(), puzzle.rating(), puzzle.themes(), puzzle.solverColor());
        }
    }

    public record ThemeView(String name, int count) {}

    /** A random puzzle near {@code rating}; {@code exclude} is a comma-separated list of recently played ids. */
    @GetMapping("/next")
    public PuzzleView next(@RequestParam(defaultValue = "1500") int rating,
                           @RequestParam(required = false) String theme,
                           @RequestParam(required = false) String exclude) {
        String wanted = theme == null || theme.isBlank() ? null : theme.trim();
        if (wanted != null && !THEME.matcher(wanted).matches()) {
            throw new IllegalArgumentException("Unknown puzzle theme.");
        }
        Set<String> excluded = exclude == null ? Set.of() : Arrays.stream(exclude.split(","))
            .map(String::trim)
            .filter(id -> ID.matcher(id).matches())
            .limit(MAX_EXCLUDED)
            .collect(Collectors.toSet());
        int target = Math.max(0, Math.min(3500, rating));
        return book.pick(target, wanted, excluded, ThreadLocalRandom.current())
            .map(PuzzleView::of)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No puzzles found for that theme."));
    }

    @GetMapping("/{id}")
    public PuzzleView byId(@PathVariable String id) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid puzzle id.");
        }
        return book.byId(id)
            .map(PuzzleView::of)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Puzzle not found."));
    }

    /** Themes present in the collection with their counts, most common first. */
    @GetMapping("/themes")
    public List<ThemeView> themes() {
        return book.themeCounts().entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> new ThemeView(entry.getKey(), entry.getValue()))
            .toList();
    }
}
