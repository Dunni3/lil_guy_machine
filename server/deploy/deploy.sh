#!/usr/bin/env bash
# Sync server/ to the instance and (re)start the stack. Called by `make server-deploy`.
#   DEPLOY_HOST=ubuntu@lilguy.example.com server/deploy/deploy.sh
# The server's .env is created once by hand on the box and is never overwritten.
set -euo pipefail
: "${DEPLOY_HOST:?set DEPLOY_HOST, e.g. ubuntu@lilguy.example.com}"
DEPLOY_DIR="${DEPLOY_DIR:-lilguy-server}"
cd "$(dirname "$0")/.."

ssh "$DEPLOY_HOST" "test -f $DEPLOY_DIR/.env" || {
  echo "No $DEPLOY_DIR/.env on $DEPLOY_HOST. Create it from server/.env.example first." >&2
  exit 1
}

rsync -az --delete --exclude .env --exclude '__pycache__' --exclude '.pytest_cache' ./ "$DEPLOY_HOST:$DEPLOY_DIR/"
ssh "$DEPLOY_HOST" "cd $DEPLOY_DIR && docker compose up -d --build && docker compose ps"
