#!/usr/bin/env bash
# Renders stills of guy.html (its ?shot= mode) into gallery/<set>/ for gallery/index.html: every
# style x face x body colour at the three-quarter camera, plus a front/side/back turnaround per style.
#
#   tools/workshop/shoot.sh [set]        # default set: v1
#   ONLY=paper-happy tools/workshop/shoot.sh   # just the stills whose names match
#
# Needs Google Chrome (headless, software WebGL) and the network for three.js. Uses throwaway
# profiles, never your own. Writes JPEGs via sips (macOS).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
set_name="${1:-v1}"
out="$here/gallery/$set_name"
chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
profiles="$(mktemp -d)"
trap 'rm -rf "$profiles"' EXIT
mkdir -p "$out"

# Each set's designs: "<id>|<query for that design>". Stills are <id>-<face>-<colour> and <id>-turn-<view>.
case "$set_name" in
  v1)
    designs=("crayon|shot=crayon&shape=ball&topper=sprout" "paper|shot=paper")
    faces="neutral happy sad tired thinking asleep"; colours="apricot rose lavender sky mint butter" ;;
  v2)
    designs=()
    for s in ball mochi bean gumdrop onigiri tater; do designs+=("$s|shot=crayon&shape=$s&topper=none"); done
    for t in curl ears beanie; do designs+=("mochi_$t|shot=crayon&shape=mochi&topper=$t"); done
    faces="neutral happy sad asleep"; colours="apricot lavender mint" ;;
  v3)
    designs=("tater|shot=crayon&shape=tater&topper=none&palette=butter")
    faces="neutral happy sad tired thinking asleep"; colours="butter" ;;
  *) echo "unknown set: $set_name" >&2; exit 1 ;;
esac

jobs=()
for d in "${designs[@]}"; do
  id="${d%%|*}" q="${d#*|}"
  for f in $faces; do
    for p in $colours; do jobs+=("$id-$f-$p|$q&face=$f&palette=$p"); done
  done
  jobs+=("$id-turn-front|$q&cam=front&yaw=0" "$id-turn-side|$q&cam=front&yaw=1.5708" "$id-turn-back|$q&cam=front&yaw=3.1416")
done

shoot() {
  local name="${1%%|*}" query="${1#*|}"
  [[ -n "${ONLY:-}" && "$name" != *"$ONLY"* ]] && return 0
  # Under load Chrome sometimes shoots before the first frame; a blank cream still is tiny, so retry it.
  for attempt in 1 2 3; do
    "$chrome" --headless=new --user-data-dir="$profiles/$name" --use-angle=swiftshader --enable-unsafe-swiftshader \
      --hide-scrollbars --window-size=640,640 --virtual-time-budget=10000 \
      --screenshot="$out/$name.png" "file://$here/guy.html?$query" >/dev/null 2>&1
    sips -s format jpeg -s formatOptions 84 "$out/$name.png" --out "$out/$name.jpg" >/dev/null
    (( $(wc -c < "$out/$name.jpg") > 15000 )) && break
  done
  rm "$out/$name.png"
  echo "$name"
}
export -f shoot
export chrome profiles out here ONLY

printf '%s\n' "${jobs[@]}" | xargs -P 4 -I{} bash -c 'shoot "$1"' _ {}
