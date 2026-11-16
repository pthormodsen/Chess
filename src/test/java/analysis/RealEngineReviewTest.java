package analysis;

import com.github.bhlangonijr.chesslib.move.MoveList;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs only with a real Stockfish (STOCKFISH_PATH set): reviews are reproducible and look sensible. */
class RealEngineReviewTest {

    static final String OPERA_GAME = "1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7 "
        + "8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6 "
        + "15. Bxd7+ Nxd7 16. Qb8+ Nxb8 17. Rd8#";

    static Path engine() {
        String path = System.getenv("STOCKFISH_PATH");
        assumeTrue(path != null && Files.isExecutable(Path.of(path)), "needs a real Stockfish in STOCKFISH_PATH");
        return Path.of(path);
    }

    static List<String> uci(String pgn) throws Exception {
        MoveList list = new MoveList();
        list.loadFromSan(pgn.replaceAll("\\d+\\.", " ").trim());
        return list.stream().map(Object::toString).toList();
    }

    @Test
    void sameGameGivesIdenticalReviewEveryTime() throws Exception {
        Path engine = engine();
        List<String> moves = uci(OPERA_GAME);
        GameAnalyzer.Summary first = new GameAnalyzer(engine, 12, 2).analyze(moves);
        GameAnalyzer.Summary second = new GameAnalyzer(engine, 12, 2).analyze(moves);

        assertEquals(first.whiteAccuracy, second.whiteAccuracy);
        assertEquals(first.blackAccuracy, second.blackAccuracy);
        for (int i = 0; i < moves.size(); i++) {
            assertEquals(first.entries.get(i).qualityTag, second.entries.get(i).qualityTag, "ply " + i);
            assertEquals(first.entries.get(i).bestMove, second.entries.get(i).bestMove, "ply " + i);
        }
        if (System.getenv("REVIEW_PRINT") != null) {
            GameAnalyzer.Summary deep = new GameAnalyzer(engine, Integer.parseInt(System.getenv("REVIEW_PRINT")), 2).analyze(moves);
            GameReviewFormatter.buildReport(deep, deep.entries.stream().map(e -> e.san).toList()).forEach(System.out::println);
        }
    }
}
