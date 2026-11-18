#!/usr/bin/env bash
# Build the sim host, sync server/ to the instance and (re)start the stack. Called by `make server-deploy`.
#   DEPLOY_HOST=ubuntu@lilguy.example.com server/deploy/deploy.sh
# The server's .env and .env.host are created once by hand on the box and are never overwritten.
set -euo pipefail
: "${DEPLOY_HOST:?set DEPLOY_HOST, e.g. ubuntu@lilguy.example.com}"
DEPLOY_DIR="${DEPLOY_DIR:-lilguy-server}"
cd "$(dirname "$0")/.."

ssh "$DEPLOY_HOST" "test -f $DEPLOY_DIR/.env" || {
  echo "No $DEPLOY_DIR/.env on $DEPLOY_HOST. Create it from server/.env.example first." >&2
  exit 1
}
ssh "$DEPLOY_HOST" "grep -q '^LILGUY_ADMIN_PASSWORD=.' $DEPLOY_DIR/.env.host" || {
  echo "No admin password in $DEPLOY_DIR/.env.host on $DEPLOY_HOST. Create it from server/.env.host.example first." >&2
  exit 1
}

deploy/stage-host.sh
rsync -az --delete --exclude .env --exclude .env.host --exclude '__pycache__' --exclude '.pytest_cache' ./ "$DEPLOY_HOST:$DEPLOY_DIR/"
ssh "$DEPLOY_HOST" "cd $DEPLOY_DIR && docker compose up -d --build && docker compose ps"
