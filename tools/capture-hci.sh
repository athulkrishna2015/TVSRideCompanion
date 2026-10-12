#!/usr/bin/env bash
# Capture Bluetooth HCI traffic from a paired Android phone.
#
# Why this exists: the protocol facts in docs/PROTOCOL.md were recovered by
# decompiling the official app, which tells us what bytes it *sends* but nothing
# about what the cluster *replies*. Only a capture answers that. See
# docs/CAPTURE.md for the ordered list of questions a capture resolves.
#
# Captures are large and contain the owner's device addresses and Bluetooth
# identity. They live in captures/, which is gitignored. Never commit one.
#
# Usage:
#   tools/capture-hci.sh check              # is capture enabled? is a log there?
#   tools/capture-hci.sh enable             # print what to toggle, open the page
#   tools/capture-hci.sh pull [outfile]     # pull the newest capture
#   tools/capture-hci.sh watch [seconds]    # pull repeatedly as the log grows
#
set -uo pipefail

ADB="${ADB:-adb}"
OUT_DIR="${OUT_DIR:-captures}"
# Where Android 11+ writes it. The /data path needs root; the sdcard path is the
# one an unrooted phone can actually read.
SD_PATHS=(
  "/sdcard/Android/data/com.android.bluetooth/files/btsnoop_hci.log"
  "/sdcard/btsnoop_hci.log"
)
DATA_PATH="/data/misc/bluetooth/logs/btsnoop_hci.log"

say() { printf '%s\n' "$*"; }
die() { say "error: $*" >&2; exit 1; }

require_device() {
  "$ADB" get-state >/dev/null 2>&1 || die "no device. Is USB debugging on?"
}

# Snoop logging is a Developer Options toggle, not a plain Settings.Global key
# on every build. Report what we can actually observe rather than guessing.
snoop_state() {
  local a b
  a=$("$ADB" shell settings get global bluetooth_hci_log 2>/dev/null | tr -d '\r')
  b=$("$ADB" shell settings get global bluetooth_hci_snoop_log_mode 2>/dev/null | tr -d '\r')
  say "bluetooth_hci_log           = ${a:-unset}"
  say "bluetooth_hci_snoop_log_mode = ${b:-unset}"
  say "note: both keys set is necessary but not sufficient. The Bluetooth stack"
  say "      only honours them on some builds; the Developer options toggle"
  say "      'Enable Bluetooth HCI snoop log' is the reliable switch."
}

find_log() {
  local p
  for p in "${SD_PATHS[@]}"; do
    if "$ADB" shell "test -f '$p'" >/dev/null 2>&1; then
      printf '%s' "$p"
      return 0
    fi
  done
  # /data path usually needs root; report it as found-but-unreadable so the
  # message is actionable.
  if "$ADB" shell "test -f '$DATA_PATH'" >/dev/null 2>&1; then
    printf '%s' "$DATA_PATH"
    return 0
  fi
  return 1
}

do_check() {
  require_device
  snoop_state
  say ""
  local log
  if log=$(find_log); then
    local size
    size=$("$ADB" shell "ls -l '$log' 2>/dev/null | awk '{print \$5}'" | tr -d '\r')
    say "capture present: $log (${size:-?} bytes)"
  else
    say "no capture found on device"
    say "nothing is being recorded. Enable it, then reconnect to the scooter."
  fi
}

do_enable() {
  require_device
  # Both keys. Neither is sufficient alone and some builds honour neither, but
  # they are free to set and one of them is usually the one the stack reads.
  "$ADB" shell settings put global bluetooth_hci_log 1 >/dev/null 2>&1 || true
  "$ADB" shell settings put global bluetooth_hci_snoop_log_mode full >/dev/null 2>&1 || true
  say "set bluetooth_hci_log=1 and bluetooth_hci_snoop_log_mode=full"
  say "opening Developer options — confirm 'Enable Bluetooth HCI snoop log' is ON"
  "$ADB" shell am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null 2>&1
  say ""
  say "Then:"
  say "  1. Make sure the toggle is ON"
  say "  2. Toggle Bluetooth off and on (the log only starts on a fresh attach)"
  say "  3. Open TVS Connect and connect to the scooter"
  say "  4. Leave it connected a couple of minutes, then:"
  say "       tools/capture-hci.sh pull"
}

do_pull() {
  require_device
  local dest="${1:-$OUT_DIR/}"
  mkdir -p "$dest"
  local log
  log=$(find_log) || die "no capture on device. Run 'tools/capture-hci.sh enable' first"
  if [[ "$log" == "$DATA_PATH" ]]; then
    "$ADB" shell "ls -l '$DATA_PATH'" >/dev/null 2>&1 && ! "$ADB" shell "cat '$DATA_PATH'" >/dev/null 2>&1 \
      && die "$log needs root; use 'adb bugreport' or grant root, then retry"
  fi
  local stamp out
  stamp=$(date +%Y%m%d-%H%M%S)
  if [[ "$dest" == */ ]]; then out="${dest}btsnoop-${stamp}.btsnoop_hci.log"; else out="$dest"; fi
  say "pulling $log -> $out"
  "$ADB" pull "$log" "$out" >/dev/null || die "adb pull failed"
  say "saved: $out ($(stat -c%s "$out") bytes)"
  say ""
  say "Next: tools/decode-snoop.py '$out' | less"
}

do_watch() {
  require_device
  local secs="${1:-60}"
  say "polling every 5s for ${secs}s — reconnect to the scooter now"
  local waited=0
  while [ "$waited" -lt "$secs" ]; do
    if log=$(find_log); then
      size=$("$ADB" shell "ls -l '$log' 2>/dev/null | awk '{print \$5}'" | tr -d '\r')
      say "$(date +%H:%M:%S)  ${size:-0} bytes  $log"
    else
      say "$(date +%H:%M:%S)  no log yet"
    fi
    sleep 5
    waited=$((waited + 5))
  done
  say "done. Run: tools/capture-hci.sh pull"
}

case "${1:-check}" in
  check)  do_check ;;
  enable) do_enable ;;
  pull)   shift; do_pull "${1:-}" ;;
  watch)  shift; do_watch "${1:-60}" ;;
  *)      say "usage: $0 {check|enable|pull [out]|watch [secs]}"; exit 1 ;;
esac
