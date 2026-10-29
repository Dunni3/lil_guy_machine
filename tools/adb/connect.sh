#!/usr/bin/env bash
# Connect adb to a TV over the network.
#
# On the TV first: Settings > System > About > tap "Android TV OS build" 7x to unlock
# Developer options, then Settings > System > Developer options > enable USB debugging
# and (if present) Network debugging / Wireless debugging.
#
#   tools/adb/connect.sh 192.168.1.50                 # classic network debugging (port 5555)
#   tools/adb/connect.sh 192.168.1.50:37123           # explicit port
#   tools/adb/connect.sh --pair 192.168.1.50:41234 123456
#        Wireless debugging (Android 11+): the TV shows a pairing port + 6-digit code under
#        Wireless debugging > Pair device with pairing code. Pair once, then connect using
#        the (different) port shown on the Wireless debugging screen.
#   tools/adb/connect.sh --usb-to-tcp                 # device on USB: switch it to tcp 5555
set -euo pipefail
source "$(dirname "$0")/_common.sh"

case "${1:-}" in
  --pair)
    [[ $# -eq 3 ]] || { echo "usage: $0 --pair <ip:pair-port> <code>" >&2; exit 2; }
    adb_ pair "$2" "$3"
    echo "Paired. Now: $0 <ip:port-from-wireless-debugging-screen>"
    ;;
  --usb-to-tcp)
    adb_ tcpip 5555
    ip=$(adb_ shell ip -f inet addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | tr -d '\r')
    [[ -z "$ip" ]] && ip=$(adb_ shell ip -f inet addr show eth0 | awk '/inet /{print $2}' | cut -d/ -f1 | tr -d '\r')
    echo "Device IP: ${ip:-unknown}. Unplug USB, then: $0 $ip"
    ;;
  ""|-h|--help)
    sed -n '2,18p' "$0"; exit 0
    ;;
  *)
    target="$1"
    [[ "$target" == *:* ]] || target="$target:5555"
    adb_ connect "$target"
    echo "Accept the 'Allow USB debugging?' prompt on the TV if it appears, then re-run if it said 'unauthorized'."
    adb_ devices
    ;;
esac
