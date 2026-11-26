#!/usr/bin/env bash
# Capture a clean on-device log for a hardware session.
#
# Usage: tools/capture-session.sh [output.log]
#
# Clears logcat, restarts the app so the session starts at a known point, then
# waits for you to connect/ride. On return it pulls the app's own log, which
# captures every BLE frame in both directions (see dev.tvsopen.log.Log).
#
# The pulled file is PRIVATE: it contains the cluster MAC and, while maps is
# navigating, maneuver text with real street names. Redact those before
# pasting an excerpt into an issue or commit. Never `git add` the raw file.
set -euo pipefail

OUT="${1:-$HOME/ntorq-capture/device/session-$(date +%Y%m%d-%H%M%S).log}"
ADB="${ADB:-adb}"

"$ADB" get-state >/dev/null
"$ADB" shell am force-stop dev.tvsopen
"$ADB" logcat -c
"$ADB" shell am start -n dev.tvsopen/.ui.MainActivity >/dev/null

echo "App restarted. Turn ignition on, connect, ride, then press Enter."
read -r -t 3600 _ || true

mkdir -p "$(dirname "$OUT")"
"$ADB" exec-out run-as dev.tvsopen cat files/logs/tvsride.log > "$OUT"
echo "Saved $OUT"
echo "Remember: redact street names, distances and the MAC before sharing."
