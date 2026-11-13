# Review: `sudo-Mystic/Scoot-releases`

Reviewed 2026-11-13. This is the public release feed of the closed-source Scoot
companion app. It was reviewed in full — every non-APK file in the repository —
to extract whatever is useful for this project and to record what was
deliberately left alone.

Nothing here is a substitute for hardware. It is a second source, and the
contradictions it produced are recorded rather than smoothed over.

## 1. What the repository actually is

Three unrelated things share one repository, which is why a casual look
undersells it:

1. **An OTA feed.** `stable/<version>/manifest.json` + APKs + `versions.json`.
   Boring, but it pins the build identity: the `arm64-v8a` APK for 0.5.10 hashes
   to `58d74a4d82c63e0df262d3d03b32a96db32700612f99b2da93a2a7e153140a93`, the
   file this project analysed. So the spec below and that binary describe the
   same build.
2. **A protocol specification.** `sim/docs/PROTOCOL.md` (~23 KB),
   `sim/docs/tvs-addendum.md` (~15 KB), and an executable engine in
   `sim/src/engine/frames.js`. Extracted from the Scoot app's protocol layer and
   dex-verified against TVS Connect v8.12.4.
3. **A working Jupiter cluster simulator.** A virtual cluster UI, a physics
   engine that emits real 20-byte frames, scenarios, record/replay, and a BlueZ
   GATT peripheral that makes a laptop look like a scooter over BLE.

The app source itself (`sudo-Mystic/Scoot`) is private and returns 404. The APK
is the only artifact of it; this repo is the only published description of it.

There is **no licence file**. Copies of the simulator source stay out of this
repository's history for that reason — `tools/fetch-scoot-sim.sh` reproduces
them into the gitignored `research/vendor/`.

## 2. File inventory and what each part is evidence for

| Path | Size | Use to us |
| :--- | :--- | :--- |
| `sim/docs/PROTOCOL.md` | 23 KB | Authoritative Jupiter byte maps, frame anatomy, checksum, maneuver catalogue. Adopted (see §3). |
| `sim/docs/tvs-addendum.md` | 15 KB | Independent second pass over the same dex; contradicts the Dart in three named places. Read as a tie-breaker, not as truth. |
| `sim/docs/VERIFICATION.md` | 16 KB | Independent verification of the spec against golden frames. The most valuable document in the repo: it is where the lead byte and the checksum rules are settled. |
| `sim/docs/SIM_MANUAL.md` | 18 KB | Control-deck → byte mapping (which byte each physical control sets). Used to cross-check our decoder field offsets. |
| `sim/docs/SAFETY.md` | 4 KB | What the simulator will never do. Adopted as the boundary for our bench tool. |
| `sim/docs/UI-AUDIT*.md` | 20 KB | Design/QA notes for their web panels. Not applicable to an Android Compose UI. |
| `sim/src/engine/frames.js` | 15 KB | Executable encoders/decoders. Cross-read against the prose; not vendored. |
| `sim/src/engine/engine.js`, `physics.js`, `profiles.js` | 28 KB | Ride simulation. `profiles.js` carries the 110/125 fuel split; it also contains a known divergence (§5). |
| `sim/src/engine/*.test.js`, `sim/tests/golden.test.js` | 45 KB | Golden vectors with hand-computed expectations. The numbers were reused in `tools/jupiter-bench.py --self-test`. |
| `sim/bridge/jupiter-ble-bridge.py` | 30 KB | BlueZ GATT peripheral + WebSocket relay. **Runs on this laptop.** This is the hardware-free test path (§4). |
| `sim/bridge/jupiter-ble-bridge-windows.py` | 33 KB | Same protocol over WinRT. Irrelevant here. |
| `sim/bridge/verify_bridge.py`, `smoke_local.py` | 26 KB | Bridge acceptance tests. Their PASS criteria became the bench's self-test. |
| `sim/src/ui/*.js`, `styles/`, `index.html` | ~200 KB | The virtual cluster. Useful to read the display rules; not portable to Compose. |
| `sim/src/scenarios/*.js` | 19 KB | Scenario scripts (`city-commute`, `highway-run`, `fault-injection`, `call-during-nav`, `low-fuel-warning`) and a JSONL recorder. Shape adopted for `--jsonl`. |
| `stable/*/manifest.json` | 0.7–2 KB | `releaseNotes` per version is a free, honest feature history of a competitor app (§6). |
| root `index.html`, `privacy.html`, `terms.html` | 38 KB | Marketing site. Feature claims only; no protocol content. |

## 3. What was adopted

Already implemented and documented in `docs/PROTOCOL.md` §9 and
`docs/reference/jupiter-scoot.md`:

- Jupiter inbound maps `0x10`/`0x11`/`0x18`/`0x19`/`0x6B`.
- Outbound `0x43` caller sequence, `0x50` pictogram, `0x61`/`0x62`/`0x65` media
  metadata, `0x5A 0xF1` brightness, `0x22` user id, `0x53` SMS, `0x60`
  play/pause, `0x73` calibration, `0x6B` media keys, call switch on `0x10`
  byte 12.
- Verified-open GATT gate and the reconnect backoff.

Adopted in this pass:

| Finding | Where it landed |
| :--- | :--- |
| Inbound byte 18 of `0x10` is the RPM low byte, not a checksum; inbound checksums are never gated, validation is structural | `docs/PROTOCOL.md` §9; enforced in `tools/jupiter-bench.py`'s frame builders |
| Only `0x4E` and `0xF1` lead with `0x5A`; `0x4F` and `0x73` keep `0x5B` | already true in `PacketBuilder.navigationControl`; now stated explicitly and checked by the bench |
| The bridge + bench gives end-to-end verification with no scooter | `docs/SIMULATOR.md`, `tools/jupiter-bridge.sh`, `tools/jupiter-bench.py` |
| Golden-frame numbers (42 km/h, odo raw 12345, RPM 3600, battery 126 → 12.6 V, DTE 180, FE 52/45) | `tools/jupiter-bench.py --self-test` |
| Control-deck byte map (throttle byte 10, RPM 17–18, ride mode byte 8, indicators `0x11` byte 12) | cross-checked against `JupiterFrames`; no drift found |
| Recorder JSONL shape (`{t,dir,hex}`) | `tools/jupiter-bench.py --jsonl` |

## 4. The highest-value item: a fake cluster

Before this, every BLE behaviour was "verified on the owner's Ntorq" or not at
all, and the Ntorq is out of service. The bridge is a BlueZ GATT peripheral
that advertises the real Jupiter service UUID and relays frames to a WebSocket,
so the app connects to it as if it were a scooter.

It works here: `bluetoothd` is active, the adapter supports peripheral mode,
and `dbus-next` 0.2.3 runs on Python 3.14. The earlier "Python 3.14 dbus
signature error" belonged to `ntorq-console`, not to this lane.

```bash
tools/fetch-scoot-sim.sh
tools/jupiter-bridge.sh            # fake cluster, advertising 'Jupiter-SIM'
tools/jupiter-bench.py --scenario ride
```

See `docs/SIMULATOR.md` for what this proves and, more importantly, what it
cannot.

## 5. Defects in the upstream material — do not copy these

Found by their own verification pass, and worth recording because each one would
have been easy to inherit silently:

1. **`PROTOCOL.md` §9 misstates the Ntorq battery source.** It claims `0x11`
   byte 6 × 0.1; the Dart decoder reads `0x18` byte 11 × 0.1, identical to
   Jupiter. Our Ntorq driver already uses `0x18` byte 11, so the doc is wrong,
   not us.
2. **`profiles.js` 110 fuel decode diverges from the Dart.** The sim normalises
   the low nibble; the Dart passes the whole raw byte through and sets
   `reserve = (raw & 0xF0) != 0`. Our `Capabilities.fuelLowNibble` follows the
   Dart (125 = nibble, 110 = raw), which is the dex-verified behaviour.
3. **The engine's `decodeOutbound` rejected `0x5A`-lead nav-control frames**,
   pinning a failing test until fixed. The Dart builders are unambiguous; our
   `PacketBuilder` never had this bug because it was written from the Dart
   layout.
4. **`0x53` SMS is model-gated in the real app** (U388/iQube only, never
   Jupiter). We expose `ClusterFrames.sms` but do not wire it on the Jupiter
   session.
5. **`0x22` user id is never sent automatically by Scoot**, and the cluster's
   expectation is unverified. Ours stays owner-initiated for the same reason.

## 6. Feature history: what a competitor ships

The `releaseNotes` fields are a free roadmap. Phase 2 candidates for us, in the
order they matter to a rider:

- **Trip recording and trip history** — automatic, with a resume window
  (15 min), sort chips, and a trip detail screen (distance, duration, average
  and top speed, ECO/POWER split from real cluster data, energy estimate).
- **Parked pin** — remembers where the scooter was left; clears when ignition
  comes back.
- **Navigation** — Google Maps notification bridge (we have this), plus saved
  places (Home/Work/custom), a favorites list, and a Maneuver list.
- **Calls and SMS on the cluster** — caller number/name sequence (we build it),
  reject-with-SMS, and a call log. Answering needs the default-dialer role.
- **Ride telemetry screen** — coolant, intake, battery, injection, MIL, service
  reminder, indicators.
- **Diagnostics** — DTC list with clear/describe; the cluster exposes MIL blink
  codes on `0x11`.
- **Settings that they found necessary** — update check, units, warm map style,
  voice guidance default-off, throttle-rate-limited cluster updates (battery
  and data).

Two of their lessons are worth copying outright:

- **Throttle what goes to the cluster**, not what goes to the UI. They cut API
  calls and cluster update rate to save battery; our keepalive already runs at
  2 s and nav control at 1.5 s.
- **"Cluster holds last known trip distance instead of dropping to 0."**
  Zeroing a field the cluster cannot currently know reads as data. Our rule is
  the same: show `—`, never invent a `0`.

## 7. What remains unverified after all of this

- Jupiter speed mapping and the `/10` odometer scale — PROVISIONAL in the spec
  itself, so the simulator cannot settle them.
- Fuel *bar* meaning; only the 110/125 byte split is dex-verified.
- Cluster acceptance of `0x52`, `0x53`, `0x73`, and the `0x61`/`0x62`/`0x65`
  title/artist/album order.
- Anything Ntorq-specific. The simulator is Jupiter-only.

Each of these needs the scooter, not more code. The simulator narrows the list;
it does not empty it.
