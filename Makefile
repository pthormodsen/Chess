# prod/deploy/rollback run the image GitHub Actions publishes to GHCR; nothing is built on the server.
# dev builds the working tree locally via compose.dev.yml.
IMAGE   := ghcr.io/pthormodsen/chess
SERVICE := chess
DEV     := docker compose -f compose.dev.yml

.PHONY: help dev dev-down prod prod-down deploy rollback restart logs ps status test build

help:
	@echo "Local:"
	@echo "  make dev               Backend :8081 + Vite hot reload on http://localhost:3000"
	@echo "  make dev-down          Stop the dev containers"
	@echo "  make test              Run backend tests"
	@echo "  make build             Build the production image locally as $(IMAGE):local"
	@echo "Server:"
	@echo "  make prod              Pull the latest image and start it"
	@echo "  make prod-down         Stop production"
	@echo "  make deploy            git pull + make prod + status"
	@echo "  make rollback TAG=sha  Pull and run a specific image tag (short commit SHA)"
	@echo "  make restart           Restart the running container"
	@echo "  make logs / ps / status"

dev:
	$(DEV) up --build

dev-down:
	$(DEV) down

prod:
	docker compose pull
	docker compose up -d

prod-down:
	docker compose down

deploy:
	git pull --ff-only origin main
	$(MAKE) prod
	docker compose ps

rollback:
	@test -n "$(TAG)" || { echo "Usage: make rollback TAG=<short-sha>"; exit 1; }
	CHESS_IMAGE_TAG=$(TAG) docker compose pull
	CHESS_IMAGE_TAG=$(TAG) docker compose up -d
	docker compose ps

restart:
	docker compose restart $(SERVICE)

logs:
	docker compose logs -f --tail=200 $(SERVICE)

ps status:
	docker compose ps

test:
	mvn -B test

build:
	docker build -t $(IMAGE):local .
