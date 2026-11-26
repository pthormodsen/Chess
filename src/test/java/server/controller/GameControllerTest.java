package server.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import server.ServerApplication;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ServerApplication.class, properties = "stockfish.path=")
@AutoConfigureMockMvc
class GameControllerTest {
    private static final String SESSION = "22222222-aaaa-bbbb-cccc-000000000001";

    @Autowired
    MockMvc mvc;

    @Test
    void requestsWithoutValidSessionHeaderAreRejected() throws Exception {
        mvc.perform(get("/api/game"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/game").header("X-Chess-Session", "nope"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void stateAndMoveRoundTrip() throws Exception {
        mvc.perform(get("/api/game").header("X-Chess-Session", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.turn").value("white"))
            .andExpect(jsonPath("$.engineTurn").value(false));

        mvc.perform(post("/api/game/move").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromCol\":4,\"fromRow\":6,\"toCol\":4,\"toRow\":4}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.legal").value(true))
            .andExpect(jsonPath("$.state.uciMoves[0]").value("e2e4"));

        mvc.perform(post("/api/game/move").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromCol\":4,\"fromRow\":1,\"toCol\":4,\"toRow\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.legal").value(false));
    }

    @Test
    void badPromotionAndMalformedBodiesAreClientErrors() throws Exception {
        mvc.perform(post("/api/game/move").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromCol\":4,\"fromRow\":6,\"toCol\":4,\"toRow\":4,\"promotion\":\"k\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/game/move").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void gotoOutsideReviewIsAConflict() throws Exception {
        mvc.perform(post("/api/game/analysis/goto").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ply\":3}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void exploreValidatesPositionsAndMoves() throws Exception {
        String afterE4 = "{\"fen\":\"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1\"";
        mvc.perform(post("/api/game/explore/legal").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON).content(afterE4 + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.legalMoves.length()").value(20));
        mvc.perform(post("/api/game/explore/legal").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON).content("{\"fen\":\"rm -rf /\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/game/explore").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON).content(afterE4 + ",\"move\":\"e2e4\"}"))
            .andExpect(status().isBadRequest());
        // No engine configured in this test context: a legal move is a clean 503, not a crash.
        mvc.perform(post("/api/game/explore").header("X-Chess-Session", SESSION)
                .contentType(MediaType.APPLICATION_JSON).content(afterE4 + ",\"move\":\"c7c5\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/game/explore/legal").contentType(MediaType.APPLICATION_JSON).content(afterE4 + "}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void analyzeWithoutEngineReturnsImmediately() throws Exception {
        mvc.perform(post("/api/game/analyze").header("X-Chess-Session", SESSION))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.analysisStatus").exists());
    }

    @Test
    void newGoesToSetupAndStartBeginsPlay() throws Exception {
        String session = "22222222-aaaa-bbbb-cccc-000000000002";
        mvc.perform(post("/api/game/new").header("X-Chess-Session", session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.awaitingStart").value(true))
            .andExpect(jsonPath("$.whiteClock").value("05:00"));
        mvc.perform(post("/api/game/start").header("X-Chess-Session", session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.awaitingStart").value(false));
        mvc.perform(post("/api/game/start").header("X-Chess-Session", session))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void removedEngineEndpointsAreGone() throws Exception {
        mvc.perform(post("/api/engine/bestmove").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }
}
