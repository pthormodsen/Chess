package server.service;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.service.WebGameService.CapacityException;
import server.service.WebGameService.ConflictException;
import server.service.WebGameService.GameSettingsRequest;
import server.service.WebGameService.GameState;
import server.service.WebGameService.InvalidSessionException;
import server.service.WebGameService.MoveRequest;
import server.service.WebGameService.NewGameRequest;
import server.service.WebGameService.PgnImportRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WebGameServiceTest {
    private static final String S1 = "11111111-aaaa-bbbb-cccc-000000000001";
    private static final String S2 = "11111111-aaaa-bbbb-cccc-000000000002";

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @TempDir
    Path tempDir;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private WebGameService service;

    @BeforeEach
    void requireNoRealEngineOverride() {
        // Board prefers the STOCKFISH_PATH env var; these tests need the fake engine below.
        assumeTrue(System.getenv("STOCKFISH_PATH") == null, "STOCKFISH_PATH is set; skipping fake-engine tests");
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        System.clearProperty("stockfish.path");
    }

    @Test
    void rejectsMissingOrMalformedSessionIds() {
        service = newService(null, 0);

        assertThrows(InvalidSessionException.class, () -> service.state(null));
        assertThrows(InvalidSessionException.class, () -> service.state(""));
        assertThrows(InvalidSessionException.class, () -> service.state("short"));
        assertThrows(InvalidSessionException.class, () -> service.state("bad id with spaces!"));
        assertThrows(InvalidSessionException.class, () -> service.state("x".repeat(65)));
        assertEquals(0, service.activeSessionCount());
        assertNotNull(service.state(S1));
    }

    @Test
    void sessionsAreIsolated() {
        service = newService(null, 0);

        service.move(S1, new MoveRequest(4, 6, 4, 4));

        assertEquals(1, service.state(S1).uciMoves().size());
        assertTrue(service.state(S2).uciMoves().isEmpty());
    }

    @Test
    void sessionCountIsBounded() {
        service = new WebGameService(null, 5, Duration.ofMinutes(30), 2, 2, ticker);

        for (int i = 0; i < 50; i++) {
            service.state(String.format("session-%08d", i));
        }

        assertTrue(service.activeSessionCount() <= 5, "sessions: " + service.activeSessionCount());
    }

    @Test
    void idleSessionsExpireAndReleaseTheirEngine() throws Exception {
        service = newService(fakeEngine(0), 1);
        service.updateSettings(S1, new GameSettingsRequest(null, false, false, null));
        assertEquals(0, service.availableEngineSlots());

        nanos.addAndGet(Duration.ofMinutes(31).toNanos());
        service.cleanUpSessions();

        waitFor(() -> service.availableEngineSlots() == 1, "engine slot released after eviction");
        assertEquals(0, service.activeSessionCount());
    }

    @Test
    void engineOpponentsAreCapped() throws Exception {
        service = newService(fakeEngine(0), 1);
        service.updateSettings(S1, new GameSettingsRequest(null, false, false, null));

        assertThrows(CapacityException.class,
            () -> service.updateSettings(S2, new GameSettingsRequest(null, false, false, null)));

        service.updateSettings(S1, new GameSettingsRequest(null, true, null, null));
        assertEquals(1, service.availableEngineSlots());
        service.updateSettings(S2, new GameSettingsRequest(null, false, false, null));
        assertEquals(0, service.availableEngineSlots());
    }

    @Test
    void humanCannotMoveWhileEngineIsToMove() throws Exception {
        service = newService(fakeEngine(400), 2);
        service.startGame(S1, new NewGameRequest(null, false, false, null));

        service.move(S1, new MoveRequest(4, 6, 4, 4));
        assertTrue(service.state(S1).engineTurn());

        ConflictException conflict = assertThrows(ConflictException.class,
            () -> service.move(S1, new MoveRequest(4, 1, 4, 3)));
        assertTrue(conflict.getMessage().contains("Stockfish"));
        assertTrue(service.legalMoves(S1, 4, 1).isEmpty());

        waitFor(() -> service.state(S1).uciMoves().size() == 2, "engine reply");
        assertEquals("e7e5", service.state(S1).uciMoves().get(1));
        assertEquals("white", service.state(S1).turn());
    }

    @Test
    void staleEngineMoveIsDiscardedAfterNewGame() throws Exception {
        service = newService(fakeEngine(500), 2);
        // Engine plays white, so once started it thinks about e2e4 immediately.
        service.startGame(S1, new NewGameRequest(null, false, true, null));
        assertTrue(service.state(S1).engineTurn());

        // New game (back to setup) before the reply arrives: the old e2e4 must be discarded.
        service.startNewGame(S1, new NewGameRequest(null, false, true, null));
        Thread.sleep(900);

        GameState state = service.state(S1);
        assertTrue(state.uciMoves().isEmpty(), "stale engine move applied: " + state.uciMoves());
        assertTrue(state.awaitingStart());
        assertFalse(state.engineTurn());

        // Starting again lets the engine play its first move exactly once.
        service.startGame(S1, null);
        waitFor(() -> service.state(S1).uciMoves().size() == 1, "engine opening move");
        Thread.sleep(300);
        assertEquals(1, service.state(S1).uciMoves().size());
    }

    @Test
    void setupChangesNeverTriggerAnEngineMove() throws Exception {
        service = newService(fakeEngine(0), 2);

        service.updateSettings(S1, new GameSettingsRequest(null, false, null, null));
        service.updateSettings(S1, new GameSettingsRequest(null, null, true, null));   // play as Black
        service.updateSettings(S1, new GameSettingsRequest(5, null, null, 1600));
        Thread.sleep(500);

        GameState setup = service.state(S1);
        assertTrue(setup.awaitingStart());
        assertTrue(setup.uciMoves().isEmpty(), "engine moved during setup: " + setup.uciMoves());
        assertFalse(setup.engineTurn());
        assertEquals("05:00", setup.whiteClock());
        assertTrue(service.legalMoves(S1, 4, 1).isEmpty());
        assertThrows(ConflictException.class, () -> service.move(S1, new MoveRequest(4, 1, 4, 3)));

        GameState started = service.startGame(S1, null);
        assertFalse(started.awaitingStart());
        waitFor(() -> service.state(S1).uciMoves().size() == 1, "Stockfish (White) opening move after Start");
        assertEquals("e2e4", service.state(S1).uciMoves().get(0));
        assertEquals("black", service.state(S1).turn());
    }

    @Test
    void stockfishAsBlackRepliesOnlyAfterStart() throws Exception {
        service = newService(fakeEngine(0), 2);
        service.updateSettings(S1, new GameSettingsRequest(null, false, false, null));
        assertThrows(ConflictException.class, () -> service.move(S1, new MoveRequest(4, 6, 4, 4)));

        service.startGame(S1, null);
        assertTrue(service.state(S1).uciMoves().isEmpty());
        service.move(S1, new MoveRequest(4, 6, 4, 4));
        waitFor(() -> service.state(S1).uciMoves().size() == 2, "Stockfish reply");
        assertEquals("e7e5", service.state(S1).uciMoves().get(1));
    }

    @Test
    void opponentAndSideAreLockedMidGame() throws Exception {
        service = newService(fakeEngine(0), 2);
        service.startGame(S1, new NewGameRequest(null, false, false, null));

        assertThrows(ConflictException.class, () -> service.updateSettings(S1, new GameSettingsRequest(null, true, null, null)));
        assertThrows(ConflictException.class, () -> service.updateSettings(S1, new GameSettingsRequest(null, null, true, null)));
        assertThrows(ConflictException.class, () -> service.startGame(S1, null));
        assertEquals(1600, service.updateSettings(S1, new GameSettingsRequest(null, false, false, 1600)).engineElo());

        GameState reset = service.startNewGame(S1, new NewGameRequest(null, true, null, null));
        assertTrue(reset.awaitingStart());
        assertTrue(reset.humanVsHuman());
    }

    @Test
    void localGameStartsWithTheFirstMove() {
        service = newService(null, 0);
        assertTrue(service.state(S1).awaitingStart());

        service.move(S1, new MoveRequest(4, 6, 4, 4));

        assertFalse(service.state(S1).awaitingStart());
        assertTrue(service.state(S1).clockStarted());
    }

    @Test
    void analysisRunsInBackgroundAndEntersReview() throws Exception {
        service = newService(fakeEngine(0), 2);
        service.move(S1, new MoveRequest(4, 6, 4, 4));
        service.move(S1, new MoveRequest(4, 1, 4, 3));

        GameState started = service.analyze(S1);
        assertTrue("running".equals(started.analysisStatus()) || "ready".equals(started.analysisStatus()));

        waitFor(() -> "ready".equals(service.state(S1).analysisStatus()), "analysis finished");
        GameState done = service.state(S1);
        assertTrue(done.analysisMode());
        assertEquals(2, done.analysisEntries().size());
        assertEquals(2, service.availableAnalysisSlots());
        assertThrows(ConflictException.class, () -> service.move(S1, new MoveRequest(6, 7, 5, 5)));
    }

    @Test
    void reviewReportsProgressAndJumpsToAnyPly() throws Exception {
        service = newService(fakeEngine(0), 2);
        service.move(S1, new MoveRequest(4, 6, 4, 4));
        service.move(S1, new MoveRequest(4, 1, 4, 3));
        assertThrows(ConflictException.class, () -> service.jumpAnalysis(S1, 0), "no review yet");

        service.analyze(S1);
        waitFor(() -> "ready".equals(service.state(S1).analysisStatus()), "analysis finished");

        GameState done = service.state(S1);
        assertEquals(new WebGameService.AnalysisProgressView(2, 2), done.analysisProgress());
        assertEquals("e4", done.analysisEntries().get(0).san());
        assertEquals("Book", done.analysisEntries().get(0).qualityTag());
        assertEquals(-1, done.analysisFrame().plyIndex(), "review opens on the summary / start position");

        GameState second = service.jumpAnalysis(S1, 1);
        assertEquals(1, second.analysisFrame().plyIndex());
        assertEquals("e5", second.analysisFrame().san());
        assertNotNull(second.analysisFrame().explanation());
        assertEquals(-1, service.jumpAnalysis(S1, -7).analysisFrame().plyIndex());
        assertEquals(1, service.jumpAnalysis(S1, 99).analysisFrame().plyIndex());
    }

    @Test
    void finishedReviewsAreCachedAcrossSessions() throws Exception {
        service = newService(fakeEngine(0), 2);
        for (String session : new String[] {S1, S2}) {
            service.move(session, new MoveRequest(4, 6, 4, 4));
            service.analyze(session);
            waitFor(() -> "ready".equals(service.state(session).analysisStatus()), "analysis finished");
        }
        assertEquals(service.state(S1).analysisSummary(), service.state(S2).analysisSummary());
    }

    @Test
    void analysisResultIsDroppedWhenGameChanges() throws Exception {
        service = newService(fakeEngine(300), 2);
        service.move(S1, new MoveRequest(4, 6, 4, 4));

        service.analyze(S1);
        service.startNewGame(S1, new NewGameRequest(null, null, null, null));

        waitFor(() -> service.availableAnalysisSlots() == 2, "analysis slot released");
        GameState state = service.state(S1);
        assertFalse(state.analysisMode());
        assertEquals("idle", state.analysisStatus());
        assertTrue(state.uciMoves().isEmpty());
    }

    @Test
    void importedUnderpromotionIsReplayedFaithfully() {
        service = newService(null, 0);

        GameState state = service.importPgn(S1, new PgnImportRequest("1. a4 b5 2. axb5 a6 3. bxa6 Nc6 4. a7 Rb8 5. a8=N *"));

        assertEquals("a7a8n", state.uciMoves().get(8));
        assertTrue(state.pieces().stream().anyMatch(p -> p.col == 0 && p.row == 0 && p.name.equals("Knight")));
    }

    @Test
    void movePromotionFieldIsValidated() {
        service = newService(null, 0);
        service.importPgn(S1, new PgnImportRequest("1. a4 b5 2. axb5 a6 3. bxa6 Nc6 4. a7 Rb8 *"));

        assertThrows(IllegalArgumentException.class, () -> service.move(S1, new MoveRequest(0, 1, 0, 0, "k")));
        assertTrue(service.move(S1, new MoveRequest(0, 1, 0, 0, "b")).legal());
        assertTrue(service.state(S1).pieces().stream().anyMatch(p -> p.col == 0 && p.row == 0 && p.name.equals("Bishop")));
    }

    @Test
    void oversizedPgnIsRejected() {
        service = newService(null, 0);
        String huge = "1. e4 e5 ".repeat(WebGameService.MAX_PGN_CHARS / 9 + 1);

        assertThrows(IllegalArgumentException.class, () -> service.importPgn(S1, new PgnImportRequest(huge)));
    }

    private WebGameService newService(Path engine, int maxEngineSessions) {
        if (engine != null) {
            System.setProperty("stockfish.path", engine.toString());
        } else {
            System.clearProperty("stockfish.path");
        }
        return new WebGameService(null, 100, Duration.ofMinutes(30), maxEngineSessions, 2, ticker);
    }

    /** Minimal UCI engine: from the start position it plays e2e4, otherwise e7e5, after an optional delay. */
    private Path fakeEngine(int delayMillis) throws IOException {
        String delay = delayMillis <= 0 ? "" : String.format("sleep %.3f; ", delayMillis / 1000.0);
        String script = "#!/bin/sh\n"
            + "pos=''\n"
            + "while IFS= read -r line; do\n"
            + "  case \"$line\" in\n"
            + "    uci) echo 'id name Fake'; echo 'uciok' ;;\n"
            + "    isready) echo 'readyok' ;;\n"
            + "    position*) pos=\"$line\" ;;\n"
            + "    go*) " + delay + "if [ \"$pos\" = 'position startpos' ]; then m=e2e4; else m=e7e5; fi;"
            + " echo \"info depth 1 score cp 20 pv $m\"; echo \"bestmove $m\" ;;\n"
            + "    quit) exit 0 ;;\n"
            + "  esac\n"
            + "done\n";
        Path path = tempDir.resolve("fake-stockfish.sh");
        Files.writeString(path, script);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    private static void waitFor(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for " + description);
            }
            Thread.sleep(25);
        }
    }
}
