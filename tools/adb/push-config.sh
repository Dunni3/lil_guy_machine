#!/usr/bin/env bash
# Push the endpoint config, and the API keys it names, to the app on the device.
#
#   tools/adb/push-config.sh [config/endpoints.yaml]
#   tools/adb/push-config.sh --clear        # delete the pushed config and keys
#
# DEV ONLY. Keys are copied from your shell environment (the variables named by api_key_env)
# into secrets.env in the app's external files dir. Anyone with adb access to the TV can read
# them there. Use a low-limit key, and --clear when you're done.
set -euo pipefail
source "$(dirname "$0")/_common.sh"
require_device

DEST=/sdcard/Android/data/dev.lilguy.machine/files

if [[ "${1:-}" == "--clear" ]]; then
  adb_ shell rm -f "$DEST/endpoints.yaml" "$DEST/secrets.env"
  echo "Removed endpoints.yaml and secrets.env from $DEST"
  exit 0
fi

CONFIG="${1:-config/endpoints.yaml}"
[[ -f "$CONFIG" ]] || { echo "No $CONFIG. Copy config/endpoints.example.yaml to it and edit." >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
umask 077
: > "$tmp/secrets.env"
for var in $(grep -E '^[[:space:]]*api_key_env:' "$CONFIG" | sed -E 's/.*api_key_env:[[:space:]]*//; s/[[:space:]]*(#.*)?$//; s/["'"'"']//g' | sort -u); do
  if [[ -n "${!var:-}" ]]; then
    echo "$var=${!var}" >> "$tmp/secrets.env"
    echo "key: $var (from your shell)"
  else
    echo "warning: $var is not set here, so endpoints that use it won't work on the device" >&2
  fi
done

adb_ shell mkdir -p "$DEST"
adb_ push "$CONFIG" "$DEST/endpoints.yaml" >/dev/null
adb_ push "$tmp/secrets.env" "$DEST/secrets.env" >/dev/null
echo "Pushed $CONFIG and secrets.env to $DEST. Restart the app: make tv-run [ENDPOINT=<name>]"
