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
import server.service.WebGameService.PremoveRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WebGameServiceTest {
    private static final String S1 = "11111111-aaaa-bbbb-cccc-000000000001";
    private static final String S2 = "11111111-aaaa-bbbb-cccc-000000000002";

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
        assertTrue(setup.untimed(), "games vs Stockfish have no clock");
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
    void exploreJudgesAnyMoveFromAReviewPositionWithoutTouchingTheReview() throws Exception {
        service = newService(fakeEngine(0), 2);
        service.move(S1, new MoveRequest(4, 6, 4, 4));
        service.move(S1, new MoveRequest(4, 1, 4, 3));
        service.analyze(S1);
        waitFor(() -> "ready".equals(service.state(S1).analysisStatus()), "analysis finished");

        String afterE4 = service.jumpAnalysis(S1, 0).analysisFrame().positionFen();
        assertTrue(afterE4.startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b"), afterE4);

        var legal = service.exploreLegal(S1, new WebGameService.ExploreRequest(afterE4, null));
        assertEquals(20, legal.legalMoves().size());

        // Black tries 1...c5 instead of the game's 1...e5.
        WebGameService.ExploreView tried = service.explore(S1, new WebGameService.ExploreRequest(afterE4, "c7c5"));
        assertEquals("c5", tried.san());
        assertFalse(tried.whiteMove());
        assertEquals(1, tried.moveNumber());
        assertNotNull(tried.qualityTag());
        assertNotNull(tried.explanation());
        assertTrue(tried.fen().startsWith("rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w"), tried.fen());
        assertEquals(30, tried.legalMoves().size());
        assertSame(tried, service.explore(S1, new WebGameService.ExploreRequest(afterE4, "c7c5")), "cached");

        // The review is untouched.
        GameState review = service.state(S1);
        assertTrue(review.analysisMode());
        assertEquals(0, review.analysisFrame().plyIndex());
        assertEquals(List.of("e2e4", "e7e5"), review.analysisEntries().stream().map(e -> e.playedMove()).toList());

        assertThrows(IllegalArgumentException.class,
            () -> service.explore(S1, new WebGameService.ExploreRequest(afterE4, "e2e4")), "White's move on Black's turn");
        assertThrows(IllegalArgumentException.class,
            () -> service.explore(S1, new WebGameService.ExploreRequest("not a fen", "e2e4")));
        assertThrows(IllegalArgumentException.class,
            () -> service.exploreLegal(S1, new WebGameService.ExploreRequest("8/8/8/8/8/8/8/8 w - - 0 1", null)), "no kings");
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
    void importedGamesHaveNoRunningClockInLiveViewOrReview() throws Exception {
        service = newService(fakeEngine(0), 2);
        GameState imported = service.importPgn(S1, new PgnImportRequest("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 *"));
        assertTrue(imported.untimed());
        assertFalse(imported.clockStarted());

        Thread.sleep(1_100); // a running clock would lose a second here
        GameState later = service.state(S1);
        assertEquals(imported.whiteClock(), later.whiteClock());
        assertEquals(imported.blackClock(), later.blackClock());

        service.analyze(S1);
        waitFor(() -> "ready".equals(service.state(S1).analysisStatus()), "analysis finished");
        service.jumpAnalysis(S1, 3);
        GameState afterReview = service.exitAnalysis(S1);
        assertFalse(afterReview.clockStarted());
        assertEquals(imported.whiteClock(), afterReview.whiteClock());
        assertEquals(imported.blackClock(), afterReview.blackClock());

        // Continuing the imported game by hand does not start a clock either; a new game does.
        GameState continued = service.move(S1, new MoveRequest(1, 7, 2, 5)).state(); // Nc3
        assertFalse(continued.clockStarted());
        service.startNewGame(S1, new NewGameRequest(null, true, null, null));
        GameState fresh = service.move(S1, new MoveRequest(4, 6, 4, 4)).state();
        assertFalse(fresh.untimed());
        assertTrue(fresh.clockStarted());
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

    @Test
    void premoveIsPlayedByTheServerAsSoonAsStockfishHasMoved() throws Exception {
        service = newService(fakeEngine(300), 2);
        service.startGame(S1, new NewGameRequest(null, false, false, null));
        service.move(S1, new MoveRequest(4, 6, 4, 4)); // e4; Stockfish thinks about e5

        // Nf3, queued while Stockfish thinks: it belongs at ply 2.
        GameState queued = service.setPremoves(S1, new PremoveRequest(2, List.of(new MoveRequest(6, 7, 5, 5))));
        assertEquals(1, queued.premoves().size());
        assertEquals(2, queued.premoveStartPly());

        waitFor(() -> service.state(S1).uciMoves().size() >= 3, "premove played after the engine reply");
        GameState state = service.state(S1);
        assertEquals(List.of("e2e4", "e7e5", "g1f3"), state.uciMoves().subList(0, 3));
        assertTrue(state.premoves().isEmpty());
    }

    @Test
    void premoveQueuedForALaterPlyWaitsAndAStaleOneIsDropped() throws Exception {
        service = newService(fakeEngine(0), 2);
        service.startGame(S1, new NewGameRequest(null, false, false, null));
        service.move(S1, new MoveRequest(4, 6, 4, 4));
        waitFor(() -> service.state(S1).uciMoves().size() == 2, "engine reply");

        // Sent while our own move to ply 3 is still on its way: it must not be played as that move.
        GameState waiting = service.setPremoves(S1, new PremoveRequest(4, List.of(new MoveRequest(6, 7, 5, 5))));
        assertEquals(2, waiting.uciMoves().size());
        assertEquals(1, waiting.premoves().size());

        GameState stale = service.setPremoves(S1, new PremoveRequest(0, List.of(new MoveRequest(6, 7, 5, 5))));
        assertEquals(2, stale.uciMoves().size());
        assertTrue(stale.premoves().isEmpty());
    }

    @Test
    void illegalPremoveDropsTheWholeQueue() throws Exception {
        service = newService(fakeEngine(0), 2);
        service.startGame(S1, new NewGameRequest(null, false, false, null));
        service.move(S1, new MoveRequest(4, 6, 4, 4));
        waitFor(() -> service.state(S1).uciMoves().size() == 2, "engine reply");

        // e2-e4 again: the pawn is gone.
        GameState state = service.setPremoves(S1, new PremoveRequest(2,
            List.of(new MoveRequest(4, 6, 4, 4), new MoveRequest(6, 7, 5, 5))));
        assertEquals(2, state.uciMoves().size());
        assertTrue(state.premoves().isEmpty());
    }

    @Test
    void premovesAreIgnoredInLocalGames() {
        service = newService(null, 0);
        GameState state = service.setPremoves(S1, new PremoveRequest(0, List.of(new MoveRequest(4, 6, 4, 4))));
        assertTrue(state.premoves().isEmpty());
        assertTrue(state.uciMoves().isEmpty());
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
