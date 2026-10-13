# SmartXonnect BLE protocol — Ntorq (125 / 150)

**Status: largely UNVERIFIED for the Ntorq.** Everything below is either
(a) transcribed from the Jupiter reverse-engineering report in the parent folder,
or (b) pending confirmation against the user's own scooter. Provenance is
marked per section. Do not treat the Jupiter facts as Ntorq facts until
`docs/CAPTURE.md` has been run.

Provenance tags: `[JUPITER]` = from the Jupiter report, `[UNVERIFIED]` =
assumed shared with Ntorq, `[NATORQ]` = confirmed for Ntorq.

---

## 1. GATT layer

```
Service UUID : 5456534D-5647-5341-5342-454E544F5251   [VERIFIED_FROM_APK]
Write char   : 00005352-0000-1000-8000-00805f9b34fb   [VERIFIED_FROM_APK]
Read char    : 00005354-0000-1000-8000-00805f9b34fb   [VERIFIED_FROM_APK]
```

Transcribed from `com.tvs.bike.core.bikes.*BLEConnectionConfig`, which declares
these three UUIDs **identically** for Ntorq, Jupiter, Apache and iQube. Only
the U388 EV family differs — it builds its service UUID at runtime from
`U388_SERVICE_UUID_KEY = "9-5342-454E544F5251"`.

The service UUID is the ASCII string `TVSM-VGSASBEN TORQ` read byte-wise, which
is why the non-EV line shares one service.

All configs request `getCharacteristicsWriteType() == 2`
(`WRITE_NO_RESPONSE`) and MTU 512.

### Model → connection config

From `com.tvs.bike.core.BikeVariants.getConnectionConfiguration()`:

| Model | Config | Note |
| :--- | :--- | :--- |
| Ntorq | `NtorqBLEConnectionConfig` | `shouldAutoConnect() == false` |
| **Raider** | **`NtorqBLEConnectionConfig`** | **same config — no RaiderBLEConnectionConfig exists** |
| Jupiter | `JupiterBLEConnectionConfig` | identical UUIDs |
| Apache | `ApacheBLEConnectionConfig` | identical UUIDs |
| iQube | `IQubeBLEConnectionConfig` | identical UUIDs, but `shouldAutoConnect() == true` |
| U388 | `U388BLEConnectionConfig` | different (EV) service UUID |
| (NonBLE) | `NonBLEConnectionConfig` | **stub** — empty UUID strings, write type 0 |

Raider sharing the Ntorq config is why one driver covers both: `protocol/raider/*`
adds only `5A54ACK`, `5A6B` and `5A9D` frames — a superset, not a separate
protocol.

## 1a. There is no Classic Bluetooth / RFCOMM path

A supplied reference (`research/refs.txt`) claims the TVS app "handles both
Bluetooth Classic (RFCOMM/SPP) for core data transmission and BLE for initial
setup/handshakes", and that "older Ntorq clusters use Classic Bluetooth via
Serial Port Profile (SPP/RFCOMM)".

**That is incorrect, and worth correcting before it misdirects a capture
session.** Searching all 8,292 decompiled classes for `createRfcommSocket`,
`createInsecureRfcommSocket` and `BluetoothSocket` returns **zero hits**.

`NonBLEConnectionConfig` is not an RFCOMM transport: it returns
`UUID.fromString("")` for the service, read and write UUIDs, write type `0`,
and is referenced only by the `NonBLE` variant stub. It is an unimplemented
placeholder.

Every real SmartXonnect model uses BLE GATT. If a capture session sees RFCOMM
data, it is not TVS traffic.

## 2. Link handshake

Most units require a handshake before accepting raw commands. Three
independent sources agree on the shape, and two of them ship actual keys.

1. Scooter emits a challenge frame — start byte `0x9B`, data id `0xF2`, plus 16
   random bytes.
2. Client responds with start byte `0x9A`, data id `0xF1`, an AES-128-CTR
   (no padding) result, padded to 20 bytes and terminated with `0xFF`.

From `BlePacketsProvider.byteArrayForUnLock`:
```
0x9A 0xF1 <encrypted> <padding to 20> 0xFF
```

`BluetoothUtil` uses `AES/CTR/NoPadding` with the **challenge bytes as the
IV** — a weak construction, since the IV is visible on the wire, but it is what
the cluster expects.

Independently confirmed by the third-party Jupiter app's `CryptoUtils`, which
builds the identical pipeline:

```java
Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
new SecretKeySpec(key, "AES");
new IvParameterSpec(challenge);
```

### The key is not a secret

| Source | Key bytes |
| :--- | :--- |
| Official APK, `KeylessEntryUtils.AES_KEY_BYTE_ARRAY` | `7A A3 20 4D 16 1D B5 **25** F4 EB 20 4F BC D7 3D D4` |
| Jupiter companion APK, `BleConstants.AES_KEY` | `7A A3 20 4D 16 1D B5 **33** F4 EB 20 4F BC D7 3D D4` |
| Jupiter report (`REVERSE_ENGINEERING_REPORT.md` §1.1) | `7A A3 20 4D 16 1D B5 **33** F4 EB 20 4F BC D7 3D D4` |

Two independent applications embed a **hardcoded 16-byte key differing in a
single byte**, and both work against real hardware. That is the client-side
half of **CVE-2024-35537** — "Broken Cryptography: Leaked secrets on Android
and iOS application platforms" (FEV LTD). It is reproduced here as a protocol
requirement, not as a credential. Treat the handshake as protocol fidelity,
never as a security boundary.

Both variants are in `Handshake.kt`; the official one is the default.

> Note `KeylessEntryUtils.AES_KEY_BYTE_ARRAY` is **not mutable** at this class
> level, but `setAES_KEY_BYTE_ARRAY()` exists, so the key can be overridden at
> runtime — plausibly pushed via Firebase RemoteConfig, which the official app
> embeds heavily. If a cluster rejects the shipped key, that is where to look
> next. Do not brute-force.

### Obfuscated command blocks

`KeylessEntryUtils` also holds `UNLOCK_COMMAND_UUID`,
`FIND_VEHICLE_COMMAND_UUID`, `LOCK_COMMAND_UUID` and
`OPEN_TRUNK_COMMAND_UUID`. Despite the names these are **not UUIDs** — they are
byte arrays that embed protocol constants (`START_BYTE_5B`, `TRIP_DATA_PACKET`,
`SONG_NAME_PACKET`, …) to disguise trivial values as opaque blobs. Not
reproduced here; `PacketBuilder` covers the same operations readably.

## 3. Ping / "mobile data" packet — `DataID 0x4A`

Sent continuously to keep the link alive and feed the dashboard. Not encrypted.
20 bytes: `0x5B 0x4A` + 17 payload bytes + `0xFF`.

### Official layout (verified with REA)

Transcribed from `BlePacketsProvider` in the official APK. All three variants —
`returnMobilePacket`, `returnValidMobilePacket`, `returnMobilePacketWithACK` —
agree on this shape:

| Byte | Field | Notes |
| :--- | :--- | :--- |
| `[0]` | Start | `0x5B` |
| `[1]` | Data ID | `0x4A` |
| `[2]` | Signal / network level | `0` when no SIM |
| `[3]` | Battery | from shared preferences |
| `[4]` | Incoming call status | |
| `[5]` | Call mute state | |
| `[6]` | Packet validity | 0 / 1 / 2 |
| `[7]` | constant | `0x02` |
| `[8..10]` | padding | zero |
| `[11]` | Network type | e.g. `0x04` |
| `[12..18]` | padding | zero (`0x01` at `[12]` in the ACK variant) |
| `[19]` | End | `0xFF` |

**There is no checksum byte.** The official builder never writes one — verified
by decompiling all three variants, none of which call a checksum routine. An
earlier draft of this repo added one, following the Jupiter report; that was
wrong and has been corrected.

### Enriched layout (third-party Jupiter app)

The Jupiter report documents a richer packet, which that app does send:

| Byte | Field |
| :--- | :--- |
| `[2]` | signal (high nibble) / battery (low nibble) |
| `[4]` | temperature °C + 40 |
| `[6..8]` | hour, minute, second |
| `[9]` | AM/PM |
| `[11]` | network type |
| `[12..14]` | day, month, year mod 100 |
| `[17]` | find-my-scooter flag |
| `[18]` | checksum (`255 - (sum[0..17] % 256)`) |

That app drives real hardware, so the cluster tolerates this shape — but it is
the app's own construction, not the vendor format, and it puts a checksum at
`[18]` where the official app writes zero. Both are implemented:
`PacketBuilder.mobileData` (default) and `PacketBuilder.mobileDataEnriched`.

Open question for the Ntorq: does it want the enriched fields (time sync,
find-my) or is the official minimal packet enough?

## 4. Inbound telemetry `DataID`s

Dispatch is by `byteArray[1]` — `IncomingFrameIdentifier.identify()` indexes the
data-id byte and looks it up in a per-model map. For Ntorq,
`NtorqFrameIdentifier.getFrameConfig()` returns exactly:

> **This table did not match the hardware.** §4a records what the owner's
> cluster actually sent: `0x16` arrives constantly and is absent here, and
> `0x19` never arrives despite being listed. Read §4a before trusting this.

```
 16 (0x10) -> SPEEDOMETER_DATA_5A_10_FRAME -> NtorqSpeedOMeter1
 17 (0x11) -> SPEEDOMETER_DATA_5A_11_FRAME -> NtorqSpeedOMeter2
 25 (0x19) -> SPEEDOMETER_DATA_5A_19_FRAME -> NtorqSpeedOMeter3
 24 (0x18) -> SPEEDOMETER_DATA_5A_18_FRAME -> NtorqSpeedOMeter4
 55 (0x37) -> SPEEDOMETER_DATA_5A_37_FRAME -> NtorqCalibrationResponse
107 (0x6B) -> MUSIC_FRAME                  -> NtorqDataFrame5A54
 41 (0x29) -> WIFI_PASSWORD_5A_29_FRAME    -> NtorqWifiPasswordFrameData
 84 (0x54) -> SPEEDOMETER_DATA_5A_54_FRAME -> NtorqDataFrame5A54ACK
```

Note `0x54` is the **ACK** here and `0x6B` carries the music command — the
inverse of what the Jupiter report's table implies. Transcribed in
`NtorqDriver.kt` with byte offsets and scaling factors.

### 4a. Measured on the owner's Ntorq 125 — 2026-10-13

First successful connection, ~2 minutes, 501 inbound frames. The table above is
transcribed from the APK; this is what the cluster **actually emitted**. They do
not match.

| Sent | Count | In our table? |
| :--- | --- | :--- |
| `5A 10` | 132 | yes — speed, odometer, fuel |
| `5A 11` | 75 | yes — vehicle state, version at idx 7 |
| `5A 16` | **136** | **no — not in the frame identifier map at all** |
| `5A 18` | 143 | yes — engine diagnostics |
| `5B 42` | 6 | **no — carries the cluster's own BLE address at idx 7–12** |

Two findings, both of which change the picture:

- **`0x16` (22) is the second most common frame and we have no table for it.**
  The identifier map above lists `0x10`, `0x11`, `0x19`, `0x18`, `0x37`, `0x6B`,
  `0x29`, `0x54` — not `0x16`. A frame we decoded nothing from was arriving
  nearly as often as the speedometer. Its layout is unknown.
- **`0x19` never appeared at all**, despite being in the table and carrying the
  documented economy and distance-to-empty fields. Either it is only emitted in
  states we did not visit, or the identifier map is not what the Ntorq 125
  actually uses.

**Every frame is exactly 20 bytes and ends `0xFF`.** A few all-zero 20-byte
frames arrive at connect time, before real telemetry starts — worth knowing so
they are not mistaken for decode failures.

Field values seen, for a stationary cluster with the ignition on:

```
5A 10 00 02 88 2A 02 00 00 00 09 50 00 …   idx 3-5 odometer, idx 9-10 = 09:50
5A 11 00 00 05 00 00 12 00 A3 20 00 …       idx 7 = 0x12
5A 16 00 00 00 00 00 03 28 00 45 00 00 00 96 09 …
5A 18 00 00 00 64 64 20 48 00 00 7C 00 …   idx 5,6 = 0x64, idx 11 = 0x7C
```

`5A 10` idx 9–10 reading `09 50` matched the wall-clock time of the capture to
the minute, which suggests that pair is a cluster clock. That is an inference
from one coincidence, not a confirmed field.

Outbound, the only thing we sent was the keepalive, exactly every 2.0 s:

```
5B 4A 04 02 00 00 00 02 00 00 00 04 00 00 00 00 00 00 00 FF
```

### 4c. Navigation mirroring — the first frames that reached the cluster

Measured 2026-10-13 with a live route. Outbound navigation was observed on the
wire for the first time:

```
5A 4E 00 00 99 01 00 00 00 07 01 01 00 00 00 00 00 00 00 FF
5A 4E 26 02 9B 01 26 02 00 07 01 01 00 00 00 00 00 00 00 FF
5B 4F "toward Example Rd"     (ASCII, 17 bytes)
5B 4F "ETA 411m X 550m"
```

`5A 4E` carries the control state — distance and ETA — and `5B 4F` carries the
text row. They alternate on a short interval so the display shows the street and
the metrics in turn.

Two things were broken before this, and both produced a screen that looked right:

- **The bridge was never given an instruction.** It read its own `_hud` flow,
  populated only by `onInstruction()`, which was called through
  `MapsNotificationListenerRef.current` — an object declared and never assigned
  anywhere in the codebase. The UI strip reads `Session.hud` directly and never
  went through the bridge, so navigation mirroring looked end-to-end on the
  phone while having never once run over BLE.
- **The sanitiser corrupted street names.** Every non-alphanumeric character was
  rewritten to `X`, so a hyphenated road reached the cluster with an `X` where
  the dash was, and the rider cannot tell an `X` from a real letter. Now `+` and
  `-` are kept and other punctuation folds to a single space. Emoji still
  collapse to `X`: for a pictogram an unknown glyph genuinely is an `X`, and
  dropping it would close the gap between two words.

**Unverified:** whether the cluster renders `+` and `-` at all. Both are ASCII
printable and very likely renderable, but that needs eyes on the dash next time.
`NH-44` is kept as `NH-44` rather than folded to `NH 44`, on the grounds that
the second is a different road name.

### 4d. Caller ID and SMS

The Ntorq 125 manual lists **"CALLER ID and SMS notification on speedometer"** as
a documented feature, with a green smart-connect LED for an incoming call or
message. `PacketBuilder.textRow` (`0x5B 0x4C` row 1, `0x5B 0x63` row 2) and
`PacketBuilder.callerInfo` (`0x5B 0x43`) were both written but unreachable:
`NtorqDriver.buildTextRow` returned `null` and the driver interface had no
`buildCallerInfo` at all. Both are wired now.

Notifications are classified **structurally** — on the Telecom call extras and on
`MessagingStyle` — rather than by package, because dialer and messaging apps are
effectively unbounded in number and share no documented extras contract.

Car and bike mode need no separate path: Google Maps puts the instruction,
street and distance in the same title/text/sub fields whichever mode is
routing, so mirroring is mode-independent. A `navigation.mode` extra is read
defensively and purely for information — it is community-documented rather than
public API, and **nothing gates on it**, precisely because it is unverified.

`ANSWER_PHONE_CALLS` and `CALL_PHONE` are deliberately **not** requested. Both
are hard restricted from Android 10: only the app the user has set as the default
phone handler can hold them, so a third-party app is denied instantly. Answering
a call from a cluster button would first mean becoming the default dialer. CI
asserts their continued absence so they are not added on a hunch.

### 4b. Discovery and handshake — measured, and both assumptions were wrong

- **The cluster advertises no service UUID.** `5456534D-…` is absent from its
  advertisement packet, so the default "only SmartXonnect clusters" scan filter
  finds nothing and the device must be found by name. It advertises as
  `TVSBT<digits>`, which is what the name-based fallback in `ScooterScanner`
  matches. Without that fallback the cluster is invisible to a filtered scan —
  it has to be connected to before the UUID can appear in the GATT table.
- **No pairing mode was needed**, and **no `9B F2` challenge was ever sent**.
  The handshake in §2 is built and wired, but this cluster does not use it.

`BtData.isValidFrame()` accepts only start bytes `0x9B` and `0x5A` inbound.

### Jupiter, for comparison

| Data ID | Offset | Field | Scaling |
| :--- | :--- | :--- | :--- |
| `0x10` | 3–5 | Odometer | uint24 **big-endian**, ÷ 10 → km |
| `0x10` | 6 | Fuel | lower nibble bars; upper nibble reserve |
| `0x19` | 8 | Economy | km/L |
| `0x19` | 11–12 | Distance to empty | |

### Byte order is big-endian, and the evidence is a photo of the dash

Every multi-byte field is **big-endian**. Measured frame:

```
5A 10 00 02 88 2A 02 00 00 00 09 50 00 00 00 00 00 00 00 FF
         ^^^^^^^ odometer, indices 3–5
```

`0x02882A` = 165,930 → **16,593.0 km**, which is the figure on the cluster.
Read little-endian the same three bytes give `0x2A8802` = 2,787,330 → 278,733.0,
a factor of 16.8 too large, on a bike that has done 16,593 km.

This was wrong in the first implementation, which assembled with `shl (8 * i)`.
The unit tests did not catch it because the *fixture writer* used the same
little-endian helper the reader did, so the two agreed by construction. A test
built from its own encoder cannot detect a shared assumption; only the bytes off
the wire can. It now asserts both the correct value and the wrong one.

Worth noting the magnitude of the failure: it produced a number. A field that
decoded to `null`, or to a value outside any plausible range, would have been
caught the first time anyone looked at it.

### The byte order is confirmed in the official parser, not inferred

The endianness above was originally settled by comparing the dashboard with the
captured bytes. That is good evidence, and it is now corroborated by the vendor's
own code — `DataParser.extractValue`:

```java
byte[] bytes = extractByteArray(start, length);
for (byte b : bytes) sb.append(String.format("%02X", b));
return Integer.parseInt(sb.toString(), 16);
```

Bytes are appended **in order** and the whole string is parsed as one hex
number, so the first byte is the most significant. That is big-endian, stated by
the implementation rather than deduced from a photograph.

The same class settles the offsets. `@ParsingMeta` marks are **1-based**
(`START_BYTE` at 1, `END_BYTE` at 20), and every one maps onto our 0-based index
after subtracting one:

| Vendor | length | Ours | Field |
| :--- | :--- | :--- | :--- |
| 1 | 1 | 0 | START_BYTE |
| 3 | 1 | 2 | SPEED |
| 4 | 3 | 3–5 | ODOMETER |
| 7 | 1 | 6 | FUEL_LEVEL |
| 15 | 3 | 14–16 | TRIP_F_METER |
| 18 | 2 | 17–18 | ENGINE_RPM |
| 20 | 1 | 19 | END_BYTE |

And the scaling is the vendor's too — `getOdometer()` is literally
`return this.odometer / 10;`.

`getFuelLevel()` is the same story: it takes `Integer.toHexString(byte)`, keeps
the **last** character, and parses it as a hex digit. That is the low nibble,
which is what this app does. So the decode is correct; see the fuel section below
for why the gauge still could not be trusted.

### Unresolved: byte 6 of `0x10` is not behaving like a fuel level

Byte 6 is transcribed as `FUEL_LEVEL` and the app reads its low nibble as a bar
count. Across **772 captured `5A 10` frames** spanning three sessions, it takes
exactly **two** values:

| Value | Low nibble | Occurrences |
| :--- | :--- | :--- |
| `0x02` | 2 | 396 |
| `0x07` | 7 | 376 |

Three things about that do not fit a fuel level:

- **It never varies with riding.** The odometer is constant at 16,593.0 km across
  all 772 frames — the bike never moved during capture — so there is no evidence
  it tracks anything dynamic at all.
- **It changes exactly once, at frame 132, in every session.** A level that
  moves between two fixed values at a fixed frame offset and then stops is more
  like a state flag than a measurement.
- **It contradicts the cluster.** The dash was photographed showing
  `FUEL EMPTY`, while byte 6 read `0x07` — which the low-nibble mapping renders as
  a **full** tank. Those cannot both be true.

#### What the official app does, and why it does not settle this

`NtorqSpeedOMeter1.getFuelLevel()`:

```java
String hex = Integer.toHexString(this.fuelLevel);
char last = /* final character of hex */;
return Character.digit(last, 16);
```

The last hex character of the byte is its **low nibble** — which is what this
app already did. So the decode was never the problem, and the vendor's own code
would produce the same 7. The disagreement is between the *phone's* reading and
the *cluster's own gauge*, and the cluster is not reading a fuel sender through
this app.

One more thing ruled out: `0x11` index 2 is **not** a fuel level either. In the
official class it is `fuelSensorFailure`, a fault flag, and it reads `0x00` — no
fault — while the tank is empty. Reading it as a level would have produced the
opposite error.

#### The fix: reject what the gauge cannot represent

The gauge has six bars. The value transmitted while the cluster said
`FUEL EMPTY` was **7**, which six bars cannot express. The app used to clamp
`min(value, 6)`, which turned that into "6 of 6 — full" on an empty tank.

Out-of-range values are now **dropped**, and the gauge reads *No usable reading*
instead of inventing a level. A genuine `0..6` still renders normally. This does
not make the gauge correct — it makes it stop being confidently wrong, which is
the only honest position until a capture with a part-full tank exists.

**To close this out**, one capture with fuel in the tank is all that is needed:
the raw byte at that moment gives the second anchor, and two points fix the
scale. Without it, any mapping is a guess wearing a decimal point.


Jupiter and Ntorq share `SpeedOMeter1` offsets almost exactly; they differ at
index 12 (`LOCATION_TAG` on both) and in the trip-meter width (3 bytes on
Ntorq, 3 on Jupiter SM1). The `jupiter/` package also contains
`NtorqSpeedOMeter2/3/4`, which are byte-for-byte the Ntorq variants of those
frames — more evidence the two models share a skeleton.

`0x10`, `0x11`, `0x18`, `0x19` are only broadcast with the ignition ON, which is
how parked and riding are distinguished. `[JUPITER]`

## 5. Outbound navigation control — `0x5A 0x4E`

Sent ~every 1.5 s to hold the HUD open. `[JUPITER]`

| Byte | Field |
| :--- | :--- |
| 2–3 | distance to turn, metres |
| 4–5 | ETA / remaining time, minutes |
| 6–8 | total remaining trip distance |
| 9 | **pictogram ID** (the maneuver arrow) |
| 10 | text rows in use (1 or 2) |

Byte 9 is the interesting one: it is the arrow. Its mapping is in
`docs/NAVIGATION.md`.

The cluster firmware natively renders the distance-to-turn integer on line 1
next to the arrow, so the app only has to fight for the remaining 17-character
line — hence the 3-second text cycling described in `docs/NAVIGATION.md`.

## 6. Outbound text — `0x4C` / `0x63` / `0x4F`

Pure text with no arrow overlay: caller ID, song titles, notifications, and the
navigation street line.

| Packet | Purpose |
| :--- | :--- |
| `0x5B 0x4C` | message text row 1 (`DATA_ID_MESSAGE1 = 76`) |
| `0x5B 0x63` | message text row 2 (`DATA_ID_MESSAGE2 = 99`) |
| `0x5B 0x4F` | navigation text (`DATA_ID_NAVIGATIONDATA1 = 79`) |
| `0x5B 0x50` | navigation text 2 (`DATA_ID_NAVIGATIONDATA2 = 80`) |

All are 20 bytes: 2-byte header, 17-byte payload, `0xFF` terminator, **no
checksum**.

### 6a. The cluster cannot render non-ASCII

The most important practical rule for anyone building on this protocol, and not
obvious. Transcribed from `BluetoothUtil.createMobileToClusterArray` and
`isEnglishCharacters` in the official APK:

1. **Emoji and symbol codepoints become `@`.** The upstream regex covers
   `\p{So}`, `\p{Cn}`, U+1F300-1F6FF, U+1F900-1F9FF, U+1FA70-1FAFF,
   U+2600-26FF and ZWJ (U+200D).
2. **Anything outside `[A-Za-z0-9 ]` becomes `X`.** `isEnglishCharacters`
   returns false for any character outside `[A-Za-z]`.
3. **Truncation.** ASCII is character-truncated to 17; anything else is
   byte-truncated via `truncateToBytes`, which never splits a multibyte char.
4. Trimmed, then UTF-8 encoded.

For an Indian vehicle this matters constantly. `NH-44 தென்` reaches the cluster
as `NH-44 XXXX`, not as mojibake. Skipping this step sends glyphs the cluster
silently cannot draw. `PacketBuilder.sanitize()` implements it faithfully.

`createMobileToClusterArrayWithEmojiData` is a separate variant that appears to
carry emoji differently — not transcribed, not needed for navigation.

### 6b. Checksum placement

`BluetoothUtil.addChecksum(byte[])` builds:

```
[ data[0], data[1], data[2 .. len-2], (255 - sum(data) % 256), 0xFF ]
```

The checksum is **penultimate** — `length - 2`, not a fixed index. For a 20-byte
frame that happens to be 18; for a shorter one it is not. Use `Checksum.apply()`,
which derives the position.

### `isValidChecksum` is not a universal validator

The official app also has `isValidChecksum(byte[])`:

```java
receivedData[len - 2] + (sum(receivedData) % 256) == 255
```

where `sum` spans the **entire** array including the checksum and the `0xFF`
terminator. Checked numerically, that identity does **not** reproduce
`addChecksum`'s output on a 20-byte frame. It therefore applies only to specific
frame shapes whose layout differs, and REA did not establish which.

This project does **not** implement it. Inbound validation stops at structural
checks plus per-model decoding — inventing a validator that discards good frames
would be worse than having none. See `docs/CAPTURE.md` §2.

## 7. Button handling — the "silence gap"

The cluster's voice-assistant button does not emit a pressed packet. Holding it
**stalls the whole BLE telemetry stream**. `[JUPITER]`

- gap > 200 ms (baseline cadence 20 ms) ⇒ held
- on resume, measure the gap duration
- coalescing window after release distinguishes tap / double-tap / triple-tap
  and the tap+hold combinations

This is the subtlest part of the protocol and the easiest to get wrong; see
`docs/NAVIGATION.md` for why the companion app maps gestures here rather than
through Android's normal input path.

## 8. Open questions for the Ntorq specifically

1. ~~GATT service UUID — identical, or a variant?~~ **Answered:** identical,
   verified in `NtorqBLEConnectionConfig`.
2. Does the Ntorq require the `0x9A` handshake? `byteArrayForUnLock` builds
   `START_BYTE_9A + DATA_ID_F1 + … + shortHandByteArray` padded to 20 with a
   `-1` terminator, and `U388ChallengeFrameData` shows the challenge shape.
   Whether Ntorq enforces it is untested — the TechEnclave consensus is that
   there is effectively no real key.
3. Pictogram byte table — the official table is in `Pictogram.kt`, but
   `ManeuverUtils.getShortInstruction()` branches on
   `KEYS.VEHICLETYPEID == "22"` with a *different* table. Which branch the
   Ntorq takes is the open question.
4. Does the Ntorq accept `0x4C`/`0x63` text packets, and how many characters
   fit? The Jupiter's 17 is unlikely to hold on a 5-inch TFT.
5. Navigation data id: `0x4E` (`DATA_ID_NAVIGATION_CONTROL`) or `0x49`
   (`DATA_ID_NAVIGATION_CONTROL_2`)?

All five need a capture — see `docs/CAPTURE.md`.