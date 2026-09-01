package server.controller;

import main.Board;
import server.service.WebGameService;
import server.service.WebGameService.GameState;
import server.service.WebGameService.GameSettingsRequest;
import server.service.WebGameService.MoveRequest;
import server.service.WebGameService.MoveResponse;
import server.service.WebGameService.NewGameRequest;
import server.service.WebGameService.PgnResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/game")
public class GameController {
    private final WebGameService gameService;

    public GameController(WebGameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping
    public GameState state() {
        return gameService.state();
    }

    @PostMapping("/new")
    public GameState newGame(@RequestBody(required = false) NewGameRequest request) {
        return gameService.startNewGame(request);
    }

    @PostMapping("/settings")
    public GameState settings(@RequestBody GameSettingsRequest request) {
        return gameService.updateSettings(request);
    }

    @GetMapping("/legal")
    public List<Board.SquareView> legalMoves(@RequestParam int col, @RequestParam int row) {
        return gameService.legalMoves(col, row);
    }

    @PostMapping("/move")
    public ResponseEntity<MoveResponse> move(@RequestBody MoveRequest request) {
        MoveResponse response = gameService.move(request);
        if(!response.legal()) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/pgn")
    public PgnResponse pgn() {
        return new PgnResponse(gameService.pgn());
    }

    @PostMapping("/analyze")
    public GameState analyze() throws Exception {
        return gameService.analyze().get();
    }

    @PostMapping("/analysis/previous")
    public GameState previousAnalysisMove() {
        return gameService.stepAnalysis(-1);
    }

    @PostMapping("/analysis/next")
    public GameState nextAnalysisMove() {
        return gameService.stepAnalysis(1);
    }

    @PostMapping("/analysis/exit")
    public GameState exitAnalysis() {
        return gameService.exitAnalysis();
    }
}
