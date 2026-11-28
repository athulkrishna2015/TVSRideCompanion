# Testing with no scooter: simulators

The rider's Ntorq is out of service and no Jupiter is available, so on-hardware
verification is impossible right now. Two software paths stand in:

| | Jupiter | Ntorq |
| :--- | :--- | :--- |
| Tool | `tools/jupiter-bridge.sh` + `tools/jupiter-bench.py` (vendored Scoot sim) | `tools/ntorq-bridge.py` (clean-room, in-repo) |
| Targets | Scoot/Jupiter byte layout & checksum rules | **This app's** Ntorq driver (`NtorqDriver` / `Keepalive`) and the `docs/PROTOCOL.md` capture |
| BLE name / discovery | advertises `Jupiter-SIM` + service UUID | advertises `TVSBT800450` with **no service UUID** by default (real Ntorq behaviour) |
| WS port | 8765 | 8766 |

**Both are evidence about the app, not about the scooter.** A simulator passing
does not mean the real cluster accepts a frame. Anything marked UNVERIFIED in
`docs/PROTOCOL.md` stays unverified, and cluster acceptance of values only a
scooter can confirm (scales, layouts, calibration responses) remains open.

## Jupiter simulator (Scoot, vendored)

The Scoot release feed ships a working cluster simulator for the Jupiter family.
It turns "unverified until the scooter comes back" into "verified against a
documented implementation of the same byte layout" for the Jupiter side.

### What it is

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

## Verified on 2026-11-13 (Galaxy Tab A7 Lite, no scooter)

The whole chain ran against the fake cluster: bridge advertising `Jupiter-SIM`,
bench on `--scenario ride`, app freshly installed from CI.

| Observed | Frame | Bench value | Phone |
| :--- | :--- | :--- | :--- |
| Speed | `0x10` byte 2 | 42 | 42 (frame `5A 10 2A …`) |
| Odometer | `0x10` bytes 3-5, `/10` | 1234.5 km | 1234.8 km, growing |
| Fuel | `0x10` byte 6 = `0x35`, 125 nibble | 5 bars | "5 of 6 bars" |
| Trip | `0x10` bytes 14-16, `/10` | 0.1 km | "This connection 0.2" |
| RPM | `0x10` bytes 17-18 | 3570 | 2890/3570, live |
| Battery | `0x18` byte 11 = `0x7E` | 12.6 V | no UI field yet |
| Coolant / intake | `0x18` bytes 8 / 7 | 87 / 35 (+40) | no UI field yet |
| DTE | `0x19` bytes 11-12 | 180 km | no UI field yet |
| Keepalive | `0x4A`, checksum at 18 | — | sent every 2 s, checksum valid |

Two bugs the bench found immediately, both fixed:

1. **Discovery was issued before the MTU exchange finished**, so
   `onServicesDiscovered` never fired and the link sat at "connected" with no
   write characteristic. The Ntorq never exposed this; the bridge did.
2. **The Jupiter keepalive had no checksum** at byte 18. That is right for the
   Ntorq, whose official builder leaves it zero, but the Jupiter spec checksums
   every outbound frame. It is now applied in `JupiterDriver.buildPing` only.

Screenshots: `docs/reference/images/sim-scan-found.png`,
`sim-model-picker.png`, `sim-overview-verified.png`.

## What the Jupiter simulator cannot verify

- Cluster acceptance of `0x52`, `0x53`, `0x73`.
- The speed byte and odometer scale, which are PROVISIONAL in the spec itself.
- Fuel *bar* meaning; only the 110/125 nibble split is dex-verified.
- Ntorq hardware behaviour (see the Ntorq simulator below for app-level Ntorq
  checks against this repo's documented layouts).

## Ntorq simulator (`tools/ntorq-bridge.py`)

A clean-room BLE+WebSocket bridge that emulates an Ntorq 125 SmartXonnect
cluster for **this app**. It is not in `research/vendor/scoot-sim/` (that
directory stays a verbatim upstream mirror) and it does not depend on the
vendored Scoot engine.

```
phone app  --BLE GATT-->  ntorq-bridge.py  --WebSocket-->  controller/script
                          (fake Ntorq radio)                    (optional)
```

### Fidelity

- **Discovery:** advertises as `TVSBT800450` with **no service UUID** by default,
  matching the measured Ntorq behaviour (`docs/PROTOCOL.md` §4b) — the app must
  find it by name. `--advertise-uuid` is an unfaithful convenience that lets the
  "Only SmartXonnect" filter see it.
- **GATT / framing:** SmartXonnect service `5456534D-…`, write `…5352`, notify
  `…5354`; 20-byte frames, `0x5A` cluster→phone / `0x5B` phone→cluster, `0xFF`
  end byte.
- **Inbound telemetry** (gated on ignition): `0x10` SM1, `0x11` SM2, `0x18` SM4
  built to `NtorqDriver`'s field tables (speed, BE odometer×0.1, fuel bars,
  avg/top/throttle, byte-12 ignition 128/64, BE trip-F×0.1, BE rpm, battery×0.1 V,
  run time, etc.). Emits the **captured `0x16` byte pattern** statically (layout
  unknown) and six `0x42` identity frames about 2 s apart, as observed in the
  preserved session logs. A burst of nine all-zero connect frames precedes
  telemetry. Only the first 16 bytes of the captured `0x16` pattern were
  observed; the tail is filler, not evidence. `0x19` (SM3) is off by
  default (`--with-19`) — the real cluster never sent it. Minimal physics drives
  speed/rpm/temps/odo; fuel bars 0..6.
- **Outbound decode** mirrors the app's own builders byte-for-byte: `0x4A`
  keepalive (`Keepalive.build`: clock, packed signal/battery, missed calls,
  date, find-my flag), `0x5A 0x4E` nav control (BE distance/ETA/remaining trip,
  pictogram/rows/status), `0x5B 0x4F`/`0x50` nav text, `0x5B 0x4C`/`0x63` text
  rows, `0x5B 0x52` rider name, `0x5B 0x43` caller. Decoded app writes are
  echoed to the WebSocket as `{"t":"app",...}`.
- **Safety boundary:** auth challenge-response frames (`0xF1`/`0xF2`) are
  detected and dropped, never answered. Calibration writes (`0x37`) are logged
  only, never answered. No lock/unlock, immobilizer, throttle, or brake path.

### Run it

```bash
PY=research/vendor/scoot-sim/.venv/bin/python   # provides websockets + dbus-next

# offline layout checks (no BLE, no WS server needed)
$PY tools/ntorq-bridge.py --selftest

# BLE fake Ntorq cluster (BlueZ running; bluetooth group or sudo)
sudo $PY tools/ntorq-bridge.py --ignition --cruise 25 --fuel 4

# decoder/controller only (no BLE)
$PY tools/ntorq-bridge.py --ws-only --ignition
```

With the bridge running and ignition on, scan in the app (default “Only
SmartXonnect” off) for **`TVSBT800450`** and connect. The WS controller listens
on `ws://127.0.0.1:8766` by default: send `{"t":"ctl","ignition":true,"cruise":40,"fuel":5,"odo":123.4,"resetTrip":true,"music":3}` to drive state; binary 20-byte
messages are injected straight to the phone.

Key flags: `--name`, `--advertise-uuid`, `--ignition`, `--cruise KPH`,
`--fuel 0..6`, `--odo KM`, `--with-19`, `--no-16`, `--port`, `--ws-only`,
`--selftest`.

### What it verifies / cannot verify

- Verifies (app-level): Ntorq GATT discovery by name, connect/notify flow, decode
  of `0x10`/`0x11`/`0x18` and tolerant handling of `0x16`/`0x42`, encoding/decode
  of keepalive and nav/caller/text/rider frames against this repo's builders and
  field tables.
- Cannot verify: real-cluster acceptance of any value or layout (speed byte,
  odometer scale, fuel bar meaning, `0x16`/idx 9-10 semantics, nav row
  wrap/scroll, caller/SMS LED behaviour), or anything that depends on Ntorq
  hardware/firmware.

## Local patch to the vendored bridge

`research/vendor/scoot-sim/bridge/jupiter-ble-bridge.py` carries one change
from upstream, marked in place: the link is considered up when the notify
characteristic is subscribed, instead of requiring a separately tracked
`Connected` signal as well.

Upstream's version reported `{"ble": false}` on this BlueZ version even while
frames flowed, because the `Connected: true` PropertiesChanged for a
peripheral-mode connection arrives before the GATT path is usable and the
bridge never adds the central to its set. Frames were never blocked by it —
the bell was just wrong — but `/health` and the stats line were unreadable
during a test.

A disconnect now also clears the subscriber count, so the link cannot stay
"up" after the phone walks away.

## Troubleshooting

- `cannot reach the bridge` / no WS client: start the bridge first
  (`tools/jupiter-bridge.sh` on 8765, or `tools/ntorq-bridge.py` on 8766).
- `Address already in use` on 8765/8766: an older bridge is still running. Kill
  it by port, not by pattern: `fuser -k 8765/tcp` / `fuser -k 8766/tcp`.
  (`pkill -f jupiter-ble-bridge` kills the shell running it, because that
  shell's own command line contains the pattern — same trap as
  `pkill -f GradleDaemon` in `AGENTS.md`.)
- `adapter hci0 not found` / BLE setup fails: `systemctl is-active bluetooth`,
  `bluetoothctl list`, and run with the `bluetooth` group or `sudo`.
  `tools/ntorq-bridge.py` needs `websockets` + `dbus-next`
  (`research/vendor/scoot-sim/.venv/bin/python` has them once the sim is fetched).
- Only one peripheral can advertise "TVS"-ish clusters at a time. Stop
  `ntorq-console` or the other bridge before starting one, or the phone sees two
  clusters.
- Python 3.14 + `dbus-next` works here; the earlier `dbus`/signature failure was
  `ntorq-console`'s own code, not the library.
