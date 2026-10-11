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
Service UUID : 5456534D-5647-5341-5342-454E544F5251   [JUPITER] [UNVERIFIED for Ntorq]
Write char   : 00005352-0000-1000-8000-00805f9b34fb   [JUPITER] [UNVERIFIED]
Read char    : 00005354-0000-1000-8000-00805f9b34fb   [JUPITER] [UNVERIFIED]
```

That service UUID is the ASCII string `TVSM-VGSASBEN TORQ` read byte-wise —
note the embedded **"TORQ"**. That is the single strongest hint that the same
service is shared across the SmartXonnect line rather than being Jupiter-only,
but it is still a hint, not a measurement. Confirm with a GATT dump from the
Ntorq itself (`docs/CAPTURE.md` step 2).

The `5352` / `5354` write/read characteristic pair is the Nordic UART-style
naming convention (`SR`/`ST`) and is what the official app writes to.

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

`[JUPITER]` — these are what the cluster streams **to** the phone.

| Data ID | Offset | Field | Scaling |
| :--- | :--- | :--- | :--- |
| `0x10` | 3–5 | Odometer | uint24, ÷ 10.0 → km |
| `0x10` | 6 | Fuel | lower nibble 0–5 bars; upper nibble reserve flag |
| `0x10` | 13 | Call command | 1 = answer, 2 = reject |
| `0x11` | 4 | Service reminder | indicator |
| `0x19` | 8 | Economy | km/L |
| `0x19` | 11–12 | Distance to empty | |
| `0x54` | 2 | Music command | *"legacy Ntorq-style button press fallback"* |
| `0x6B` | 2 | Dialer command | answer/reject from cluster |

`0x10`, `0x11`, `0x18`, `0x19` are only broadcast with the ignition ON — which
is how the companion app distinguishes Parked from Ride without any other
signal. `[JUPITER]`

`0x54` is the one entry the Jupiter report explicitly ties to Ntorq. It needs
investigation: it may be a Ntorq carry-over retained on Jupiter, or the reverse.

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

1. GATT service UUID — identical, or a variant?
2. Does the Ntorq require the `0x9A` handshake at all? The TechEnclave group
   reports no real key; the handshake may be Jupiter-only ceremony.
3. Are the `0x10`/`0x19` offsets identical, or has the Ntorq's larger TFT changed
   the telemetry layout?
4. Pictogram byte table for the Ntorq's 5-inch TFT — almost certainly **not**
   the Jupiter table.
5. Does the Ntorq accept `0x4C`/`0x63` text packets?
6. What is `0x54` actually doing on this platform?