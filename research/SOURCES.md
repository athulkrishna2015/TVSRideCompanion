# Sources

Everything this project is derived from, and what each source contributed.

## 1. Local artifacts (parent folder, **not** committed here)

| File | SHA-256 | Role |
| --- | --- | --- |
| `TVS Connect_8.14.1.apk` | `35046ea75eea2bd5f26e82d19b5002448f3ae6408e11bd943631c8c1ae15f0c4` | **Official TVS app**, v8.14.1, `com.tvsm.connect`. Primary protocol specification source. Statically decompiled only; never executed, never instrumented, never run against TVS servers. |
| `JupiterRideCompanion/JupiterRideCompanion.apk` | `957177a6ae6012b5b61278284a25b184bbc8a281db36478c2d3178d48fcff300` | Third-party companion app that **works against a Jupiter**. The closest thing to a working reference implementation. Cross-check source for packet layout. |

Both are read-only inputs and stay in the parent directory. They are not
redistributed in this repo.

## 2. Local documentation (parent folder)

### `JupiterRideCompanion/` — overclock98/JupiterRideCompanion
License: **CC BY-NC 4.0** (attribution + non-commercial). Its prose and the
arrow tables are reused here only with attribution and under the same
non-commercial terms.

- `README.md` — feature set of the Jupiter companion app: Find-My-scooter via
  injected ping flag, button gesture mapping, TelecomManager call handling,
  Smart Navigation HUD, notification pagination.
- `REVERSE_ENGINEERING_REPORT.md` — **the most valuable document in the
  folder.** Full SmartXonnect BLE protocol: service/characteristic UUIDs, the
  AES-128-CTR handshake, the `0x4A` ping packet layout and checksum, inbound
  telemetry `DataID` table, the `0x5A 0x4E` navigation control packet, the
  `0x4C`/`0x63` text message packets, the "silence gap" button protocol, and
  the Doze-bypassing connection architecture.
- `NAVIGATION_ARCHITECTURE.md` — the maneuver pictogram byte table (turns,
  junctions, forks, roundabouts CW/CCW), the alpha-hash image harvester, the
  calibration UI, and the 2-tier arrow resolution fallback.

Note: that report's own telemetry table lists `0x54` as a *"Legacy
Ntorq-style button press fallback"* — the only Ntorq-specific trace in the
folder, and a starting point for Ntorq divergence hunting.

### `TVS-Connect-Application-VAPT/` — aaravavi/TVS-Connect-Application-VAPT
License: **CC0 1.0**. FEV LTD's pentest of TVS Connect (app v4.6.0/iOS 5.0.0).
Covers CVE-2024-33308, CVE-2024-33309, CVE-2024-35537.

Read for **background only**, to understand why this project deliberately
avoids the cloud entirely. Not used as a design input — see `docs/SCOPE.md`.
Its methodology section (app decompilation) is the useful part.

### `Making alternative to TVS connect app...pdf` — TechEnclave thread
Local copy of `techenclave.com/t/making-alternative-to-tvs-connect-app-need-some-help/423446`.
Primary source for the *current state of the art* in this scene:

- **MysticUwU** — reverse-engineered the SmartXonnect BLE protocol; reported
  find-me and telemetry working, then connection-drop problems, later a working
  rewrite with no drops and Google-Maps-notification navigation. Private repo.
- **maxstirner** — building the equivalent for the Apache RTR 4V, using this
  repo as a reference plus his own BLE captures.
- **clipager**, **Kumar2**, **Gaurav_Negi**, **Mohan997** — testers wanted.

Consensus in the thread: there is no real security key; the protocol is
insecure by design; the official app's reliability, not its security, is the
complaint.

### `kia_uvo.wiki/` — fuatakgun/kia_uvo wiki
License: repo LICENSE is CC0. Unrelated vehicle, but its **methodology** is
directly reusable: patching an app's network-security config to trust a local
MITM proxy, capturing traffic, and building a replacement client from the
observed API. Methodology only — its Kia cloud targets are not applicable here.

## 3. Web sources

- **TVS Connect on Google Play** — <https://play.google.com/store/apps/details?id=com.tvsm.connect>
  Feature surface: SmartXonnect telematics, live tracking, SOC, ride stats,
  crash alerts, geofencing.
- **TVS Ntorq 150 product/bluetooth guides** —
  <https://www.tvsmotor.com/media/blog/how-to-connect-tvs-ntorq-bluetooth-on-android-and-iphone>
  Confirms the pairing flow the official app drives.
- **"I got so frustrated with the terrible TVS Connect app…"**, r/indianbikes —
  companion write-up to the TechEnclave work; corroborates the protocol shape
  and the Google-Maps-notification navigation approach.
- **1013503897/jadx-headless-mcp** — <https://github.com/1013503897/jadx-headless-mcp>
  Decompilation backend driven through the REA MCP server.
- **morluto/rea** — <https://github.com/morluto/rea> — the REA MCP server used
  for the APK analysis in `docs/RE_NOTES.md`.

## 4. What still needs a first-party source

The Ntorq-specific facts below are **not** in any public source found so far and
are the core of the remaining work:

1. Whether the Ntorq 150 SX uses the *same* SmartXonnect GATT service UUID as
   the Jupiter, or a variant.
2. The Ntorq's exact handshake behaviour (AES-128-CTR, or weaker/no handshake).
3. Ntorq-specific telemetry `DataID` values and scaling.
4. Ntorq maneuver pictogram byte table — the Jupiter's table in
   `NAVIGATION_ARCHITECTURE.md` is a *hypothesis*, not a fact, for Ntorq.
5. Whether the Ntorq cluster supports the `0x4C`/`0x63` text-message packets.

These are marked `UNVERIFIED` in `docs/PROTOCOL.md` and must be confirmed
against the user's own scooter — see `docs/CAPTURE.md`.