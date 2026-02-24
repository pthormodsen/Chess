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

public class StockfishClient implements Closeable {

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

    //Skill level (0-20)
    public synchronized void setSkillLevel(int level) throws IOException {
        int clamped = Math.max(0, Math.min(20, level));
        sendCommand("setoption name Skill Level value " + clamped);
    }


    public synchronized String requestBestMove(String moves, Duration thinkTime) throws IOException {
        String sanitizedMoves = moves == null ? "" : moves.trim();
        if (sanitizedMoves.isEmpty()) {
            sendCommand("position startpos");
        } else {
            sendCommand("position startpos moves " + sanitizedMoves);
        }

        long millis = Math.max(thinkTime.toMillis(), 1);
        sendCommand("go movetime " + millis);

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("bestmove")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    return parts[1];
                }
                break;
            }
        }
        return null;
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
