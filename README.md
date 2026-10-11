# TVSRide

A third-party companion app for **TVS SmartXonnect** vehicles — Ntorq, Jupiter,
Apache, Raider, iQube. Speaks the instrument cluster's Bluetooth LE protocol
directly and mirrors turn-by-turn navigation from **whatever maps app you
already use**, with **no TVS account and no network access at all**.

```
Google Maps / MapMyIndia / Organic Maps / Waze
        │  (notification, read-only)
        ▼
  NotificationListenerService  ──►  pictogram + text  ──►  BLE  ──►  cluster
```

The manifest has **no `INTERNET` permission**. That is deliberate: "works
without logging in" is enforced by the build, not promised in a README.

---

## Status

| Area | State |
| :--- | :--- |
| Ntorq inbound telemetry | **8 frame layouts transcribed** from the official APK |
| Pictogram table | **transcribed** from the official app |
| GATT / transport | implemented |
| Ntorq navigation HUD | **unverified — not yet confirmed on hardware** |
| Jupiter / Apache / Raider / iQube | connect + stream raw frames; drivers not written |
| APK analysis | complete for the protocol packages |

Nothing below "unverified" has been run against a scooter yet. See
[docs/CAPTURE.md](docs/CAPTURE.md).

---

## Universal by architecture, Ntorq-verified by hardware

The official app splits its protocol one package per model
(`protocol/ntorq`, `protocol/jupiter`, `protocol/apache`, `protocol/raider`,
`protocol/u388`, …). We mirror that:

```
protocol/
  Constants.kt        frame ids, GATT UUIDs, checksum
  VehicleModel.kt     model registry + capability flags
  ProtocolDriver.kt   the per-model interface + generic fallback
  NtorqDriver.kt      Ntorq: transcribed frame layouts
  PacketBuilder.kt    outbound frames
  Pictogram.kt        maneuver table + text-keyword fallback
transport/
  ScooterBleService.kt   GATT, write mutex, frame stream
  RideService.kt         foreground service
nav/
  MapsNotificationListener.kt   any maps app → NavInstruction
  NavigationBridge.kt           NavInstruction → cluster frames
```

A model with no driver still **connects and streams raw frames** — that is how a
new vehicle gets characterised without writing a driver first.

Adding a model means writing one `ProtocolDriver`. The transport does not change.

---

## What it does

- **Navigation on the cluster** from any maps app, no account
- **Find my scooter** — injects the flag into the keepalive packet
- **Live telemetry** — speed, odometer, fuel, trip, RPM, economy, range
- **Button gestures** — the cluster button is remappable (planned)
- **No cloud** — nothing leaves the phone

---

## Build

```bash
cd app && ../gradlew assembleDebug --no-daemon --max-workers=2
```

`gradle.properties` caps workers at 2 and disables the daemon, per
[AGENTS.md](AGENTS.md).

---

## Documentation

| File | Contents |
| :--- | :--- |
| [docs/SCOPE.md](docs/SCOPE.md) | what this project is and deliberately is not |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | the BLE protocol, with per-fact provenance |
| [docs/NAVIGATION.md](docs/NAVIGATION.md) | navigation architecture, pictogram tables |
| [docs/CAPTURE.md](docs/CAPTURE.md) | what still needs a scooter, and how to get it |
| [research/SOURCES.md](research/SOURCES.md) | every source, and what it contributed |
| [AGENTS.md](AGENTS.md) | working rules for agents on this repo |

---

## Sources

Full attribution, licences and per-source contribution notes are in
[research/SOURCES.md](research/SOURCES.md). In brief:

**Local artifacts** (parent folder, not redistributed here)
- `TVS Connect_8.14.1.apk` — the official app, primary protocol source
- `JupiterRideCompanion.apk` — a working third-party companion app
- `JupiterRideCompanion/` docs — **CC BY-NC 4.0**
- `TVS-Connect-Application-VAPT/` — **CC0 1.0**, background only, see SCOPE
- TechEnclave thread + `kia_uvo.wiki` — methodology
- `research/refs.txt` — supplied reference list

**Web** — TVS product docs, Google Play listing, r/indianbikes,
1013503897/jadx-headless-mcp, morluto/rea.

---

## Licence

Non-commercial. See [LICENSE](LICENSE).

The protocol documentation in `docs/` reuses prose and the maneuver byte table
from `overclock98/JupiterRideCompanion` (CC BY-NC 4.0) under attribution, on
the same non-commercial terms.