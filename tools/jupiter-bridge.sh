#!/usr/bin/env bash
# Run the Jupiter BLE bridge: this laptop becomes a fake Jupiter cluster that
# advertises the real service UUID, so the app connects to it exactly as it
# would to a scooter.
#
# Display/telemetry only. The upstream bridge never answers the 0x9A/0xF2 auth
# challenge and has no lock, immobilizer, throttle or brake path.
#
# Usage: tools/jupiter-bridge.sh [--adapter hci0] [--no-ble]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/research/vendor/scoot-sim"
BRIDGE="$DEST/bridge/jupiter-ble-bridge.py"
PY="$DEST/.venv/bin/python"

if [[ ! -f "$BRIDGE" || ! -x "$PY" ]]; then
  echo "simulator not fetched yet; running tools/fetch-scoot-sim.sh" >&2
  "$ROOT/tools/fetch-scoot-sim.sh"
fi

# BlueZ is the system service, not ours: report it rather than failing opaquely.
if ! systemctl is-active --quiet bluetooth; then
  echo "bluetoothd is not running; start it with: sudo systemctl start bluetooth" >&2
  exit 1
fi

exec "$PY" "$BRIDGE" "$@"
