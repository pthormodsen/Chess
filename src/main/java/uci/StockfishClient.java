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

        initialize();
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
        writer.write(command);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            sendCommand("quit");
        } finally {
            reader.close();
            writer.close();
            process.destroy();
        }
    }
}
