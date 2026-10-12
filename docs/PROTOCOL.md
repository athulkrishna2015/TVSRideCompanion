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

`BtData.isValidFrame()` accepts only start bytes `0x9B` and `0x5A` inbound.

### Jupiter, for comparison

| Data ID | Offset | Field | Scaling |
| :--- | :--- | :--- | :--- |
| `0x10` | 3–5 | Odometer | uint24, ÷ 10 → km |
| `0x10` | 6 | Fuel | lower nibble bars; upper nibble reserve |
| `0x19` | 8 | Economy | km/L |
| `0x19` | 11–12 | Distance to empty | |

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