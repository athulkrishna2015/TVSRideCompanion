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
| Ntorq + Raider inbound | **8 frame layouts transcribed** from the official APK (Raider shares the Ntorq config) |
| GATT / transport | implemented |
| AES-CTR handshake | **proven** — key and cipher confirmed in two independent apps |
| Navigation HUD packets | **proven** — `0x5A 0x4E` / `0x5B 0x4F` transcribed from a working app |
| Navigation on the Ntorq TFT | **untested on hardware** — width and pictograms unconfirmed |
| Pictogram table | transcribed (official); a second, conflicting table recorded |
| Jupiter / Apache / iQube | connect + stream raw frames; drivers not written |
| APK analysis | complete for the protocol packages |

The handshake and navigation packet layouts are cross-checked against **two**
independent applications — the official TVS Connect APK and a working
third-party Jupiter app. Where they agree, a fact is solid; where they differ,
both are documented rather than silently merged.

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
  NtorqDriver.kt      Ntorq + Raider: transcribed frame layouts
  PacketBuilder.kt    outbound frames (nav layout proven)
  Handshake.kt        AES-CTR challenge/response
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
| [docs/UI_REFERENCE.md](docs/UI_REFERENCE.md) | which apps the UI draws on, and privacy rules |
| [docs/CAPTURE.md](docs/CAPTURE.md) | what still needs a scooter, and how to get it |
| [research/SOURCES.md](research/SOURCES.md) | every source, and what it contributed |
| [AGENTS.md](AGENTS.md) | working rules for agents on this repo |

`ref/` holds UI reference screenshots and is **gitignored** — it contains
personal data (registration number, home location). See
[docs/UI_REFERENCE.md](docs/UI_REFERENCE.md) §3.

---

## Sources

Full attribution, licences and per-source contribution notes are in
[research/SOURCES.md](research/SOURCES.md). In brief:

**Local artifacts** (parent folder, not redistributed here)
- `TVS Connect_8.14.1.apk` — the official app, primary protocol source
- `JupiterRideCompanion.apk` — a working third-party companion app; source of
  the *proven* nav packet layout and an independent crypto cross-check
- `JupiterRideCompanion/` docs — **CC BY-NC 4.0**
- `TVS-Connect-Application-VAPT/` — **CC0 1.0**; its crypto finding (CVE-2024-35537)
  is what demonstrated the BLE "auth" key is hardcoded. The server-side CVEs are
  unused by design, see SCOPE
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