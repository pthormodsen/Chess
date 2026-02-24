# Chess (Swing + Stockfish)

This is a simple Swing chess GUI that lets you play as White against the Stockfish engine. It highlights legal moves, tracks captured material, and shows who is ahead (similar to chess.com). The project was built with the help of an AI assistant.

## Requirements

- macOS/Linux/Windows with Java 23 (or adjust the Maven compiler version if needed)
- Maven 3.9+
- Stockfish binary (macOS ARM build shown below, but any UCI-compatible Stockfish works)

## Setup

1. **Clone the repo**
   ```bash
   git clone https://github.com/<your-account>/Chess.git
   cd Chess
   ```

2. **Download Stockfish**
   - Grab a binary from [https://stockfishchess.org/download/](https://stockfishchess.org/download/).
   - Place it in `engines/stockfish/` (create the folder if it doesn’t exist).
   - Example path: `engines/stockfish/stockfish-macos-m1-apple-silicon`.
   - Mark it executable on macOS/Linux:
     ```bash
     chmod +x engines/stockfish/stockfish-macos-m1-apple-silicon
     ```

3. **Set the Stockfish path**
   - Export the env var in the terminal you’ll use:
     ```bash
     export STOCKFISH_PATH=/absolute/path/to/stockfish-binary
     ```
   - If you run from an IDE, add the same path as an environment variable or JVM property (`-Dstockfish.path=...`).

4. **Build & run**
   ```bash
   mvn clean package
   java -cp target/classes main.Main
   ```
   (Alternatively, run `./run.sh`, which builds and launches with the env var baked in.)

## Using the app

- Click **Play** to start a game. The Stockfish side moves automatically.
- Use the **Difficulty** dropdown to adjust Stockfish’s “Skill Level” (0–20). You can change it even mid-game.
- The top-left shows:
  - Material evaluation (e.g., “White +3”).
  - Captured pieces for each color using chess Unicode icons.
- The centered label shows whose turn it is or the result (White/Black wins, stalemate, insufficient material, etc.).
- The board highlights legal moves for the selected piece; the last move is shown with a yellow overlay.

## Notes

- This codebase was produced with AI assistance; double-check logic before shipping it into production.
- Stockfish binaries are large; they’re intentionally not tracked in git. Each user must supply their own copy.
- Tested on macOS with Java 23; adjust Maven’s `<maven.compiler.source>`/`target` if you need Java 17 or earlier.
