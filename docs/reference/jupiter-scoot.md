# Reference: Scoot's Jupiter protocol spec (third-party source)

Working reference for the Jupiter-family byte maps. This is the material the
`JupiterDriver` and `ClusterFrames` were built from, kept in the repo so a
future change does not have to re-download it.

**This file is a condensed restatement, not a copy of the upstream prose.**
The upstream document is someone else's internal tooling documentation; the
tables below are the protocol facts we use, with the caveats they attach to
each one.

## Provenance

| | |
| :--- | :--- |
| Project | Scoot, a closed-source TVS companion app (Flutter) |
| Source of the protocol notes | `sudo-Mystic/Scoot-releases`, `sim/docs/PROTOCOL.md` |
| Second pass | `sim/docs/tvs-addendum.md` (independent bytecode read of TVS Connect v8.12.4) |
| Executable form | `sim/src/engine/frames.js` (builders/decoders used by their cluster simulator) |
| APK compared | `app-arm64-v8a-release.apk`, Scoot 0.5.10, SHA-256 `58d74a4d82c63e0df262d3d03b32a96db32700612f99b2da93a2a7e153140a93` |
| Feed manifest | `stable/manifest.json` in the same repo, same SHA-256 |

The upstream notes say they were extracted from the Scoot app's own protocol
layer and dex-verified against TVS Connect v8.12.4. They mark anything they
could not verify as UNVERIFIED; those marks are preserved here.

**Do not treat this as a first source.** The owner's Ntorq is the first source
for Ntorq behaviour. Where the two disagree, see
[Contradictions with our own measurements](#contradictions-with-our-own-measurements).

## Frame anatomy

Every frame is 20 bytes unless stated otherwise.

```
phone -> cluster   0x5B  id  payload(16)  cksum 0xFF
cluster -> phone   0x5A  id  payload(16)  info  0xFF
```

- Inbound start bytes accepted by the reference implementation: `0x5A` primary,
  `0x9B` alternate, `0x5B` seen in RE captures.
- Byte 19 is always `0xFF`.
- Outbound byte 18 is the checksum. Inbound byte 18 is **not** a checksum and is
  never validated; inbound validation is structural only (length, start byte,
  end byte).
- GATT: service `5456534D-5647-5341-5342-454E544F5251`, write
  `00005352-…`, notify `00005354-…`. UUID comparisons are lowercase.

### Checksum

```
checksum = 255 - (sum(bytes[0..17]) % 256)          // byte 18, outbound
calibration: (256 - (sum(bytes[2..8]) % 256)) & 0xFF // byte 9 of 0x73
```

## Inbound frames (cluster -> phone)

### 0x10 primary telemetry

| Byte | Field | Notes |
| :--- | :--- | :--- |
| 2 | speed, km/h | PROVISIONAL mapping |
| 3–5 | odometer, UInt24 BE | km = raw / 10, scale PROVISIONAL |
| 6 | fuel byte | model-gated, see below |
| 7 | average speed, km/h | |
| 8 | ECO / POWER | 0 = Economy, 1 = Power |
| 9 | top speed, km/h | |
| 10 | throttle position | raw |
| 11 | backlight / pair mode | low nibble street/sport UNVERIFIED |
| 12 | ignition + call switch | 128 = IG on, 64 = off; 1 accept, 2 reject, 3 reject+SMS, 4 voice assist |
| 13 | 0–60 raw | not a usable time on Jupiter |
| 14–16 | trip meter, UInt24 BE | km = raw / 10 |
| 17–18 | engine RPM, UInt16 BE | |

**Fuel byte, dex-verified split behind `JUPITER_U566`:** the 125 extracts the
low nibble (`0x3C -> 12`, `0x35 -> 5`, `0x80 -> 0`); the 110 passes the raw byte
through. Bar interpretation and the reserve rule are the app's own and remain
PROVISIONAL.

### 0x11 service / diagnostics

| Byte | Field |
| :--- | :--- |
| 2 | fuel sensor failure |
| 3 | vehicle state 1 |
| 4 | service reminder (nonzero = due) |
| 6 | ISS high/low + vehicle state 2 (bit layout undecoded) |
| 7 | speedo software version |
| 8 | MIL blink code |
| 9 | vehicle model numeric (values not in the dex) |
| 10 | vehicle diagnostics; bit 2 = MIL state |
| 11 | ISG blink code |
| 12 | turn indicator status |
| 13 | connector status |
| 14 | cluster theme |
| 17 | capture screenshot |

### 0x18 engine telemetry

| Byte | Field |
| :--- | :--- |
| 2 | engine load |
| 3–4 | accumulated fuel injection time, UInt16 BE |
| 5 | manifold air pressure |
| 6 | barometric pressure (U714 variant) |
| 7 | intake air temperature |
| 8 | engine temperature |
| 9–10 | barometric pressure, UInt16 BE |
| 11 | battery voltage, **raw × 0.1 V**, no offset (121 -> 12.1 V) |
| 12–13 | engine running time, UInt16 BE |
| 14–15 | distance travelled, UInt16 BE |
| 16–17 | fuel injection volume, UInt16 BE |

### 0x19 economy

| Byte | Field |
| :--- | :--- |
| 2–4 | ISS duration, 24-bit (units undecoded) |
| 5 | OTA status |
| 8 | average fuel economy, km/L |
| 9 | instantaneous fuel economy, km/L |
| 11–12 | distance to empty, UInt16 BE, km |
| 15–17 | ISS count, 24-bit |

### 0x6B music button

Command at byte 2. Same map on both families.

| Value | Command |
| :--- | :--- |
| 0 | play |
| 1 | pause |
| 2 | toggle |
| 3 | next |
| 4 | previous |
| 5 | volume up |
| 6 | volume down |

In stock, Jupiter only logs `0x6B`; Ntorq is the family that acts on it. This
app acts on both, the Jupiter case being a deliberate addition.

### 0x54 doc / image transfer

`byte 2` = picture number, `bytes 5–6` = big-endian 16-bit frame number. Not
decoded; count as known.

## Outbound frames (phone -> cluster)

### 0x4A mobile data / keepalive

| Byte | Field |
| :--- | :--- |
| 2 | signal (upper nibble) \| phone battery bucket (lower nibble; `ceil(pct/25)`) |
| 3 | overspeed limit: 120 default on Jupiter (70 Zest) |
| 4 | ambient temperature + 40 (40 when unknown, i.e. 0 °C) |
| 5 | `0x00` |
| 6–9 | hour (12-hour, 1–12), minute, second, AM/PM (0 = AM, 1 = PM) |
| 10 | missed calls |
| 11 | network type |
| 12–14 | day, month (1–12), year mod 100 |
| 15 | `0x00` |
| 16 | `0x00` on Jupiter (voice-assist byte is Ntorq-gated) |
| 17 | find-me: 1 for ~10 packets after a trigger |
| 18 | checksum |

The clock block must stay at 6–9: moving it set the cluster clock to 12:04 on
every connect, which is also the failure our own `keepalive` rewrite fixed.

### 0x4C / 0x63 custom text rows

- Row 1 `5B 4C <up to 17 bytes> 00-pad FF`, row 2 `5B 63 …`.
- Official sanitisation: emoji/symbols -> `@`, anything outside
  `[A-Za-z0-9 space @]` -> `X`, trimmed, truncated to 17 UTF-8 bytes without
  splitting a character.
- Longer notifications paginate into 17-char pages, cycled about every 3 s.
- A blank line is skipped so the other row is not blanked.

### 0x4F navigation text

`5B 4F <text, up to 17 bytes> 00-pad FF`. Padding is **`0x00`**, not `0xFF`:
`0xFF` inside the text region renders as garbage glyphs. Stock hard-cuts 17
characters with no sanitisation; the reference implementation sanitises and
prefers a word boundary, which is what our `NavSanitizer`/`NavShortCodes` do.

### 0x5A 0x4E navigation control

Byte 0 is **`0x5A`**, not `0x5B`. Byte 1 is `0x4E`.

| Byte | Field |
| :--- | :--- |
| 2–3 | distance to maneuver, metres, **UInt16 BE** |
| 4–5 | ETA, minutes, **UInt16 BE** |
| 6–8 | remaining trip, metres, **UInt24 BE** |
| 9 | pictogram |
| 10 | row count, 1 or 2 |
| 11 | nav status, `0x01` |
| 12–18 | padding |
| 19 | `0xFF` |

Framing beyond these bytes is UNVERIFIED. **This is where our byte order
changed**: we had 2–3/4–5/6–8 little-endian.

### 0x43 caller info (three packets)

Each packet: `5B 43 subId <UTF-8 text> 00-pad to 17 chars FF` — 21 bytes.

| subId | Content |
| :--- | :--- |
| `0x01` | national number |
| `0x03` | country code digits |
| `0x02` | contact name, only when non-empty |

Order sent by stock: `01`, `03`, `02`. Padding is computed from
`String.length()` (chars) while the text is UTF-8 encoded, so non-ASCII text can
produce a longer packet than 20 bytes; the reference implementation mirrors that
rather than fixing it.

### 0x50 pictogram

`[5B, 50] + UTF-8 name + zero pad to 17 chars + [FF, 00]` — **21 bytes**, the
two-byte tail being verbatim from the dex.

### 0x61 / 0x62 / 0x65 music metadata

`[5B, id] + UTF-8 text (16 bytes) + [FF] + zero pad + [00]`, always 20 bytes.
The id -> title/artist/album mapping is **UNVERIFIED**; our own constants
transcription disagrees with this one.

### 0x52 rider name

`5B 52 <name up to 17 bytes> 00-pad FF`. Same sanitisation and padding as the
text rows. Builder-verified; cluster acceptance UNVERIFIED on device.

### 0x22 first-connection user id

`[5B, 22] + BigInteger.toByteArray(userId) + 0xFF pad + [FF]`, exactly 20 bytes.

- A single `0x00` is prefixed when `toByteArray()` is two bytes or shorter.
- The pad fill is **`0xFF`**, not `0x00`. (The reference app's Dart source
  zero-pads; the dex fills with `0xFF` and the dex wins.)
- The reference app never sends it automatically; neither do we.

### 0x53 SMS notification

`[5B, 53] + UTF-8 text + zero pad to 17 chars + [FF]`. Model-gated upstream to
U388/iQube, never sent to Jupiter. Exposed here, not wired.

### 0x5A 0xF1 vehicle control (brightness)

| Byte | Field |
| :--- | :--- |
| 0 | `0x5A` |
| 1 | `0xF1` |
| 2 | brightness wire byte: level 1 -> 1, levels 2–5 -> level × 2 |
| 3 | 9 |
| 4 | `0x00` |
| 5 | 2 |
| 6–8 | `0x00` |
| 9 | phone day |
| 10 | `0x00` |
| 11 | phone month − 1 (zero-based) |
| 12 | `0x00` |
| 13 | phone year & `0xFF` |
| 14 | `0x00` |
| 15 | 1 |
| 16 | `0x01` |
| 17–18 | `0x00` |
| 19 | `0xFF` |

### 0x60 play/pause state

`[5B, 60, 00, currentHour, 00, 00, 00, stateByte, 00 × 11, FF]`. State-byte
values UNVERIFIED; Jupiter applicability UNVERIFIED (the stock dispatcher picks
a different builder per bike type).

### 0x73 calibration

`[5B, 73, b2, b1, 04, value LE32, checksum, 00 × 9, FF]`, checksum at byte 9 over
bytes 2–8. The value is **little-endian**, the one exception in the protocol.

`b1`: 9 idle start-stop timer, 18 follow-me headlamp timer, 23 TSL auto-cancel
timer. Cluster acceptance UNVERIFIED. Calibration changes cluster behaviour and
must only be sent on explicit owner instruction.

Alternate follow-me encoding, kept for a live test:
`[5B, 73, 0A, 12, 02, 00, 00, hi, lo, cksum, 00 × 9, FF]` with the timer in
big-endian seconds and cksum `0x3D`/`0x74`/`0xAB` for lo 16/32/other.

## Maneuver catalogue

Exhaustive per the upstream navigation architecture note.

| Id | Maneuver | Id | Maneuver |
| :--- | :--- | :--- | :--- |
| 0 | sharp left | 17 | merge left |
| 1 | slight left (alt) | 18 | merge right |
| 2 | slight left | 19 | exit left |
| 3 | sharp right | 20 | exit right |
| 4 | slight right (alt) | 21 | crossroad |
| 5 | slight right | 40 | no entry |
| 6 | U-turn left | 41 | U-turn right |
| 7 | straight | 65 | roundabout slight left |
| 8 | arrived | 66 | roundabout sharp left |
| 11 | T-junction left | 67 | roundabout slight right |
| 12 | T-junction right | 68 | roundabout straight |
| 13 | left branch | 69 | roundabout right |
| 14 | right branch | 70 | roundabout sharp right |
| 15 | fork left | 71 | roundabout U-turn |
| 16 | fork right | 82–88 | right-hand roundabout set |

India drives on the left, so a roundabout's first exit is a left maneuver.

## Session lifecycle

1. **Scan.** May be filtered by service UUID or unfiltered. Filtered scanning
   hides scooters that serve GATT without advertising the UUID, so the reference
   app scans unfiltered and ranks hints (name contains jupiter/tvsbt/tvs, or the
   service UUID). A name is a hint only.
2. **Connect.** MTU 515 requested on Android, bounded to 2 s; failure does not
   abandon the session.
3. **Discover,** bounded to 12 s. **Verified-open gate**: exact service UUID
   with both characteristics; otherwise any service exposing *both*
   characteristic UUIDs. Service UUID present but a characteristic missing is a
   hard failure, not a fallback.
4. **Subscribe** the notify stream *before* writing the CCCD, because the
   cluster can emit the `0x9A 0xF2` challenge the moment the descriptor is
   written.
5. **Auth:** wait up to 10 s for a `0x9A 0xF2` challenge, **detect only, never
   answered**.
6. **Keepalive `0x4A`:** immediate kick, then every 2 s. Parked clusters answer
   only to this; ride telemetry needs ignition on. Writes are serialised through
   one chain (parallel writes cause status-133 disconnects).
7. **Ready:** parked-level ready = verified GATT + subscribed + auth attempted +
   ping flowing. Ride-level ready needs the first `0x10`/`0x11`/`0x18`/`0x19`.
   Stale-link watchdog: no rx for 10 s emits degraded; any live frame clears it.
   RSSI polled every 15 s.
8. **Close / reconnect:** shut down notify, write chain, ping loop, nav refresh
   and paged text. Unexpected drops retry immediately, then 5/10/20/40/60/60…
   seconds, ten attempts, then emit closed.

## Timing constants

| Stream | Cadence |
| :--- | :--- |
| Telemetry 0x10/0x11/0x18/0x19 | ~20 ms (about 50 Hz) |
| 0x4A keepalive | every 2 s |
| Nav control 0x5A4E HUD keepalive | every 1 s (official app ~1.5 s) |
| Text page cycle | 3 s |
| Nav instruction burst | 200 ms apart, text frame first, then control |
| Auth challenge wait | 10 s timeout |
| Button gesture | >200 ms stall = held; 200–550 ms gap = tap; >550 ms = hold; taps within 350 ms coalesce |

## Safety boundary

The upstream spec is explicit and this project matches it: telemetry, display
and mirroring only. No lock/unlock, immobiliser, ignition cut, throttle or brake
control. The `0x9A/0xF2/0xF1` path is detect-only. No key material is
documented or reproduced here, and none is used by this app.

## Contradictions with our own measurements

Recorded, not resolved by preference. The owner's hardware wins for Ntorq.

| Topic | Our observation | This spec | Resolution |
| :--- | :--- | :--- | :--- |
| Ntorq outbound masking | plaintext frames drive the cluster | Ntorq XOR-masks every outbound frame with `0xEB` | **Not applied.** Our link is verified sending plaintext. |
| Ntorq keepalive | a `0x4A` ping every 2 s is what holds the link | Ntorq has no keepalive loop at all | **Not applied.** Keepalive stays on. |
| `0x50` | second navigation text row (`DATA_ID_NAVIGATIONDATA2`) | pictogram-by-name, 21 bytes | Split per driver: Ntorq text row, Jupiter pictogram. |
| Nav control byte order | little-endian, from a report transcription | big-endian | **Changed to big-endian.** Everything else in the protocol is BE. |
| `0x61/0x62/0x65` roles | album / artist / artist-new | title / artist / album | Builder takes the id explicitly; mapping stays UNVERIFIED. |
| 0x4A byte 4 | `0x00` | ambient temperature + 40 | Parameterised: zero by default, ambient on Jupiter. |

## What is implemented here, and where

| Item | Code |
| :--- | :--- |
| Jupiter inbound maps | `protocol/JupiterFrames.kt` |
| Caller 3-packet, pictogram, media metadata, brightness, user id, SMS, play/pause, calibration | `protocol/ClusterFrames.kt` |
| Media keys, call switch | `transport/ClusterButtons.kt` |
| Verified-open gate | `ScooterBleService.resolveVerifiedService` |
| Reconnect backoff | `RideService.scheduleReconnect` |
| Byte-order decision | `docs/PROTOCOL.md` §9 and `PacketBuilder.navigationControl` |

## Still unverified

Jupiter speed mapping and odometer scale; fuel-bar meaning; cluster acceptance
of `0x52`, `0x53`, `0x73`; the media-metadata id mapping; and answering a call
from the cluster, which needs the default-dialer role this app does not take.