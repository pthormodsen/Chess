package server.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import server.ServerApplication;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ServerApplication.class, properties = "stockfish.path=")
@AutoConfigureMockMvc
class PuzzleControllerTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void nextPuzzleNeedsNoSessionAndIsNearTheRating() throws Exception {
        mvc.perform(get("/api/puzzles/next").param("rating", "1200"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").isString())
            .andExpect(jsonPath("$.fen").isString())
            .andExpect(jsonPath("$.moves.length()", greaterThanOrEqualTo(2)))
            .andExpect(jsonPath("$.rating", greaterThanOrEqualTo(1125)))
            .andExpect(jsonPath("$.rating", lessThanOrEqualTo(1275)))
            .andExpect(jsonPath("$.color").isString());
    }

    @Test
    void themeFilterAndThemeList() throws Exception {
        mvc.perform(get("/api/puzzles/themes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].count", greaterThan(0)));
        mvc.perform(get("/api/puzzles/next").param("rating", "1500").param("theme", "fork"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.themes", hasItem("fork")));
    }

    @Test
    void badInputIsAJsonError() throws Exception {
        mvc.perform(get("/api/puzzles/next").param("theme", "no such theme"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/puzzles/next").param("theme", "noSuchTheme"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/puzzles/zzzzzzzz"))
            .andExpect(status().isNotFound());
    }
}
