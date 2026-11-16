package server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import support.FakeUciEngine;
import uci.StockfishClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EnginePoolTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesWarmEnginesAndCapsConcurrency() throws Exception {
        Path engine = FakeUciEngine.write(tempDir, "cp 20", "e2e4");
        try (EnginePool pool = new EnginePool(engine, 1, 1, 2, 16, 60_000)) {
            List<StockfishClient> used = new ArrayList<>();
            pool.withEngine(1_000, client -> used.add(client));
            pool.withEngine(1_000, client -> used.add(client));
            assertSame(used.get(0), used.get(1), "the warm engine is reused");
            assertEquals(1, pool.idleCount());

            // Hold the only engine in another thread: a second caller gets a clean "busy" error, not a hang.
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try {
                    pool.withEngine(1_000, client -> {
                        holding.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Exception ignored) {
                }
            });
            holder.start();
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            assertThrows(WebGameService.CapacityException.class, () -> pool.withEngine(100, client -> null));
            release.countDown();
            holder.join(5_000);

            assertNotNull(pool.withEngine(1_000, client -> client.analyzeFen(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 4)), "usable again once released");
        }
    }

    @Test
    void idleEnginesAreClosedAfterTheTimeout() throws Exception {
        Path engine = FakeUciEngine.write(tempDir, "cp 20", "e2e4");
        try (EnginePool pool = new EnginePool(engine, 2, 2, 2, 16, 1_000)) {
            pool.withEngine(1_000, client -> null);
            assertEquals(1, pool.idleCount());
            long deadline = System.currentTimeMillis() + 5_000;
            while (pool.idleCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(0, pool.idleCount(), "a quiet server holds no engines");
        }
    }
}
