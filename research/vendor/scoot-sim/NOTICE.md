# Upstream notice — Scoot simulator

This directory is a verbatim mirror of the simulator parts of
**https://github.com/sudo-Mystic/Scoot-releases** (branch `main`), fetched on
2026-11-13 by `tools/fetch-scoot-sim.sh`:

- `sim/docs/` — protocol spec, addendum, verification report, simulator manual
- `sim/src/` — engine, frame builders/decoders, scenarios, virtual-cluster UI
- `sim/bridge/` — BlueZ and WinRT BLE bridges, bridge acceptance scripts
- `sim/tests/`, `sim/engine/`, `sim/ui/`, `sim/scenarios/` — build output and
  duplicate entry points, kept because the upstream import graph references them

The upstream repository **does not declare a licence**. It is vendored here
deliberately so the fake-cluster test path in `docs/SIMULATOR.md` is
reproducible without network access, and so the protocol evidence can be read
alongside the code that uses it. It is not linked into the Android app, not
built by CI, and not redistributed as part of any APK.

If the upstream author asks for removal, delete this directory and
`tools/fetch-scoot-sim.sh`; the app does not depend on either.

The Android app itself is a clean-room implementation written from documented
byte layouts (`docs/PROTOCOL.md`, `docs/reference/`). No upstream JavaScript or
Python is translated into Kotlin; the simulator is used as an oracle and as a
test harness, not as source.
