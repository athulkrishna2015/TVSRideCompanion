# Capture plan

Everything that cannot be settled without the scooter in hand. Each item says
what to capture, why it is needed, and what it unblocks.

Run these when hardware is available. Until then the corresponding facts stay
`Confidence.NEEDS_CAPTURE` in code and are not guessed.

## 0. Prerequisite: is this Ntorq BLE or classic SPP?

`research/refs.txt` raises a real question: whether the target Ntorq uses BLE
GATT or classic Bluetooth RFCOMM/SPP. **The official APK answers this for the
models it supports** — `NtorqBLEConnectionConfig` declares BLE service
`5456534D-…` with write/read `00005352-…` / `00005354-…`, and the U388 EV is the
only classic-BT variant (`U388_SERVICE_UUID_KEY`). So a SmartXonnect Ntorq is
BLE.

But confirm, because "NTORQ 150 SX" spans variants:

1. Pair with the official app once.
2. `adb shell dumpsys bluetooth_manager | grep -A20 Bonded`
3. Or, better, nRF Connect / `BluetoothLeScanner` and look for the service
   `5456534D-5647-5341-5342-454E544F5251`.

If the service is absent, it is a different generation — re-check which
`*BLEConnectionConfig` the official app selects for it.

## 1. HCI snoop log — the highest-value capture

This gives ground truth for everything inbound, and lets us confirm the
outbound frames the official app sends.

```
adb shell settings put global bluetooth_hci_snoop_log_mode full
adb shell settings get global bluetooth_hci_snoop_log_mode   # expect: full
```

Then: start TVS Connect, pair, ride, trigger in order —

1. ignition on, stationary
2. navigation: a left, a right, a roundabout, arrival
3. a phone call in, answer it, end it
4. play music, pause, next track, volume up/down
5. press the cluster button (short, long, double)
6. ignition off

Pull and decode:

```
adb shell "cat /data/misc/bluedroid/btsnoop_hci.log" > btsnoop_hci.log
# or on API 29+: adb exec-out 'cat /data/misc/bluedroid/btsnoop_hci.log' > btsnoop_hci.log
tshark -r btsnoop_hci.log -Y 'btatt.handle==0x52 || btatt.handle==0x5352' -T fields -e frame.time -e btatt.handle -e data.data
```

Filter on ATT handle `0x52` (write) / `0x54` (read) for the SmartXonnect
service, or just filter ATT writes and look at the payloads that start
`5B 4A`, `5B 4E`, `5A`.

> On Android 12+ the log may land in `/data/misc/bluedroid/` with a timestamp
> suffix. `adb shell ls /data/misc/bluedroid/` to find it. Some OEMs require the
> app to be allowlisted in `bluetooth_hci_snoop_log_mode` settings.

### What to extract

| Question | Where to look |
| :--- | :--- |
| Does the cluster notify on `0x5354`? | ATT notification, any data id |
| Which data ids actually appear? | leading bytes of inbound frames |
| Exact ping cadence | timestamps of `5B 4A` writes |
| Does the nav packet use `4E` or `49`? | `sendNavigationData` writes |
| Text row ids and width | long street names while navigating |
| Cluster button encoding | inbound frames when pressing the button |

## 2. Confirm the frame layouts

Our tables come from the APK annotations. The snoop log confirms them.

- `0x10` (16): expect start `5A`, id `10`, speed at index 2, odometer at 3–5.
  Cross-check by comparing the odometer against the cluster's own reading.
- `0x11` (17): vehicle state, software version at index 7.
- `0x19` (25): `AVERAGE_FUEL_ECONOMY` index 8, `DISTANCE_TO_EMPTY` 11–12.
- `0x18` (24): engine diagnostics block.
- `0x37` (55): calibration.

## 3. Navigation on the Ntorq

The big unknown. The official app has a full nav implementation
(`sendNavigationData`, `sendNavigationInstructions`,
`sendNavigationAssistOn`), so it does work on some cluster.

- Which data id: `0x4E` or `0x49` (`DATA_ID_NAVIGATION_CONTROL_2`)?
- Where does the pictogram byte sit? The Jupiter report says byte 9 of a 20-byte
  frame; verify.
- Is the text channel `0x4C`/`0x63`, or `0x4F`/`0x50`?
- **Character width.** The Jupiter shows 17. The Ntorq's 5-inch TFT is much
  larger — count characters in a long street name in the snoop log.
- Roundabout pictograms: confirm 65–71 are the set the cluster actually draws.

## 4. `KEYS.VEHICLETYPEID`

`ManeuverUtils.getShortInstruction()` has a branch for `VEHICLETYPEID == "22"`
with a different table. Find the Ntorq's id — it is in the cluster's vehicle
model frame (`VEHICLE_MODEL`, `0x11` index 9) or in the app's shared prefs.

Getting this right tells us which pictogram table is authoritative.

## 5. Handshake

- Does the Ntorq emit a `9B F2` challenge at all?
- If yes, what does the official app send back? `byteArrayForUnLock` builds
  `START_BYTE_9A + DATA_ID_F1 + … + shortHandByteArray`, padded to 20 with a
  `-1` terminator. `U388ChallengeFrameData` shows the challenge shape.
- The Jupiter report documents an AES-128-CTR response with a published key.
  Confirm whether the Ntorq uses the same key or skips it.

## 6. Button gestures

The Jupiter report's "silence gap" mechanism (holding stalls the stream, gap >
200 ms = held) is remarkable and needs verifying: watch the *timestamps* of
inbound frames in tshark while pressing the cluster button in known patterns.

## 7. Find-my-scooter byte

Byte 17 of `0x4A` per the Jupiter report. Verify: capture with the byte 0, then
1, and confirm the cluster flashes and beeps.

## Tooling

- `tools/hci/` — decode helpers for the snoop log.
- REA MCP is wired up for APK work: `inspect_android_package`,
  `search_android_classes`, `inspect_android_class`, `inspect_android_method`
  against a later TVS Connect release to diff protocol changes.

## Safety

Capture on your own parked scooter, ideally with the ignition on but the
wheel off the ground or the bike on its centre stand, and never while riding.
Keep a Bluetooth sniffer out of the way of the throttle.