# Sources

Everything this project is derived from, and what each source contributed.

## 1. Local artifacts (parent folder, **not** committed here)

| File | SHA-256 | Role |
| --- | --- | --- |
| `TVS Connect_8.14.1.apk` | `35046ea75eea2bd5f26e82d19b5002448f3ae6408e11bd943631c8c1ae15f0c4` | **Official TVS app**, v8.14.1, `com.tvsm.connect`. Primary protocol specification source. Statically decompiled only; never executed, never instrumented, never run against TVS servers. |
| `JupiterRideCompanion/JupiterRideCompanion.apk` | `957177a6ae6012b5b61278284a25b184bbc8a281db36478c2d3178d48fcff300` | Third-party companion app that **works against a Jupiter**. Package `com.tvs.jupiter.ble`. Contributed the *proven* navigation packet layout, the AES-CTR crypto confirmation, and an independent GATT/AES-key cross-check. |

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

## What the Jupiter companion APK contributed

Decompiled (`com.tvs.jupiter.ble`) as a second, independent source. Where the
two apps agree, a fact is solid; where they differ, both are recorded.

**Confirmed identically in both apps:**

| Fact | Official APK | Jupiter companion |
| :--- | :--- | :--- |
| GATT service | `5456534D-…` | `5456534D-…` |
| Write / read chars | `00005352-…` / `00005354-…` | same |
| AES mode | `AES/CTR/NoPadding` | `AES/CTR/NoPadding` |
| IV | the challenge bytes | the challenge bytes |
| AES key | `…B5 25 F4…` | `…B5 33 F4…` — **one byte differs** |

**Contributed only by the companion app** (it is the only one with a working
nav implementation):

- `BleNavigationPacketBuilder.buildControlPacket` — the `0x5A 0x4E` layout,
  including saturating distance encoding, the `remaining <= 0 → reuse distance`
  fallback, and the absence of any checksum.
- `buildTextPackets` — `0x5B 0x4F`, 17 characters, and the 5-second text-cycling
  cadence with the 11-character right-aligned metrics line.
- `BleConstants.AUTH_*` — `START_BYTE_9A`, `DATA_ID_F1 (0xF1)`,
  `AUTH_REQUEST_TYPE (0x50)`.

**Corrected by this source:** an earlier draft of this repo added a checksum to
the navigation frames and used start byte `0x5B` for the control packet. The
working implementation uses **no checksum** and start byte **`0x5A`**. Fixed in
`PacketBuilder.navigationControl`.

## What REA contributed

`morluto/rea` MCP server with `1013503897/jadx-headless-mcp` 0.7.1 as the
decompiler. Used for the focused, citable decompilations rather than a bulk
`jadx` pass — every method below was decompiled by REA with
`body_status: available`, no fallback markers, against a known artifact digest.

The official 180 MB APK exceeds REA's 120 s provider deadline. Working around
that by building a **slim APK** containing only `classes7.dex` + `classes12.dex`
(5 MB) brought it inside the deadline and made official-app methods queryable.
That artifact is a local analysis convenience; the official APK is never
redistributed.

Findings that changed the implementation:

| Method | Class | Result |
| :--- | :--- | :--- |
| `buildControlPacket` | `BleNavigationPacketBuilder` | Confirmed `0x5A 0x4E` layout, no checksum |
| `returnMobilePacket` | `BlePacketsProvider` | Official ping is 20 bytes, **no checksum**, bytes 12–18 zeroed |
| `returnValidMobilePacket` | `BlePacketsProvider` | Same shape, network level/battery at [2]/[3] |
| `returnMobilePacketWithACK` | `BlePacketsProvider` | Same, plus `[12] = 0x01` |
| `createMobileToClusterArray` | `BluetoothUtil` | Text sanitiser: emoji→`@`, non-`[A-Za-z0-9 ]`→`X`, byte-truncate 17 |
| `isEnglishCharacters` | `BluetoothUtil` | Confirms the cluster cannot render non-ASCII |
| `truncateToBytes`, `toUtf8BytesWithin` | `BluetoothUtil` | Byte-accurate truncation that never splits a char |
| `addChecksum` | `BluetoothUtil` | Checksum is **penultimate**: `255 - (sum % 256)` |
| `isValidChecksum` | `BluetoothUtil` | **Not** a universal validator — numerically inconsistent with `addChecksum` on a 20-byte frame |
| `inspect_android_class` ×3 | — | Member inventories with explicit overload indices |

The `BluetoothUtil` inventory returned 135 methods, which is how the text and
checksum helpers were located at all.

The ping and text-sanitiser results contradicted earlier assumptions and drove
the `mobileData` / `mobileDataEnriched` split plus the `sanitize()`
implementation — see `docs/PROTOCOL.md` §3 and §6a.

### REA limits encountered

- `inspect_android_class` on the full 180 MB APK → `provider_timeout` (120 s,
  `retryable`). Worked around with the slim APK rather than a larger deadline.
- REA's JavaScript runtime is confined: `shell` is not exposed to
  `execute`; use the dedicated shell tool for filesystem work.

## What the VAPT contributed

`TVS-Connect-Application-VAPT` (CC0, FEV LTD) is useful in exactly one
respect, and it is the reason this project can skip the cloud entirely.

**CVE-2024-35537 — "Broken Cryptography: Leaked secrets on Android and iOS
application platforms"** predicted that the client's secrets are hardcoded and
recoverable. This project then *demonstrated* it for the BLE layer: the
official APK embeds a static 16-byte AES key in `KeylessEntryUtils`, and a
third-party app ships the same key with one byte changed and drives real
hardware. That is the "authentication" in full — it is protocol fidelity, not a
security boundary, and this project treats it accordingly.

**CVE-2024-33308 / CVE-2024-33309** (unauthenticated access to other users'
service history; add/delete any user's emergency contact) are server-side flaws
against TVS's cloud. **Not used, and deliberately so.** This app never contacts
TVS, has no `INTERNET` permission, and has no user accounts to attack. The
findings are recorded only to justify that design — see `docs/SCOPE.md`.

### `research/refs.txt` — supplied reference list
A third-party summary of the reverse-engineering landscape: community projects,
the HCI-snoop-log workflow, decompilation, scanning tools, and cross-industry
BLE reference repos.

**Methodology is sound and already followed** — snoop log + Wireshark,
jadx, nRF Connect — and the community-project pointers match what this repo
already cites independently.

**One claim is factually wrong and was rejected on evidence.** It states the
TVS app "handles both Bluetooth Classic (RFCOMM/SPP) for core data transmission
and BLE for initial setup/handshakes", and that "older Ntorq clusters use
Classic Bluetooth via SPP simulating an RS-232 connection". Grepping all 8,292
decompiled classes for `createRfcommSocket` and `BluetoothSocket` returns **zero
hits**; `NonBLEConnectionConfig` is a stub returning `UUID.fromString("")`.
Every real model is BLE GATT. Recorded in `docs/PROTOCOL.md` §1a so the claim
does not misdirect a future capture session.

Kept in the repo for traceability rather than deleted.

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