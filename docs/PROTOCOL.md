# SmartXonnect BLE protocol — Ntorq 150 (SX)

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

Most units require a handshake before accepting raw commands. `[JUPITER]`

1. Scooter emits `0x9A 0xF2` + 16 random bytes via notification (challenge).
2. Client responds `0x9A 0xF1` + a randomised "shorthand" slice of
   **AES-128-CTR** (no padding) over those bytes.

Key reported in the Jupiter report: `7A A3 20 4D 16 1D B5 33 F4 EB 20 4F BC D7 3D D4`.

> This key is transcribed here because it is already public in the folder's
> report and is the same value across the product line. If Ntorq rejects it,
> that is a real finding — record it and do not brute-force.

The TechEnclave participants' consensus is that there is effectively **no real
security key**; this is a formality the cluster expects, not a secret. Treat
the handshake as protocol fidelity, not as a security boundary.

## 3. Ping / "mobile data" packet — `DataID 0x4A`

Sent continuously to keep the link alive and feed the dashboard. **Not
encrypted.** 20 bytes, plaintext, checksum-protected. `[JUPITER]`

| Byte | Field | Notes |
| :--- | :--- | :--- |
| `[0]` | Start | always `0x5B` |
| `[1]` | Data ID | `0x4A` |
| `[2]` | Signal/Battery | upper nibble = signal bars 0–5, lower nibble = battery bars 0–5 |
| `[4]` | Temperature | °C + 40 (25 °C → `0x41`) |
| `[6..8]` | Time | hour (12h), minute, second |
| `[9]` | AM/PM | `0x00` AM, `0x01` PM |
| `[11]` | Network | `0x04` typically LTE/4G |
| `[12..14]` | Date | day, month (1–12), year mod 100 |
| `[17]` | Find Me | `0x01` triggers horn + hazard flash |
| `[18]` | Checksum | `255 - (sum(bytes[0..17]) % 256)` |
| `[19]` | End | always `0xFF` |

Unlisted bytes are padding, normally `0x00`.

The Find-My flag is what makes the official app's most complained-about
feature reliable when injected deliberately: toggle byte 17 and the cluster
physically responds.

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

## 6. Outbound text messages — `0x4C` / `0x63`

Pure text with no arrow overlay, used for caller ID, song titles and app
notifications. `[JUPITER] [UNVERIFIED for Ntorq]`

| Packet | Purpose |
| :--- | :--- |
| `0x5B 0x4C` | message text row 1 (`DATA_ID_MESSAGE1 = 76`) |
| `0x5B 0x63` | message text row 2 (`DATA_ID_MESSAGE2 = 99`) |

Up to 17 UTF-8 characters per row. Longer strings are chunked and paginated.

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