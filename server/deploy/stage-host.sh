#!/usr/bin/env bash
# Builds the sim host and stages what its image copies into server/host/dist/ (gitignored): the
# JVM app, and config/endpoints.yaml when there is one. That file names models and which
# environment variables hold their keys, never the keys. Without it the host runs the scripted policy.
set -euo pipefail
cd "$(dirname "$0")/../.."
./gradlew --console=plain -q :host:installDist
rsync -a --delete host/build/install/host/ server/host/dist/
if [[ -f config/endpoints.yaml ]]; then
  mkdir -p server/host/dist/config
  cp config/endpoints.yaml server/host/dist/config/endpoints.yaml
fi
