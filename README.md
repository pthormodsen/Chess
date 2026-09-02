# Chess

Chess is a Java chess project with two interfaces:

- A Swing desktop app.
- A Spring Boot + React web app for browser play, Stockfish review, and self-hosting.

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

## Desktop App

Build and run the Swing version:

```bash
mvn clean package
java -cp target/classes main.Main
```

Or use:

```bash
./run.sh
```

## Server Deployment

On the Ubuntu server, the project is expected at:

```text
/home/pmt/web/Chess
```

Deploy or update:

```bash
cd /home/pmt/web/Chess
./deploy.sh
```

`deploy.sh` runs:

```bash
git pull --ff-only origin main
docker compose up -d --build
docker compose ps
```

The Docker image builds the React frontend, packages the Spring Boot app, and installs Stockfish inside the container.

## Docker Compose

The app is bound to localhost only:

```yaml
ports:
  - "127.0.0.1:8081:8081"
```

That means it is not directly exposed to the LAN or internet. Cloudflare Tunnel or another reverse proxy should forward public traffic to:

```text
http://localhost:8081
```

Inside Docker, Stockfish is installed at:

```text
/usr/games/stockfish
```

## Cloudflare Tunnel

Your tunnel config should contain an ingress rule like:

```yaml
- hostname: chess.patreek.no
  service: http://localhost:8081
```

The DNS record in Cloudflare should be:

```text
Type: CNAME
Name: chess
Target: <tunnel-id>.cfargotunnel.com
Proxy status: Proxied
TTL: Auto
```

After changing the tunnel config:

```bash
sudo systemctl restart cloudflared
```

Check the deployed app:

```bash
curl -I http://localhost:8081
curl -I https://chess.patreek.no
```

## Updating The Server

After local changes are working:

```bash
cd /Users/patrik/Documents/Prosjekter/Chess
git status
git add .
git commit -m "Update chess web app"
git push
```

Then on Ubuntu:

```bash
ssh pmt@pmt-server
cd /home/pmt/web/Chess
./deploy.sh
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

If the deployed site does not update:

```bash
cd /home/pmt/web/Chess
git pull --ff-only origin main
docker compose up -d --build
docker compose logs -f chess
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
