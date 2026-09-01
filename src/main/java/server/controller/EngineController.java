package server.controller;

import org.springframework.web.bind.annotation.*;
import uci.StockfishClient;
import uci.StockfishClient.AnalysisResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.time.Duration;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/engine")
public class EngineController {

    @Value("${stockfish.path}")
    private String stockfishPath;

    @PostMapping("/bestmove")
    public ResponseEntity<?> bestMove(@RequestBody Map<String, Object> body) {
        String moves = (String) body.getOrDefault("moves", "");
        Number movetime = (Number) body.getOrDefault("movetimeMs", 500);
        try (StockfishClient client = new StockfishClient(Path.of(stockfishPath))) {
            AnalysisResult res = client.analyzePosition(moves, Duration.ofMillis(movetime.longValue()));
            return ResponseEntity.ok(Map.of(
                    "bestMove", res == null ? null : res.bestMove,
                    "scoreCp", res == null ? null : res.scoreCp,
                    "mate", res == null ? null : res.mate,
                    "pv", res == null ? null : res.principalVariation
            ));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
