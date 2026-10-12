# Capture plan

Everything that cannot be settled without the scooter in hand. Each item says
what to capture, why it is needed, and what it unblocks.

Run these when hardware is available. Until then the corresponding facts stay
`Confidence.NEEDS_CAPTURE` in code and are not guessed.

## 0. Prerequisite settled: it is BLE, and there is no RFCOMM path

`research/refs.txt` claims the official app "handles both Bluetooth Classic
(RFCOMM/SPP) for core data transmission and BLE for initial setup/handshakes",
and that older Ntorq clusters use SPP. **Both are wrong** — see
`docs/PROTOCOL.md` §1a. Searching all decompiled classes for `BluetoothSocket`
and `createRfcommSocket` returns zero hits, and `NonBLEConnectionConfig` is an
unimplemented stub with empty UUID strings.

So: **ignore RFCOMM/SPP when reading the snoop log.** If RFCOMM data appears it
is not TVS traffic. Confirm the GATT service is present with nRF Connect, but do
not go hunting for a serial profile.

## 0a. Which model family is it?

Raider is bound to `NtorqBLEConnectionConfig` upstream, so a Raider works with
the Ntorq driver as-is. Once connected, read the vehicle model frame (`0x11`,
`VEHICLE_MODEL` at index 9) and record the value.

## 1. HCI snoop log — the highest-value capture

This gives ground truth for everything inbound, and lets us confirm the
outbound frames the official app sends.

```
adb shell settings put global bluetooth_hci_snoop_log_mode full
adb shell settings get global bluetooth_hci_snoop_log_mode   # expect: full
```

### Attempt on 2026-10-12: no capture, and here is why

The owner connected to the scooter with the official TVS Connect app on an
Android 15 phone (RMX3312, SDK 35) and expected a capture to exist. **It does
not.** Verified, not assumed:

| Check | Result |
| :--- | :--- |
| `settings get global bluetooth_hci_log` | `null` — never set |
| `dumpsys bluetooth_manager` → `sSnoopLogSettingAtEnable` | `EMPTY` |
| `/sdcard/Android/data/com.android.bluetooth/files/` | does not exist |
| `find /sdcard -iname '*btsnoop*'` | nothing |
| `/data/misc/bluetooth/logs/` | exists, **permission denied** (needs root) |

So HCI snoop logging was never on and nothing was recorded. A second finding
from the same session: the bonded-device list holds **only** Rockerz 333,
IBELL and MiTV-AXSO0 — all audio or TV. **No scooter is bonded.** Either the
official app paired ephemerally, or the bond was cleared afterwards. That is
worth knowing before reading any capture: if the scooter is not bonded, the
capture will show a connect with no subsequent bond, which changes how the
handshake must be read.

Both settings keys are now set on that device, but a key being writable is not
proof the Bluetooth stack reads it. The Developer options UI toggle is the
reliable switch, and only starts a fresh log on a Bluetooth off/on cycle.

### Tools

Two scripts, both gitignore-safe to keep in the tree:

```
tools/capture-hci.sh check      # is logging on, and is there a log yet?
tools/capture-hci.sh enable     # set the keys, open Developer options
tools/capture-hci.sh watch 120  # poll size while you reconnect
tools/capture-hci.sh pull       # save to captures/, gitignored
tools/decode-snoop.py <file> --frames
```

`decode-snoop.py` turns the binary into one line per ATT packet with
direction, handle, opcode name and a `<5A>`-style tag for SmartXonnect frames,
so `--frames` reduces a multi-megabyte capture to just the protocol traffic.

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

The packet layout is **no longer an open question** — `0x5A 0x4E` control and
`0x5B 0x4F` text are proven by a working third-party app over the same GATT
service. What remains is whether the Ntorq's **5-inch TFT** behaves the same:

1. Does it accept the control frame at all, or ignore it?
2. **Character width — now the key question.** A photograph of this cluster
   shows a **three-row text area** of roughly 9-10 characters per row, while the
   proven Jupiter layout uses a single 17-character row. Determine what this
   cluster actually accepts:
   - send a known 17-character ASCII string and count what appears;
   - check whether `0x5B 0x63` (99) selects a row;
   - test whether rows 2 and 3 need separate packets.
3. **Pictogram table.** The official app's table and the Jupiter companion's
   disagree on ids 5, 7, 13, 15, 21, 41 and the roundabout block. If arrows
   render wrong, that is the first suspect — both tables are recorded side by
   side in `docs/NAVIGATION.md`.
4. `KEYS.VEHICLETYPEID` — the official app branches on `== "22"` with a
   different pictogram table. Find the Ntorq's id from the `0x11` vehicle model
   frame (index 9).

Suggested order: connect, confirm telemetry decodes (that validates the whole
transport), then send one navigation step and watch the cluster.

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