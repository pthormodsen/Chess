#!/bin/bash
set -e

echo "=== Deploying Chess application ==="

cd /home/pmt/web/Chess

echo "=== Pulling latest changes ==="
git pull --ff-only origin main

echo "=== Building and starting container ==="
docker compose up -d --build

echo "=== Deployment complete ==="
docker compose ps
