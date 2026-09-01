# Chess (Swing + Web + Stockfish)

This is a simple chess project with a Swing desktop GUI and a Spring Boot powered web version. The desktop app lets you play as White against the Stockfish engine. The web app serves a React frontend from the Spring Boot jar and exposes Stockfish through `/api/engine`.

## Requirements

- macOS/Linux/Windows with Java 17+
- Maven 3.9+
- Stockfish binary (macOS ARM build shown below, but any UCI-compatible Stockfish works)
- Node.js 20+ if you want to build the frontend outside Docker

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

4. **Build & run the desktop app**
   ```bash
   mvn clean package
   java -cp target/classes main.Main
   ```
   (Alternatively, run `./run.sh`, which builds and launches with the env var baked in.)

## Web version

For local frontend development:

```bash
cd frontend
npm ci
npm run dev
```

Run the Spring Boot API separately from the project root:

```bash
export STOCKFISH_PATH=/absolute/path/to/stockfish-binary
mvn spring-boot:run -Dspring-boot.run.main-class=server.ServerApplication
```

Vite runs on `http://localhost:3000` and proxies `/api` and `/ws` to Spring Boot on port `8081`.

## Self-host on Ubuntu with Docker Compose

On the Ubuntu server, install Docker and the Compose plugin, then from the project directory run:

```bash
docker compose up -d --build
```

The container installs Stockfish during the image build. Docker Compose publishes it on port `8080`:

```text
http://<server-ip>:8080
```

For a domain name, put a reverse proxy such as Caddy, Nginx Proxy Manager, or Nginx in front of the container and forward traffic to `localhost:8080`. If you expose it outside your home network, enable HTTPS at the proxy and avoid publishing extra ports.

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
- The Docker image uses Java 17 and installs Stockfish inside the runtime container.
