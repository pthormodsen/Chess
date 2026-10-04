package support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a tiny shell-script UCI engine for tests: every search reports the given score token and best move. */
public final class FakeUciEngine {
    private FakeUciEngine() {}

    /**
     * @param scoreToken e.g. {@code "cp 1581"} or {@code "mate 3"}; reported for every position
     *                   from the side to move's perspective, like a real UCI engine.
     */
    public static Path write(Path dir, String scoreToken, String bestMove) throws IOException {
        String script = "#!/bin/sh\n"
            + "while IFS= read -r line; do\n"
            + "  case \"$line\" in\n"
            + "    uci) echo 'id name Fake'; echo 'uciok' ;;\n"
            + "    isready) echo 'readyok' ;;\n"
            + "    go*) echo 'info depth 12 score " + scoreToken + " pv " + bestMove + "'; echo 'bestmove " + bestMove + "' ;;\n"
            + "    quit) exit 0 ;;\n"
            + "  esac\n"
            + "done\n";
        Path path = dir.resolve("fake-uci-" + scoreToken.replace(' ', '_') + ".sh");
        Files.writeString(path, script);
        if (!path.toFile().setExecutable(true)) {
            throw new IOException("Could not make fake engine executable");
        }
        return path;
    }
}
