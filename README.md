# Chess

Chess is a Spring Boot + React web app for browser play, Stockfish review, puzzles and self-hosting.

The web version is designed to run on a home Ubuntu server behind Cloudflare Tunnel.

## Features

- Play human vs human in the browser.
- Play against Stockfish with configurable side and Elo.
- Import PGN text or `.pgn` / `.txt` files for review.
- Run game review with Stockfish best moves, move classifications, arrows, eval graph, and accuracy.
- Anonymous per-browser sessions using `localStorage`, so different visitors do not share the same board.
- Mobile-friendly board layout with touch scroll locking while moving pieces.
- Timer starts after White makes the first move, not when the game is created.

## Requirements

Local development:

- Java 17+
- Maven 3.9+
- Node.js 20+
- Stockfish binary for your OS

Server deployment:

- Ubuntu with Docker and Docker Compose plugin
- Cloudflare Tunnel, if exposing through `chess.patreek.no`

## Local Development

Start the Spring Boot backend from the project root:

```bash
cd /Users/patrik/Documents/Prosjekter/Chess
./run-web.sh
```

The backend runs on:

```text
http://localhost:8081
```

In another terminal, start the React/Vite frontend:

```bash
cd /Users/patrik/Documents/Prosjekter/Chess/frontend
npm run dev
```

Open:

```text
http://localhost:3000
```

Vite proxies `/api` and `/ws` to Spring Boot on port `8081`.

## Local Stockfish

`run-web.sh` sets `STOCKFISH_PATH` to the bundled local path:

```bash
/Users/patrik/Documents/Prosjekter/Chess/engines/stockfish/stockfish-macos-m1-apple-silicon
```

To use another Stockfish binary:

```bash
export STOCKFISH_PATH=/absolute/path/to/stockfish
./run-web.sh
```

## Deployment

Deployment is split between GitHub Actions (build) and the server (pull and run). Nothing is built on the server, and GitHub Actions never connects to it.

### CI and images

`.github/workflows/ci.yml` runs on every pull request and every push to `main`:

1. Backend tests (`mvn -B test`).
2. Frontend production build.
3. Docker image build. On pushes to `main` the image is pushed to GHCR (authenticated with `GITHUB_TOKEN`) as:
   - `ghcr.io/pthormodsen/chess:latest`
   - `ghcr.io/pthormodsen/chess:<short-sha>` (7-character commit SHA, e.g. `8c59db7`)

The image builds the React frontend, packages the Spring Boot app, and installs Stockfish at `/usr/games/stockfish`.

### Server

The project is checked out on `docker01` at:

```text
/home/pmt/docker/Chess
```

The checkout only provides `docker-compose.yml` and the `Makefile`. Compose runs `ghcr.io/pthormodsen/chess:${CHESS_IMAGE_TAG:-latest}`.

If the GHCR package is private, log in once on the server with a personal access token that has `read:packages`:

```bash
echo <token> | docker login ghcr.io -u pthormodsen --password-stdin
```

### Make targets

On a development machine:

```bash
make dev        # backend on :8081 + Vite with hot reload on http://localhost:3000 (compose.dev.yml)
make dev-down   # stop the dev containers
make test       # backend tests
make build      # build the production image as ghcr.io/pthormodsen/chess:local
```

Frontend edits hot reload. Backend changes need `make dev` again, which rebuilds the backend image. To run without Docker, use `./run-web.sh` plus `npm run dev` in `frontend/` (see Local Development).

On the server, from `/home/pmt/docker/Chess`:

```bash
make deploy             # git pull --ff-only, then make prod, then docker compose ps
make prod               # docker compose pull && docker compose up -d
make prod-down          # docker compose down
make rollback TAG=<sha> # pull and run ghcr.io/pthormodsen/chess:<sha>
make restart            # restart the running container
make logs               # follow container logs
make ps                 # docker compose ps (also: make status)
```

A rollback lasts until the next `make deploy` or `make prod`, both of which return to `latest`. To find a SHA, use `git log --oneline` or the package page on GitHub.

### Releasing a change

```bash
git push origin main     # wait for the CI workflow to finish and publish the image
ssh pmt@docker01
cd /home/pmt/docker/Chess
make deploy
```

## Docker Compose

No host port is published. The `chess-web` container joins the external `cloudflare` Docker network, and `cloudflared` reaches it there. The compose file also sets memory, CPU and PID limits, log rotation, and `STOCKFISH_PATH=/usr/games/stockfish`.

## Cloudflare Tunnel

The tunnel's public hostname for `chess.patreek.no` should point to:

```text
http://chess-web:8081
```

The DNS record in Cloudflare should be:

```text
Type: CNAME
Name: chess
Target: <tunnel-id>.cfargotunnel.com
Proxy status: Proxied
TTL: Auto
```

Check the deployed app:

```bash
curl -I https://chess.patreek.no
```

## PGN Review

In the web app:

1. Paste PGN into **Import PGN**, or upload a `.pgn` / `.txt` file.
2. Click **Import for review**.
3. Click **Analyze**.
4. Use **Previous** / **Next**, or keyboard arrow keys on desktop, to step through the review.

The backend parses SAN PGN with `chesslib`, converts it to UCI, then replays the moves through the Java board validation.

## Sessions And Accounts

The current web app uses anonymous per-browser sessions. Each browser stores a generated session id in `localStorage` and sends it as `X-Chess-Session`.

This prevents public users from sharing the same active game, but it is not a full account system. Real accounts, saved games, login, and persistent review history would require adding a database and authentication.

## Troubleshooting

If the deployed site does not update, first confirm that the CI run on `main` succeeded and pushed the image. Then:

```bash
cd /home/pmt/docker/Chess
make deploy
make logs
```

If the browser says DNS cannot resolve:

```bash
dig +short chess.patreek.no
```

If analysis fails, confirm Stockfish exists inside the container:

```bash
docker exec -it chess-web which stockfish
docker exec -it chess-web /usr/games/stockfish
```

## Notes

- Stockfish runs server-side. Any phone or computer using `chess.patreek.no` sends requests to the Ubuntu server; it does not need Stockfish installed locally.
- The server container uses Java 17.
- Local development uses the Mac Stockfish binary configured in `run-web.sh`.
