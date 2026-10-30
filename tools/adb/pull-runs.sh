#!/usr/bin/env bash
# Copy the app's run logs from the device into runs/tv/ without disturbing anything already there.
#
#   tools/adb/pull-runs.sh [--from DIR] [DEST]      # DEST defaults to runs/tv
#
# Run directories on the device are named app-<scenario>-<timestamp>, so a finished run never
# collides with another. The same file can still show up twice: once while its run was going and
# again later. Each file from the device is dealt with on its own:
#
#   not here yet                   copied
#   identical to the one here      skipped
#   the one here is its start      extended in place (logs only grow, so nothing is lost)
#   it is the start of the one here  skipped (you have more than the device does)
#   anything else                  both kept: the new one is saved as <name>.pulled-<time>
#
# Nothing here is ever overwritten or deleted, and nothing is removed from the device.
# --from DIR reads a local directory instead of the device (for trying this out).
set -euo pipefail
source "$(dirname "$0")/_common.sh"

DEVICE_RUNS=/sdcard/Android/data/dev.lilguy.machine/files/runs
FROM=""
DEST=runs/tv
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) FROM="${2:?--from needs a directory}"; shift 2 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) DEST="$1"; shift ;;
  esac
done

stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT

if [[ -n "$FROM" ]]; then
  [[ -d "$FROM" ]] || { echo "no directory $FROM" >&2; exit 1; }
  cp -R "$FROM/." "$stage/"
else
  require_device
  adb_ pull "$DEVICE_RUNS/." "$stage/" >/dev/null || { echo "nothing to pull from $DEVICE_RUNS (has the app written any runs?)" >&2; exit 1; }
fi

size() { wc -c < "$1" | tr -d ' '; }

# True when the file $1 is exactly the first bytes of the file $2.
is_start_of() {
  local n
  n=$(size "$1")
  [[ $n -le $(size "$2") ]] && head -c "$n" "$2" | cmp -s - "$1"
}

stamp=$(date +%Y%m%d-%H%M%S)
added=0 grown=0 same=0 kept=0
runs_added=""

while IFS= read -r -d '' file; do
  rel=${file#"$stage"/}
  out="$DEST/$rel"
  mkdir -p "$(dirname "$out")"
  if [[ ! -e "$out" ]]; then
    cp -p "$file" "$out"
    added=$((added + 1))
    runs_added="$runs_added${rel%%/*}"$'\n'
  elif cmp -s "$file" "$out"; then
    same=$((same + 1))
  elif is_start_of "$out" "$file"; then
    cp -p "$file" "$out"
    grown=$((grown + 1))
  elif is_start_of "$file" "$out"; then
    same=$((same + 1)) # what's here already has everything the device has, and more
  else
    # Diverged: keep both, unless an earlier pull already saved this exact version.
    seen=0
    for earlier in "$out".pulled-*; do
      [[ -e "$earlier" ]] && cmp -s "$file" "$earlier" && seen=1
    done
    if [[ $seen == 0 ]]; then
      cp -p "$file" "$out.pulled-$stamp"
      echo "differs from what's here, kept both: $rel -> $rel.pulled-$stamp"
      kept=$((kept + 1))
    else
      same=$((same + 1))
    fi
  fi
done < <(find "$stage" -type f -print0)

new_runs=$(printf '%s' "$runs_added" | sort -u | sed '/^$/d')
[[ -n "$new_runs" ]] && echo "new in $DEST:" && echo "$new_runs" | sed 's/^/  /'
echo "pulled into $DEST: $added new files, $grown extended, $same unchanged, $kept kept alongside"
