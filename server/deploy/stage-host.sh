#!/usr/bin/env bash
# Builds the web renderer and the sim host, whose jar carries it, and stages what the image copies
# into server/host/dist/ (gitignored): the JVM app, git-version, and config/endpoints.yaml when there is one. That file names models and which
# environment variables hold their keys, never the keys. Without it the host runs the scripted policy.
# git-version is the commit this was built from and whether the checkout had changes, since the image has no git;
# the host stamps its runs with it.
set -euo pipefail
cd "$(dirname "$0")/../.."
make --no-print-directory web-build
./gradlew --console=plain -q :host:installDist
rsync -a --delete host/build/install/host/ server/host/dist/
{ git rev-parse HEAD; if [[ -n "$(git status --porcelain)" ]]; then echo dirty; else echo clean; fi; } > server/host/dist/git-version
if [[ -f config/endpoints.yaml ]]; then
  mkdir -p server/host/dist/config
  cp config/endpoints.yaml server/host/dist/config/endpoints.yaml
fi
