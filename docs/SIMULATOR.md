# Testing with no scooter: the Jupiter simulator

The rider's Ntorq is out of service and no Jupiter is available, so on-hardware
verification is impossible right now. The Scoot release feed ships a working
cluster simulator for the Jupiter family, and it runs on this laptop. That
turns "unverified until the scooter comes back" into "verified against a
documented implementation of the same byte layout" for everything except the
things only real hardware can answer.

**It is evidence about the app, not about the scooter.** A simulator passing
does not mean the real cluster accepts a frame. Anything marked UNVERIFIED in
`docs/PROTOCOL.md` stays unverified.

## What it is

```
phone app  --BLE GATT-->  jupiter-ble-bridge.py  --WebSocket-->  our bench
                          (fake cluster radio)                    (frames + decode)
```

The bridge is a BlueZ GATT peripheral. It advertises the real Jupiter service
UUID (`5456534d-…-454e544f5251`) with the real write characteristic
(`00005352-…`) and notify characteristic (`00005354-…`), and moves raw 20-byte
frames between BLE and a WebSocket on port 8765. **The app needs no changes**:
it scans, connects and talks exactly as it would to a scooter.

It emulates the display/telemetry cluster only. It does not answer the
`0x9A`/`0xF2` auth challenge and has no lock, immobilizer, throttle or brake
path — see the upstream `SAFETY.md`, mirrored under
`research/vendor/scoot-sim/docs/`.

## Files

| Path | Committed | What |
| :--- | :--- | :--- |
| `tools/fetch-scoot-sim.sh` | yes | Mirrors the upstream sim into `research/vendor/scoot-sim/` and builds its venv |
| `tools/jupiter-bridge.sh` | yes | Runs the bridge (fake cluster radio) |
| `tools/jupiter-bench.py` | yes | Emits scripted telemetry, decodes what the app sends back |
| `research/vendor/scoot-sim/` | **no** | Upstream simulator source: engine, virtual cluster UI, bridge, spec |

`research/vendor/` is gitignored because the upstream repository has no licence
file. The fetch script is the reproducible way to restore it.

## Run it

```bash
tools/fetch-scoot-sim.sh          # once: mirror + venv
tools/jupiter-bench.py --self-test   # frame builders, no bridge needed

# terminal 1: fake cluster
tools/jupiter-bridge.sh
# expect: GATT application registered / advertising as 'Jupiter-SIM'

# terminal 2: telemetry
tools/jupiter-bench.py --scenario ride
```

Then install the app and connect:

```bash
gh run list --limit 1
gh run download <run-id> -n app-debug -D /tmp/apk
android run --apks=/tmp/apk/app-debug.apk
```

On the Scan screen the fake cluster appears as **`Jupiter-SIM`**. The app maps
that name to `JUPITER_125` (`VehicleModel.detect`), so the Jupiter driver is
selected without touching a setting. It advertises the service UUID, so it is
also flagged SmartXonnect with `Only SmartXonnect` on.

## What it verifies

- Service discovery, the verified-open GATT gate, and subscribe-before-notify.
- Decode of `0x10` (speed, odometer `/10`, fuel gate, trip, RPM, ignition,
  ECO/POWER), `0x11` (service reminder, MIL, indicators), `0x18` (battery
  `raw × 0.1`, coolant, intake), `0x19` (average/instant FE, DTE).
- The outbound frames, printed and structurally checked by the bench: `0x4A`
  keepalive (clock, find-me at byte 17), `0x5A 0x4E` navigation control,
  `0x4F` navigation text, `0x4C`/`0x63` text rows, `0x43` caller sequence,
  `0x52` rider name, `0x5A 0xF1` brightness, `0x6B` media keys.
- The bench flags a wrong lead byte, a wrong end byte, a non-20-byte frame, or
  a checksum that does not match `255 - (sum(bytes[0..17]) % 256)`.

Scenarios: `idle` (key off, ignition byte 64), `ride` (0→42 km/h sweep, then
coast), `fuel-low` (3 bars, short DTE), `fault` (service reminder + MIL blink
code 3).

## What it cannot verify

- Cluster acceptance of `0x52`, `0x53`, `0x73`.
- The speed byte and odometer scale, which are PROVISIONAL in the spec itself.
- Fuel *bar* meaning; only the 110/125 nibble split is dex-verified.
- Anything Ntorq. The simulator is Jupiter-only; the Ntorq driver keeps its
  own evidence base.

## Troubleshooting

- `cannot reach the bridge`: start `tools/jupiter-bridge.sh` first.
- `Address already in use` on 8765: an older bridge is still running. Kill it by
  port, not by pattern: `fuser -k 8765/tcp`. (`pkill -f jupiter-ble-bridge`
  kills the shell running it, because that shell's own command line contains the
  pattern — same trap as `pkill -f GradleDaemon` in `AGENTS.md`.)
- `adapter hci0 not found`: `systemctl is-active bluetooth`, `bluetoothctl list`.
- Only one peripheral can advertise "TVS"-ish clusters at a time. Stop
  `ntorq-console` before starting the bridge, or the phone sees two clusters.
- Python 3.14 + `dbus-next` works here; the earlier `dbus`/signature failure was
  `ntorq-console`'s own code, not the library.
