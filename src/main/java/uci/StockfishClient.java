package uci;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal UCI bridge for communicating with a Stockfish binary.
 */
public class StockfishClient implements Closeable {

    public static final class AnalysisResult {
        public final String bestMove;
        public final double scoreCp;
        public final Integer mate;
        public final List<String> principalVariation;

        public AnalysisResult(String bestMove, double scoreCp, Integer mate, List<String> principalVariation) {
            this.bestMove = bestMove;
            this.scoreCp = scoreCp;
            this.mate = mate;
            this.principalVariation = principalVariation == null ? List.of() : List.copyOf(principalVariation);
        }
    }

    /**
     * One principal variation from a fixed-depth search, scored from the side to move's perspective.
     * Exactly one of {@code scoreCp} / {@code mate} is meaningful: {@code mate} is non-null for a forced mate.
     */
    public static final class Line {
        public final int multipv;
        public final int depth;
        public final int scoreCp;
        public final Integer mate;
        public final List<String> pv;

        public Line(int multipv, int depth, int scoreCp, Integer mate, List<String> pv) {
            this.multipv = multipv;
            this.depth = depth;
            this.scoreCp = scoreCp;
            this.mate = mate;
            this.pv = pv == null ? List.of() : List.copyOf(pv);
        }

        public String firstMove() {
            return pv.isEmpty() ? null : pv.get(0);
        }
    }

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    public StockfishClient(Path enginePath) throws IOException {
        if (enginePath == null || !Files.exists(enginePath)) {
            throw new IllegalArgumentException("Stockfish binary not found at " + enginePath);
        }

        process = new ProcessBuilder(enginePath.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();

        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        try {
            initialize();
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private void initialize() throws IOException {
        sendCommand("uci");
        waitForKeyword("uciok");
        sendCommand("isready");
        waitForKeyword("readyok");
        sendCommand("ucinewgame");
    }

    public synchronized void newGame() throws IOException {
        sendCommand("ucinewgame");
        sendCommand("isready");
        waitForKeyword("readyok");
    }

    public synchronized String requestBestMove(String moves, Duration thinkTime) throws IOException {
        AnalysisResult result = analyzePosition(moves, thinkTime);
        return result == null ? null : result.bestMove;
    }

    public synchronized AnalysisResult analyzePosition(String moves, Duration thinkTime) throws IOException {
        String sanitizedMoves = moves == null ? "" : moves.trim();
        if (sanitizedMoves.isEmpty()) {
            sendCommand("position startpos");
        } else {
            sendCommand("position startpos moves " + sanitizedMoves);
        }

        long millis = Math.max(thinkTime.toMillis(), 1);
        sendCommand("go movetime " + millis);

        double lastScore = 0;
        Integer mate = null;
        String bestMove = null;
        List<String> pvMoves = Collections.emptyList();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("info ")) {
                if (line.contains(" score mate ")) {
                    String value = extractAfter(line, "score mate ");
                    try {
                        mate = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (line.contains(" score cp ")) {
                    String value = extractAfter(line, "score cp ");
                    try {
                        lastScore = Double.parseDouble(value);
                        mate = null;
                    } catch (NumberFormatException ignored) {
                    }
                }
                int pvIndex = line.indexOf(" pv ");
                if (pvIndex >= 0 && pvIndex + 4 < line.length()) {
                    String pvPart = line.substring(pvIndex + 4).trim();
                    pvMoves = parsePvMoves(pvPart);
                }
            } else if (line.startsWith("bestmove")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    bestMove = parts[1];
                }
                break;
            }
        }
        return new AnalysisResult(bestMove, mate != null ? (mate > 0 ? 10000 : -10000) : lastScore, mate, pvMoves);
    }

    /**
     * Reproducible analysis settings: one search thread and a fixed hash, so a fixed-depth search of the same
     * sequence of positions (after {@link #newGame()}) always produces the same result.
     */
    public synchronized void configureForAnalysis(int multiPv, int hashMb) throws IOException {
        sendCommand("setoption name Threads value 1");
        sendCommand("setoption name Hash value " + Math.max(1, hashMb));
        sendCommand("setoption name MultiPV value " + Math.max(1, multiPv));
        sendCommand("setoption name UCI_LimitStrength value false");
        sendCommand("isready");
        waitForKeyword("readyok");
    }

    /**
     * Searches the position after {@code moves} to a fixed depth and returns the best lines ordered by
     * multipv rank (best first). Returns an empty list when the side to move has no legal moves.
     */
    public synchronized List<Line> analyzeLines(String moves, int depth) throws IOException {
        String sanitizedMoves = moves == null ? "" : moves.trim();
        return search(sanitizedMoves.isEmpty() ? "position startpos" : "position startpos moves " + sanitizedMoves, depth);
    }

    /** Like {@link #analyzeLines} for an arbitrary position given as FEN (no move history, so no repetition draws). */
    public synchronized List<Line> analyzeFen(String fen, int depth) throws IOException {
        if (fen == null || fen.isBlank()) {
            throw new IllegalArgumentException("FEN is required");
        }
        return search("position fen " + fen.trim(), depth);
    }

    private List<Line> search(String positionCommand, int depth) throws IOException {
        sendCommand(positionCommand);
        sendCommand("go depth " + Math.max(1, depth));

        java.util.TreeMap<Integer, Line> lines = new java.util.TreeMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("bestmove")) {
                break;
            }
            Line parsed = parseInfoLine(line);
            if (parsed != null) {
                lines.put(parsed.multipv, parsed); // later info lines are deeper
            }
        }
        if (line == null) {
            throw new IOException("Engine closed during search");
        }
        List<Line> result = new ArrayList<>();
        for (Line value : lines.values()) {
            if (value.mate != null && value.mate == 0) {
                continue; // "mate 0": no legal moves, nothing to report
            }
            if (!value.pv.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    /** Parses a UCI "info ... score ... pv ..." line; null for anything else, including bound (partial) scores. */
    static Line parseInfoLine(String line) {
        if (!line.startsWith("info ") || !line.contains(" score ") || line.contains(" lowerbound") || line.contains(" upperbound")) {
            return null;
        }
        String[] tokens = line.trim().split("\\s+");
        int multipv = 1;
        int depth = 0;
        Integer cp = null;
        Integer mate = null;
        List<String> pv = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            try {
                switch (token) {
                    case "multipv" -> multipv = Integer.parseInt(tokens[++i]);
                    case "depth" -> depth = Integer.parseInt(tokens[++i]);
                    case "score" -> {
                        String kind = tokens[++i];
                        int value = Integer.parseInt(tokens[++i]);
                        if ("mate".equals(kind)) {
                            mate = value;
                        } else if ("cp".equals(kind)) {
                            cp = value;
                        }
                    }
                    case "pv" -> {
                        for (int j = i + 1; j < tokens.length; j++) {
                            if (tokens[j].length() >= 4) {
                                pv.add(tokens[j]);
                            }
                        }
                        i = tokens.length;
                    }
                    default -> { }
                }
            } catch (RuntimeException malformed) {
                return null;
            }
        }
        if (cp == null && mate == null) {
            return null;
        }
        return new Line(multipv, depth, cp == null ? 0 : cp, mate, pv);
    }

    public synchronized void setSkillLevel(int level) throws IOException {
        int clamped = Math.max(0, Math.min(20, level));
        sendCommand("setoption name Skill Level value " + clamped);
    }

    public synchronized void setLimitStrength(boolean enabled) throws IOException {
        sendCommand("setoption name UCI_LimitStrength value " + (enabled ? "true" : "false"));
    }

    public synchronized void setTargetElo(int elo) throws IOException {
        int clamped = Math.max(300, Math.min(3500, elo));
        sendCommand("setoption name UCI_Elo value " + clamped);
    }

    private String extractAfter(String line, String token) {
        int idx = line.indexOf(token);
        if (idx < 0) return "";
        int start = idx + token.length();
        int end = start;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        return line.substring(start, end);
    }

    private List<String> parsePvMoves(String pvPart) {
        if (pvPart.isEmpty()) {
            return Collections.emptyList();
        }
        String[] tokens = pvPart.split("\\s+");
        List<String> moves = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token.length() < 4) {
                continue;
            }
            moves.add(token);
        }
        return moves;
    }

    private void waitForKeyword(String keyword) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(keyword)) {
                return;
            }
        }
        throw new IOException("Engine closed while waiting for " + keyword);
    }

    private void sendCommand(String command) throws IOException {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("UCI command must be a single line");
        }
        writer.write(command);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            sendCommand("quit");
        } finally {
            try {
                reader.close();
                writer.close();
            } finally {
                process.destroy();
                try {
                    if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
