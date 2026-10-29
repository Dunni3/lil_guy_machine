# Shared helpers for tools/adb/*.sh. Source, don't run.
# Target a specific device with ANDROID_SERIAL=<serial> (adb honors it natively).
# Adapted from smarter-tv's tools/adb.

ADB="${ADB:-$(command -v adb || echo "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb")}"

adb_() { "$ADB" "$@"; }

require_device() {
  local n
  n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  if [[ "$n" == "0" ]]; then
    echo "No device connected. Run tools/adb/connect.sh <tv-ip> (or start an emulator)." >&2
    exit 1
  fi
  if [[ "$n" != "1" && -z "${ANDROID_SERIAL:-}" ]]; then
    echo "Multiple devices connected; set ANDROID_SERIAL. Devices:" >&2
    "$ADB" devices >&2
    exit 1
  fi
}
