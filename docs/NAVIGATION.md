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

## Open questions

1. Does the Ntorq accept the navigation control frame at all?
2. `DATA_ID_NAVIGATION_CONTROL = 78` (0x4E) and
   `DATA_ID_NAVIGATION_CONTROL_2 = 73` (0x49) — which does the Ntorq use?
3. Text row 1 is `DATA_ID_4C` (76), row 2 is `DATA_ID_63` (99). The Jupiter
   report describes 76/99 as message rows and 0x4F/0x50 as nav text. Confirm.
4. Character width: the Jupiter cluster shows 17 characters. The Ntorq's 5-inch
   TFT is larger — is it still 17, or 21+?
5. Is the pictogram table vehicle-type-22 specific? `KEYS.VEHICLETYPEID == "22"`
   selects a *different* branch, and finding the Ntorq's vehicle type id is a
   prerequisite for using the right table.