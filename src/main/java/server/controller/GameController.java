package server.controller;

import main.Board;
import server.service.WebGameService;
import server.service.WebGameService.GameState;
import server.service.WebGameService.GameSettingsRequest;
import server.service.WebGameService.MoveRequest;
import server.service.WebGameService.MoveResponse;
import server.service.WebGameService.NewGameRequest;
import server.service.WebGameService.PgnImportRequest;
import server.service.WebGameService.PgnResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public GameState state(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId) {
        return gameService.state(sessionId);
    }

    @PostMapping("/new")
    public GameState newGame(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                             @RequestBody(required = false) NewGameRequest request) {
        return gameService.startNewGame(sessionId, request);
    }

    @PostMapping("/start")
    public GameState start(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                           @RequestBody(required = false) NewGameRequest request) {
        return gameService.startGame(sessionId, request);
    }

    @PostMapping("/settings")
    public GameState settings(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                              @RequestBody GameSettingsRequest request) {
        return gameService.updateSettings(sessionId, request);
    }

    @GetMapping("/legal")
    public List<Board.SquareView> legalMoves(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                                             @RequestParam int col,
                                             @RequestParam int row) {
        return gameService.legalMoves(sessionId, col, row);
    }

    @PostMapping("/move")
    public ResponseEntity<MoveResponse> move(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                                             @RequestBody MoveRequest request) {
        MoveResponse response = gameService.move(sessionId, request);
        if(!response.legal()) {
            return ResponseEntity.badRequest().body(response);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/premoves")
    public GameState premoves(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                              @RequestBody WebGameService.PremoveRequest request) {
        return gameService.setPremoves(sessionId, request);
    }

    @GetMapping("/pgn")
    public PgnResponse pgn(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId) {
        return new PgnResponse(gameService.pgn(sessionId));
    }

    /** Starts a background review; the returned state has analysisStatus "running" until polling sees it finish. */
    @PostMapping("/analyze")
    public GameState analyze(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId) {
        return gameService.analyze(sessionId);
    }

    @PostMapping("/import-pgn")
    public GameState importPgn(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                               @RequestBody PgnImportRequest request) {
        return gameService.importPgn(sessionId, request);
    }

    @PostMapping("/analysis/previous")
    public GameState previousAnalysisMove(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId) {
        return gameService.stepAnalysis(sessionId, -1);
    }

    @PostMapping("/analysis/next")
    public GameState nextAnalysisMove(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId) {
        return gameService.stepAnalysis(sessionId, 1);
    }

    /** Jumps the review to a ply; -1 is the starting position. */
    @PostMapping("/analysis/goto")
    public GameState gotoAnalysisMove(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                                      @RequestBody AnalysisGotoRequest request) {
        return gameService.jumpAnalysis(sessionId, request == null ? -1 : request.ply());
    }

    public record AnalysisGotoRequest(int ply) {}

    /** Legal moves for a position (FEN), for trying moves during review. */
    @PostMapping("/explore/legal")
    public WebGameService.ExploreLegalView exploreLegal(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                                                        @RequestBody WebGameService.ExploreRequest request) {
        return gameService.exploreLegal(sessionId, request);
    }

    /** Plays one move from a position (FEN) and judges it like a reviewed move; the review itself is untouched. */
    @PostMapping("/explore")
    public WebGameService.ExploreView explore(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId,
                                              @RequestBody WebGameService.ExploreRequest request) {
        return gameService.explore(sessionId, request);
    }

    @PostMapping("/analysis/exit")
    public GameState exitAnalysis(@RequestHeader(value = "X-Chess-Session", required = false) String sessionId) {
        return gameService.exitAnalysis(sessionId);
    }
}
