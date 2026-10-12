#!/usr/bin/env bash
# Wait for the scooter to appear on Bluetooth, then capture automatically.
#
# Why this exists: on Android 15 the snoop log is written to
# /data/misc/bluetooth/logs/, which adb shell cannot read without root. The only
# no-root route is `adb bugreport`, and that takes 2-5 minutes. If you start it
# by hand you spend those minutes watching a progress bar. This waits for the
# thing you actually have to do — open TVS Connect and connect — and starts the
# dump the instant it lands, without a round trip.
#
# Usage:
#   tools/auto-capture.sh [timeout_seconds]
#
# Then open TVS Connect on the phone and connect to the scooter. Everything else
# happens by itself.
set -uo pipefail

ADB="${ADB:-adb}"
TIMEOUT="${1:-1800}"
OUT_DIR="${OUT_DIR:-captures}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAMP=$(date +%Y%m%d-%H%M%S)

say() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# Signals that a BLE peripheral is attached. Any one is enough; a bonded-device
# count increase is the strongest, the others are fallbacks for builds that
# report an active-but-unbonded link.
baseline_bonded=$("$ADB" shell settings get secure bluetooth_bonded_devices 2>/dev/null \
  | tr -d '\r' | tr ';' '\n' | grep -c '[A-Fa-f0-9]' || echo 0)
say "baseline bonded devices: $baseline_bonded"

say "waiting up to ${TIMEOUT}s — open TVS Connect and connect to the scooter"
waited=0
connected=0
while [ "$waited" -lt "$TIMEOUT" ]; do
  now_bonded=$("$ADB" shell settings get secure bluetooth_bonded_devices 2>/dev/null \
    | tr -d '\r' | tr ';' '\n' | grep -c '[A-Fa-f0-9]' || echo 0)

  # A TVS-named peripheral showing up at all is a strong signal.
  if "$ADB" shell dumpsys bluetooth_manager 2>/dev/null \
       | grep -qiE 'smartxonnect|tvs[^a-z]|ntorq|jupiter'; then
    connected=1
    say "detected a TVS/SmartXonnect peripheral"
    break
  fi

  if [ "${now_bonded:-0}" -gt "${baseline_bonded:-0}" ]; then
    connected=1
    say "bonded count rose $baseline_bonded -> $now_bonded (new device bonded)"
    break
  fi

  sleep 5
  waited=$((waited + 5))
done

if [ "$connected" -ne 1 ]; then
  say "no scooter detected within ${TIMEOUT}s — nothing captured"
  say "if you did connect, run: tools/capture-hci.sh pull   (via bugreport)"
  exit 1
fi

say "letting traffic flow for 20s so the capture has substance..."
sleep 20

mkdir -p "$REPO/$OUT_DIR"
zip="$REPO/$OUT_DIR/bugreport-$STAMP.zip"

# -no-stream avoids the interactive progress meter, which hangs when stdin is
# not a tty. Consent prompts are per-device and cannot be suppressed by adb.
say "running adb bugreport (2-5 min) -> $zip"
if ! "$ADB" bugreport "$zip" >/dev/null 2>&1; then
  say "bugreport failed. On the phone, tap 'Share' on any consent dialog, then retry:"
  say "  adb bugreport $zip"
  exit 1
fi
say "bugreport done ($(du -h "$zip" | cut -f1))"

# The snoop log's name and path vary by build; find it rather than assume.
say "locating the snoop log inside the archive..."
member=$(unzip -l "$zip" 2>/dev/null \
  | awk '{print $4}' \
  | grep -iE 'btsnoop_hci\.log|snoop.*\.log' | head -1)

if [ -z "$member" ]; then
  say "no btsnoop_hci.log in the archive. Candidates:"
  unzip -l "$zip" 2>/dev/null | awk '{print $4}' | grep -iE 'bluetooth' | head -20
  say "falling back to full extract for manual inspection"
  unzip -o -q "$zip" -d "$REPO/$OUT_DIR/bugreport-$STAMP/"
  exit 2
fi

out="$REPO/$OUT_DIR/btsnoop-$STAMP.btsnoop_hci.log"
unzip -o -q -j "$zip" "$member" -d "$OUT_DIR" 2>/dev/null
mv "$OUT_DIR/$(basename "$member")" "$out" 2>/dev/null || out="$REPO/$OUT_DIR/$member"

say "saved: $out ($(du -h "$out" | cut -f1))"
say ""
say "=== decoding ==="
python3 "$REPO/tools/decode-snoop.py" "$out" --frames 2>&1 | tail -40
