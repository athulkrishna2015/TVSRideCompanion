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
| Ntorq + Raider inbound | **8 frame layouts transcribed** from the official APK |
| GATT / transport | implemented |
| Discovery + connect | scan screen ships; **never run against a real cluster** |
| AES-CTR handshake | **proven** — key and cipher confirmed in two independent apps |
| Navigation HUD packets | **proven** — `0x5A 0x4E` / `0x5B 0x4F` |
| Text sanitiser | transcribed — cluster cannot render non-ASCII |
| Ntorq TFT specifics | **untested on hardware** — width and pictograms unconfirmed |
| UI | adapted Material 3, light/dark/auto — **builds clean, runs on device** |
| Tests | 18 JVM tests over the protocol layer, run in CI |
| APK analysis | complete for the protocol packages |

The handshake, GATT and navigation layouts are cross-checked against **two**
independent applications. Where they agree, a fact is solid; where they differ,
both are documented rather than silently merged.

### Tested on a phone. Never connected to a scooter.

Those are two very different claims and it is worth keeping them apart.

`:app:assembleDebug` succeeds with zero warnings; CI runs the unit tests and
asserts the merged manifest contains **no `INTERNET` permission**. The app has
been installed and driven on a real device many times — which is how the
Overview/Log tab swap, an unreachable Ride Mode toggle, and a log sink that
discarded every session were all found. None of them were visible to any test.

What has **never happened** is a Bluetooth connection to an instrument cluster.
Every protocol fact below is verified against a shipped APK and cross-checked
between two apps; none of it has been confirmed against the owner's own Ntorq.
The dashboard showing `—` is the correct rendering of "nothing received", not
evidence that decoding works.

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
  ScooterScanner.kt      BLE discovery, filtered on the SmartXonnect service
  ScooterBleService.kt   GATT, write mutex, frame stream
  RideService.kt         foreground service
  BootReceiver.kt        re-attach after reboot, when the user opts in
nav/
  MapsNotificationListener.kt   any maps app → NavInstruction
  NavigationBridge.kt           NavInstruction → cluster frames
session/  process-scoped store the UI observes; the only shared state
prefs/    ride mode, last known cluster, appearance
log/      logger: ring buffer, rotating file sink, hex frame capture
ui/       Compose screens (Home, Overview, More, Scan, Log) + theme
```

A model with no driver still **connects and streams raw frames** — that is how a
new vehicle gets characterised without writing a driver first.

Adding a model means writing one `ProtocolDriver`. The transport does not change.

---

## What it does

- **Navigation on the cluster** from any maps app, no account
- **Find my scooter** — injects the flag into the keepalive packet
- **Live telemetry** — speed, odometer, fuel, trip, engine temp, service status
- **Find and connect** — BLE scan filtered on the SmartXonnect service
- **No cloud** — nothing leaves the phone

Telemetry is limited to values the Ntorq cluster is documented to display. TVS's
own manual enumerates every display element, and **range and fuel economy are
not among them** — they are app or cloud figures, and this app has neither. So
they are decoded but not shown; see [docs/NAVIGATION.md](docs/NAVIGATION.md).

---

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

CPU budget is capped at 2 cores by `gradle.properties`; see
[AGENTS.md](AGENTS.md) for the full rule, and [BUILD.md](docs/BUILD.md) for
prerequisites.

CI (`.github/workflows/android.yml`) runs the unit tests, asserts the merged
manifest has **no** `INTERNET` permission, asserts the caller-ID permissions
stay **absent**, and attaches the APK to a release on a `v*` tag.

---

## Documentation

| File | Contents |
| :--- | :--- |
| [docs/SCOPE.md](docs/SCOPE.md) | what this project is and deliberately is not |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | the BLE protocol, with per-fact provenance |
| [docs/NAVIGATION.md](docs/NAVIGATION.md) | navigation architecture, pictogram tables, cluster display zones |
| [docs/UI_REFERENCE.md](docs/UI_REFERENCE.md) | which apps the UI draws on, and privacy rules |
| [docs/BUILD.md](docs/BUILD.md) | prerequisites, CPU-capped build command, gotchas |
| [docs/CAPTURE.md](docs/CAPTURE.md) | what still needs a scooter, and how to get it |
| [research/SOURCES.md](research/SOURCES.md) | every source, and what it contributed |
| [AGENTS.md](AGENTS.md) | working rules for agents on this repo |

`ref/` holds UI reference screenshots and is **gitignored** — it contains
personal data (registration number, home location). See
[docs/UI_REFERENCE.md](docs/UI_REFERENCE.md) §3.

---

## Sources

Full attribution, licences and per-source contribution notes are in
[research/SOURCES.md](research/SOURCES.md). Every third-party repository there is
pinned to an exact commit with a clone command, so a reader reproduces precisely
what was read rather than whatever `master` is today.

| Repository | Clone URL | Licence |
| :--- | :--- | :--- |
| [overclock98/JupiterRideCompanion](https://github.com/overclock98/JupiterRideCompanion) | `https://github.com/overclock98/JupiterRideCompanion.git` | CC BY-NC 4.0 |
| [Hyundai-Kia-Connect/kia_uvo.wiki](https://github.com/Hyundai-Kia-Connect/kia_uvo.wiki) | `https://github.com/Hyundai-Kia-Connect/kia_uvo.wiki.git` | CC0 |
| [aaravavi/TVS-Connect-Application-VAPT](https://github.com/aaravavi/TVS-Connect-Application-VAPT) | `https://github.com/aaravavi/TVS-Connect-Application-VAPT.git` | CC0 1.0 |

What each contributed:

- **`overclock98/JupiterRideCompanion`** — the only working third-party app
  available. Source of the proven navigation packet layout, an independent crypto
  cross-check, and the Home-screen layout this app adapts.
- **`aaravavi/TVS-Connect-Application-VAPT`** — FEV LTD's pentest. Its crypto
  finding (CVE-2024-35537) is what demonstrated the BLE "auth" key is hardcoded.
  Background only; the server-side CVEs are unused by design, see SCOPE.
- **`Hyundai-Kia-Connect/kia_uvo.wiki`** — an unrelated vehicle, but its
  methodology (intercept, observe, reimplement) is the transferable part.

**Local artifacts** (parent folder, not redistributed here)

- `TVS Connect_8.14.1.apk` — the official app, primary protocol source, cited
  by SHA-256
- `JupiterRideCompanion/JupiterRideCompanion.apk` — that project's own build
- TechEnclave thread + `research/refs.txt` — supplied reference list
- `ref/` — TVS's Ntorq 125 owner manual, gitignored for containing personal data

**Web** — TVS product docs, Google Play listing, r/indianbikes,
1013503897/jadx-headless-mcp, morluto/rea.

---

## Licence

Non-commercial. See [LICENSE](LICENSE).

The protocol documentation in `docs/` reuses prose and the maneuver byte table
from `overclock98/JupiterRideCompanion` (CC BY-NC 4.0) under attribution, on
the same non-commercial terms.