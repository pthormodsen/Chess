package server.service;

import uci.StockfishClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A few warm Stockfish processes for short, independent searches (review "explore" mode), so a request
 * does not pay for starting a process and loading the networks.
 *
 * <p>At most {@code maxEngines} run at once (extra callers wait briefly, then get a busy error). Only
 * {@code maxIdle} are kept between requests, and those are closed after {@code idleTimeoutMillis}, so a quiet
 * server holds no engine memory.
 */
final class EnginePool implements AutoCloseable {

    /** One unit of engine work. */
    interface Work<T> {
        T run(StockfishClient client) throws IOException;
    }

    private final Path enginePath;
    private final int maxEngines;
    private final int maxIdle;
    private final int multiPv;
    private final int hashMb;
    private final long idleTimeoutMillis;
    private final Deque<Idle> idle = new ArrayDeque<>();
    private final ScheduledExecutorService reaper;
    private int inUse;
    private boolean closed;

    private record Idle(StockfishClient client, long since) {}

    EnginePool(Path enginePath, int maxEngines, int maxIdle, int multiPv, int hashMb, long idleTimeoutMillis) {
        this.enginePath = enginePath;
        this.maxEngines = Math.max(1, maxEngines);
        this.maxIdle = Math.max(0, maxIdle);
        this.multiPv = multiPv;
        this.hashMb = hashMb;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "engine-pool-reaper");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(1_000, idleTimeoutMillis / 2);
        reaper.scheduleAtFixedRate(this::closeExpired, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs {@code work} on a warm engine. The engine is reset first ({@code ucinewgame}), so every search
     * starts from the same state and the result only depends on the position.
     *
     * @throws WebGameService.CapacityException when all engines stay busy for {@code waitMillis}
     */
    <T> T withEngine(long waitMillis, Work<T> work) throws IOException, InterruptedException {
        StockfishClient client = borrow(waitMillis);
        boolean healthy = false;
        try {
            client.newGame();
            T result = work.run(client);
            healthy = true;
            return result;
        } finally {
            release(client, healthy);
        }
    }

    private StockfishClient borrow(long waitMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + waitMillis;
        synchronized (this) {
            while (true) {
                if (closed) {
                    throw new WebGameService.CapacityException("The server is shutting down.");
                }
                Idle warm = idle.pollFirst();
                if (warm != null) {
                    inUse++;
                    return warm.client();
                }
                if (inUse < maxEngines) {
                    inUse++;
                    break;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new WebGameService.CapacityException("The engine is busy right now. Try the move again in a moment.");
                }
                wait(left);
            }
        }
        // Start a new process outside the lock.
        try {
            StockfishClient client = new StockfishClient(enginePath);
            client.configureForAnalysis(multiPv, hashMb);
            return client;
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                inUse--;
                notifyAll();
            }
            throw e;
        }
    }

    private void release(StockfishClient client, boolean healthy) {
        StockfishClient toClose = null;
        synchronized (this) {
            inUse--;
            if (healthy && !closed && idle.size() < maxIdle) {
                idle.addFirst(new Idle(client, System.currentTimeMillis()));
            } else {
                toClose = client;
            }
            notifyAll();
        }
        closeQuietly(toClose);
    }

    private void closeExpired() {
        long now = System.currentTimeMillis();
        java.util.List<StockfishClient> expired = new java.util.ArrayList<>();
        synchronized (this) {
            idle.removeIf(entry -> {
                if (now - entry.since() >= idleTimeoutMillis) {
                    expired.add(entry.client());
                    return true;
                }
                return false;
            });
        }
        expired.forEach(EnginePool::closeQuietly);
    }

    synchronized int idleCount() {
        return idle.size();
    }

    @Override
    public void close() {
        java.util.List<StockfishClient> all = new java.util.ArrayList<>();
        synchronized (this) {
            closed = true;
            idle.forEach(entry -> all.add(entry.client()));
            idle.clear();
            notifyAll();
        }
        reaper.shutdownNow();
        all.forEach(EnginePool::closeQuietly);
    }

    private static void closeQuietly(StockfishClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException | RuntimeException ignored) {
            // The process is gone either way.
        }
    }
}
