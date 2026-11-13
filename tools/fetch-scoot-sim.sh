#!/usr/bin/env bash
# Mirror the Scoot simulator sources into research/vendor/scoot-sim/ and set up
# the Python bits the BLE bridge needs.
#
# Why local-only: the upstream repository (sudo-Mystic/Scoot-releases) has no
# licence file, so the simulator code is kept out of this repository's history
# exactly like research/vendor/ generally (see .gitignore). This script is the
# reproducible way to get it back on a fresh machine.
#
# Usage: tools/fetch-scoot-sim.sh [--bridge-only]
set -euo pipefail

REPO="sudo-Mystic/Scoot-releases"
BRANCH="main"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/research/vendor/scoot-sim"
VENV="$DEST/.venv"

bridge_only=0
[[ "${1:-}" == "--bridge-only" ]] && bridge_only=1

if [[ $bridge_only -eq 0 ]]; then
  echo "==> listing $REPO@$BRANCH"
  mapfile -t PATHS < <(curl -sfL \
    "https://api.github.com/repos/$REPO/git/trees/$BRANCH?recursive=1" |
    python3 -c '
import json, sys
tree = json.load(sys.stdin)["tree"]
for f in tree:
    p = f["path"]
    if f["type"] != "blob" or not p.startswith("sim/"):
        continue
    if p.startswith("sim/assets/"):   # build output, duplicates sim/src
        continue
    if p.endswith((".js", ".py", ".json", ".md", ".css", ".html", ".sh", ".txt")):
        print(p)
')

  echo "==> ${#PATHS[@]} files -> ${DEST#"$ROOT"/}"
  for p in "${PATHS[@]}"; do
    out="$DEST/${p#sim/}"
    mkdir -p "$(dirname "$out")"
    curl -sfL "https://raw.githubusercontent.com/$REPO/$BRANCH/$p" -o "$out" ||
      echo "    failed: $p"
  done
fi

echo "==> python venv"
if ! command -v uv >/dev/null; then
  echo "uv is not installed; install it with: yay -S uv" >&2
  exit 1
fi
uv venv -q --python 3 "$VENV"
uv pip install -q --python "$VENV/bin/python" -r "$DEST/bridge/requirements.txt"

echo
echo "done. Start the fake cluster with:"
echo "  tools/jupiter-bridge.sh"
echo "Exercise the app with:"
echo "  tools/jupiter-bench.py --self-test"
echo "  tools/jupiter-bench.py --scenario ride"
