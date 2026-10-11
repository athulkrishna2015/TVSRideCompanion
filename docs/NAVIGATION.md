# Navigation

How turn-by-turn gets from any maps app onto the cluster.

## The key architectural fact

The official app's outbound surface splits cleanly in two:

| Over BLE only | Requires TVS cloud (`iceApiService`) |
| :--- | :--- |
| `sendNavigationData` | `sendWeatherData` |
| `sendNavigationInstructions` | `sendCricketData` |
| `sendNavigationAssistOn` | `sendFootballData` |
| `sendManuverString` | `sendNewsData` |
| `sendCustomTextLineOne/Two` | `sendTrafficSequence` |
| `sendDestReachedCustomText` | |
| `sendMobileData` (keepalive) | |
| `sendVehicleMode` | |

Navigation and custom text never touch the network. That is the whole reason
this app can run with no account: everything the dashboard shows is produced
locally and pushed over BLE. The cloud only adds *remote* and *content* features,
which this project drops by design. See `docs/SCOPE.md`.

## Source: the maps app, not a maps SDK

`NotificationListenerService` in the official app (`com.tvs.ble.feature`)
intercepts navigation notifications. That is what makes it provider-agnostic:
any app that posts a turn-by-turn notification in the conventional shape works.

For Google Maps the payload of interest is:

- `EXTRA_TITLE` — the maneuver, e.g. "Turn left"
- `EXTRA_TEXT` — the street, e.g. "Onto NH44"
- `EXTRA_LARGE_ICON` — the maneuver bitmap, when the provider supplies one
- `EXTRA_BIG_TEXT` / ongoing notification text — distance and ETA

MapMyIndia (also present in the official APK) posts its own shape; Organic Maps
follows the Android navigation notification convention.

The official app bundles Mappls (`com.mappls.*`) — that is the *server-side*
turn engine and needs an API key, so it is out of scope here.

## The Ntorq 125 cluster's display zones

From a photograph of the owner's own instrument cluster, captured in
display-test mode with all segments lit. This is **observation of hardware**,
not of the protocol — but it bounds what the protocol has to drive.

| Zone | Displayed as | Our decoded field |
| :--- | :--- | :--- |
| Trip selector | `TRIP A / B` | `SELECTED_TRIP` |
| Distance | `ODO / TRIP A / TRIP B`, km | `ODOMETER` (SM1 idx 3-5), `TRIP_F_METER` (idx 14-16) |
| Timer | `MIN / SEC / MILLI` | `LAP_TIME`, `TIME_60KMPH` |
| **Avg speed** | `AVG SPEED` km/h | `AVERAGE_SPEED` (SM1 idx 7) |
| **Top speed** | `TOP SPEED` km/h | `TOP_SPEED` (SM1 idx 9) |
| **0-60** | seconds | `TIME_60KMPH` (SM1 idx 13) |
| Speed | large central digits, `KM/H` | `SPEED` (SM1 idx 2) |
| Fuel | two gauges — an `H-E-F` bar and a segment bar with a pump icon | `FUEL_LEVEL` (SM1 idx 6) |

Two dedicated icon zones sit on the lower right: a **navigation/location pin**
and a **phone/notification** glyph. Both have a display region, so this cluster
is built to show caller ID and turn-by-turn.

This independently corroborates the byte offsets — `AVG SPEED`, `TOP SPEED` and
`0-60` are exactly the three values the official app's Vehicle Overview screen
renders from `NtorqSpeedOMeter1`.

### What the cluster actually displays — TVS's own list

The table above comes from a photograph. TVS's Ntorq 125 connected-cluster
manual enumerates the display elements outright, which settles a question the
photo could not: **which decoded fields are displayable at all.**

Street mode (the riding mode):

| Manual ref | Element |
| :--- | :--- |
| 1a | Odometer, km |
| 1b | Digital clock, 12-hour |
| 1c | Fuel gauge — **six** digital bars |
| 1d | Speedometer, km/h |
| 1e | Engine temperature gauge — **six** digital bars |
| 1f | Helmet icon |
| 1g | Low battery indicator |
| 1h | **Service reminder** (spanner icon) |
| 1k | Trip A, B and F |
| 1m | Side stand indicator |

Sport mode adds lap distance, lap timer and the shortest-time (0–60) indicator.
Ride stats mode adds previous/best lap distance, timer, **average speed**,
shortest time and **top speed** — but only when the bike is in sport mode and
static.

Two consequences that cost a wrong tile each:

- **Range is not on this cluster.** `DISTANCE_TO_EMPTY` decodes from `0x19`
  indexes 11–12, but the cluster has no element for it. It is an app or cloud
  figure, and this app has neither. Same for `AVERAGE_FUEL_ECONOMY`. The manual
  goes further and tells riders not to derive mileage from the fuel gauge at
  all. Neither is shown.
- **The fuel gauge has six bars, not five.** `FUEL_LEVEL` is a nibble; the
  dashboard now reports `n/6`. A clamp at 5 was silently discarding a full
  tank.

The dashboard's tile set is therefore generated from `ui/ClusterTiles.kt`, where
every entry cites a manual reference. Adding a tile needs a cluster element,
not just a byte offset we can decode.

### The text area is bigger than the Jupiter's

The bottom of the display shows **three** text rows, each rendering roughly
9-10 characters in the all-segments-on pattern. The Jupiter reference
implementation targets a **single 17-character row**.

That is a genuine divergence, not a detail:

- The 17-byte text payload (`0x5B 0x4F` / `0x4C` / `0x63`) is proven for the
  Jupiter.
- This cluster has a visibly larger, multi-row text region.
- Whether it accepts the same payload across three rows, wants a row-selecting
  byte, or wants a longer payload, is **unknown**.

`0x5B 0x63` is 99 — `ARTIST_NAME_PACKET` upstream — and may be the row-selecting
mechanism for exactly this.

**Do not assume 17 characters on this cluster.** Capture and count; see
`docs/CAPTURE.md` §3.

## Packet layouts (proven)

Transcribed from `BleNavigationPacketBuilder` in the working third-party Jupiter
app. These are **not inferred** — that app drives a real cluster over the same
GATT service this project uses.

### Control packet — `0x5A 0x4E`, 20 bytes, no checksum

```
[0]      0x5A        start byte (START_BYTE_CONTROL = 90)
[1]      0x4E        DATA_ID_NAVIGATION_CONTROL = 78
[2..3]   distance to turn, metres   saturating uint16; >= 65535 -> 0xFFFF
[4..5]   remaining time, minutes    uint16
[6..8]   remaining distance, metres uint24; if <= 0, reuses distance-to-turn
[9]      maneuver id (pictogram)
[10]     text rows in use            (upstream hardcodes 1)
[11]     active flag                 1 = navigating
[12..18] zero
[19]     0xFF        terminator
```

The start byte is `0x5A` for the control frame but `0x5B` for the text frame —
not a typo in the reference app, and easy to get wrong.

**No checksum byte.** Only the mobile-data keepalive carries one. An earlier
draft of this repo added a checksum to the nav frames; that was wrong and has
been corrected in `PacketBuilder.navigationControl`.

### Text packet — `0x5B 0x4F`, 20 bytes, no checksum

```
[0]      0x5B        start byte (START_BYTE_TEXT = 91)
[1]      0x4F        DATA_ID_NAVIGATIONDATA1 = 79
[2..18]  17 characters of UTF-8, zero-padded
[19]     0xFF
```

`0x50` is the second text channel (`DATA_ID_NAVIGATIONDATA2`). Message rows use
`0x4C` (76) and `0x63` (99).

> Text is truncated to 17 **characters**, then encoded — so a multibyte
> character can overrun the field and displace the terminator. `PacketBuilder`
> clamps by bytes as well as characters.

### Text cycling

The reference implementation alternates on a 5-second boundary,
`(System.currentTimeMillis() / 5000) % 2 == 0`:

- street name on one phase
- `time` + padding + `distance` on the other, right-aligned to 11 characters:
  `"1h 20m"`, `"45m"`, `"850m"`, `"12km"`, `"120k"` for >= 100 km

## What still needs the Ntorq

The layout is proven on the Jupiter. The open question is whether the Ntorq's
larger TFT accepts the same 17-character text field or expects more, and whether
its pictogram set matches. See `docs/CAPTURE.md` §3.

## Pictogram IDs

`Pictogram.kt` holds the full table transcribed from the official
`ManeuverUtils.getShortInstruction()`. Notable points:

- **The upstream switch has a bug-shaped quirk**: `case 21` returns
  `continue_straight`, identical to `case 7`. Preserved as-is rather than
  "fixed", because the cluster may genuinely treat 7 and 21 as the same glyph
  under different conditions, and changing it would hide that.
- **Roundabouts are 65–71**, an exit bearing in 45° steps. India drives left,
  so these are clockwise. The Jupiter report documents a separate 82–88 block
  for counter-clockwise/right-hand traffic; the official Ntorq-path table does
  not contain those, which is consistent with an India-only cluster image.
- **Highway exit/enter appears twice** — as `9`/`10` in the main switch and as
  `34`/`35` in the default branch. Both kept.
- `41` is special-cased *before* the switch in both vehicle branches, so it is
  treated as a U-turn that predates the table.

### Conflict with the Jupiter table

The Jupiter report's table differs where the two overlap:

| ID | Official app | Jupiter report |
| :--- | :--- | :--- |
| 1 | sharp left turn | slight left (down) |
| 2 | slight left | slight left (up) |
| 13 | left at junction | left branch off straight |
| 15 | left at fork | Y-junction left |
| 21 | continue straight | crossroad |

The official table is preferred: it is the vendor's own current mapping, while
the Jupiter table is one owner's empirical work on a different cluster. A user
override table (below) settles it per device.

## Two-tier resolution

Tier 1 — explicit override, stored per device.
Tier 2 — `ManeuverResolver.fromText()` keyword matching.

The official app does not do the hash-harvesting the Jupiter companion app
documents; it ships a fixed table plus the string resource names. Simpler, and
the table can be patched without a firmware flash.

## Calibration

`NtorqCalibrationResponse` (data id `0x37`) and
`JupiterCalibrationResponse` expose:

```
byte 1   data id (0x37)
byte 2   CALIBRATION_COMMAND
byte 3   CALIBRATION_ID
byte 4   DATA_LENGTH
bytes 5-8 CALIBRATION_DATA_1..4
byte 9   CHECKSUM
byte 10  reserved[9]
byte 19  end byte
```

Commands seen upstream: `MODIFY_CALIBRATION_PARAMS`, `WRITE_CALIBRATION`,
`WRITE_REQUEST_CALIBRATION`, `READ_CALIBRATION`.

Calibration ids: `FOLLOW_ME_HEAD_LAMP_CALIBRATION_ID`,
`TSL_ON_OFF_CALIBRATION_ID`, `IDLE_START_STOP_CALIBRATION_ID`.

This is the mechanism for pushing a corrected pictogram table to a cluster that
mis-renders one. It is the right hook for the override UI. **Untested on
hardware** — see `docs/CAPTURE.md`.

## Picture-in-picture / caller image

`NtorqCallerImageResponse` declares the same layout as the calibration response,
which suggests the cluster reuses that frame shape for image transfer. The
official app has `DELAY_FOR_WIFI_CALLER_IMAGE_TRANSFER` and
`updateProfilePictureToCluster`, so caller images are a Wi-Fi-mirrored feature
(`WIFI_MIRRORING_PACKET`). Out of scope for v1.

## Conflict with the Jupiter table

The pictogram table in `Pictogram.kt` comes from the **official** app. The
third-party Jupiter app ships its own mapping in `NavigationTurnData`, and it
differs. Both are recorded below rather than merged silently — the reference
app's is the one known to work on hardware, the official one is the vendor's
current intent.

| ID | Official app | Jupiter companion |
| :--- | :--- | :--- |
| 0 | turn left | turn left |
| 5 | slight right | continue straight |
| 7 | continue straight | slight right |
| 8 | destination | continue straight |
| 13 | left at junction | left branch off straight |
| 15 | left at fork | Y-junction left |
| 21 | continue straight | crossroad |
| 41 | U-turn (pre-switch) | roundabout sharp right |
| 65 | roundabout exit 45° | roundabout slight left |
| 68 | roundabout exit 180° | roundabout straight |
| 88 | (absent) | roundabout U-turn, counter-clockwise |

The official app's own switch also has `case 41` returning `make_a_u_turn`
before the switch in both vehicle branches, while the companion app assigns 41
to a roundabout bearing. These are two different tables, not one with typos.

`PacketBuilder`/`Pictogram.kt` currently use the **official** table. If the
Ntorq renders arrows wrongly, flipping to the companion table is the first
thing to try — see `docs/CAPTURE.md` §3.