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

## 0b. Try the app first — it is cheaper than the snoop log

An HCI snoop (§1) gives ground truth for both directions, but on this device it
would not start (§1a). The app can now capture the inbound half on its own, and
it is the first thing to try because it needs no Developer options, no Bluetooth
cycle and no root.

**With the ignition on:**

1. Home → **Connect**. The scan filters on the SmartXonnect service UUID
   `5456534D-5647-5341-5342-454E544F5251` for 20 s.
2. If nothing appears, switch off **Only SmartXonnect clusters** and scan again.
   That is the diagnostic: it tells us the cluster is not advertising the
   service we expect, which is a protocol finding in itself.
3. Pick the device, choose the model, connect.
4. Read the **Log** tab. Inbound frames appear as `IN <hex>`.

What each outcome settles:

| What happens | What it means |
| :--- | :--- |
| Device appears, labelled SmartXonnect | The service UUID is right; connect and the rest follows |
| Device appears, "not a cluster" | It advertises something else — record its name and UUIDs |
| Nothing appears even unfiltered | Not advertising, or not powered; try again with the ignition on |
| Connects, then `GATT disconnected` | Link established then dropped — check the keepalive is being accepted |
| Connects, frames arrive, values wrong | Byte offsets are wrong; the hex dump is the evidence |

The app's own log file can be pulled without a debugger, because the release
APK is debuggable:

```bash
adb exec-out run-as dev.tvsopen cat files/logs/tvsride.log > session.log
```

Or from the Log tab, **Export**, which bundles every rotated file into one
timestamped copy under `getExternalFilesDir("logs")` — app-scoped, so it needs
no storage permission and **no root**:

```bash
adb pull /sdcard/Android/data/dev.tvsopen/files/logs/tvsride-<stamp>.log
tools/decode-snoop.py   # n/a — this is text, read it directly
```

Verified on device 2026-10-12: exported and pulled 98 lines, rotation history
intact. Prefer this on any phone you cannot root, which is most of them.

**This has never been run against a real cluster.** Everything the scanner does
is verified only in that the platform accepts the scan and the UI renders. The
first question it answers is simply whether the Ntorq advertises the UUID we
filter on.

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
| `settings get global bluetooth_hci_log` | `null` — a different key; see Attempt 3 |
| `settings get global bluetooth_hci_snoop_log_mode` | `full` (the key the UI actually writes) |
| `dumpsys bluetooth_manager` → `sSnoopLogSettingAtEnable` | `EMPTY`, but the UI says enabled — the two disagree |
| `/sdcard/Android/data/com.android.bluetooth/files/` | does not exist |
| `find /sdcard -iname '*btsnoop*'` | nothing |
| `/data/misc/bluetooth/logs/` | exists, **permission denied** (needs root) |

> Two of these rows were misread at the time and are corrected by Attempt 3
> below: the toggle *was* on, and this device has no root at all.

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
tools/auto-capture.sh 1800      # wait for the scooter, then dump automatically
tools/decode-snoop.py <file> --frames
```

`decode-snoop.py` turns the binary into one line per ATT packet with
direction, handle, opcode name and a `<5A>`-style tag for SmartXonnect frames,
so `--frames` reduces a multi-megabyte capture to just the protocol traffic.

### Enabling snoop by adb — the Bluetooth cycle is the part that matters

Settled 2026-10-12. Both settings keys can be written and read back
successfully, and **that proves nothing**: the Bluetooth stack only samples
them when it initialises, so the log silently never starts. Observed:

```
before:  sSnoopLogSettingAtEnable = EMPTY
         settings get global bluetooth_hci_log  ->  1      (already set!)

adb shell svc bluetooth disable && sleep 8 && adb shell svc bluetooth enable

after:   sSnoopLogSettingAtEnable = FULL
```

So the working recipe, entirely by adb, without touching Developer options:

```bash
adb shell settings put global bluetooth_hci_log 1
adb shell settings put global bluetooth_hci_snoop_log_mode full
adb shell svc bluetooth disable && sleep 8 && adb shell svc bluetooth enable
sleep 12
adb shell dumpsys bluetooth_manager | grep sSnoopLogSettingAtEnable   # FULL = good
```

Verify with `sSnoopLogSettingAtEnable`, **never** with the settings key — the
key reads `1` whether or not anything is recording.

### Reading the log on Android 15 without root

Once `FULL` is set, the capture is written to `/data/misc/bluetooth/logs/`,
which `adb shell` cannot read — permission denied, and the directory does not
exist under any `/sdcard` path on this build. `find /sdcard -iname '*btsnoop*'`
returns nothing.

That leaves `adb bugreport` as the only no-root route:

```bash
adb bugreport captures/bugreport-$(date +%s).zip      # 2-5 min, several hundred MB
unzip -l captures/bugreport-*.zip | grep -i snoop     # the path varies by build
unzip -o -j captures/bugreport-*.zip '*btsnoop_hci.log*' -d captures/
tools/decode-snoop.py captures/btsnoop_hci.log --frames
```

### Attempt 2 on 2026-10-12: capture obtained, and it is empty

The dump worked and the file was there, but it contains **zero records**:

```
FS/data/misc/bluetooth/logs/BT_HCI_2026_1012_085534.cfa    16 bytes

00000000: 6274 736e 6f6f 7000 0000 0001 0000 03ea  btsnoop.........
```

Those sixteen bytes are the btsnoop header and nothing else — magic
`btsnoop\0`, version 1, datalink `0x03EA` (1002, HCI UART H4) — and not one
packet. Note the extension is `.cfa`, a Qualcomm convention, while the
*contents* are still standard btsnoop: `decode-snoop.py` parses it without
complaint and correctly reports zero. The fault is upstream of the parser.

TVS Connect's live dashboard was foregrounded throughout (pid 25037,
`U458LiveDashboardActivity`), so traffic should have existed.

Tried and ruled out:

| Attempt | Result |
| :--- | :--- |
| `settings put global bluetooth_hci_snoop_log_mode full` | writable, no effect on its own |
| `svc bluetooth disable`/`enable` to force re-init | flipped `sSnoopLogSettingAtEnable` `EMPTY` → `FULL`, still zero records |
| `setprop persist.vendor.bluetooth.btsnooplogmode` | **failed**, needs root |
| `persist.device_config.aconfig_flags.bluetooth.INIT_gd_hal_snoop_logger_{socket,filtering}` | both `true` — the stack uses a **socket** snoop sink, not a file |
| Oplus `init.svc.olc_get_bt_hci_log` / `countCachedHciLog` | both `stopped`, no binary under `/system/bin` or `/vendor/bin` |
| `persist.sys.oplus.bt.cache_hcilog_*` | a separate Oplus HCI cache exists (`mode=1`, `allow_all_devices=1`) but exposes no adb entry point |

`INIT_gd_hal_snoop_logger_socket=true` is the likely explanation: on this build
the snoop logger streams to a socket consumer instead of rotating a file, and
nothing is consuming it, so the file gets a header and no writes.

### Attempt 3 on 2026-10-12 evening: the toggle is on, and there is no root

Earlier notes in this file say "HCI snoop logging was never on". **That is
wrong**, and it is wrong in a way that matters, because it stopped anyone
looking further. Three separate checks now:

**1. Developer options shows it enabled.** Settings → Developer options →
*Enable Bluetooth HCI snoop log* → **Enabled**, and the sub-screen radio is on
**Enabled** (full), not *Disabled* or *Enabled Filtered*. The AOSP toggle was
genuinely flipped. `dumpsys bluetooth_manager` corroborates it in its enable
history, which shows the toggle's own Bluetooth off/on cycle:

```
10-12 08:55:26.078  Package [android] requested to [Disable].
10-12 08:55:34.267  Package [android] requested to [Enable].   (8 s later)
```

`Package [android]` is the system, not an app — that is the snoop toggle
restarting the stack, and it is the same second the `.cfa` was created. So the
log *was* opened deliberately. It recorded nothing anyway, which points at the
sink, not at the switch.

**2. The stack was denied a property at exactly that moment.** From the
bugreport's own log:

```
08:55:34.684 auditd: denied { set } for property=vendor.bthcisnoop.running
          pid=30690 uid=1002 gid=1002
          scontext=u:r:bluetooth:s0 tcontext=u:object_r:vendor_default_prop:s0
          permissive=0
```

twice, ~16 ms apart, from the `bluetooth` uid as the snoop logger came up. The
log file is created at enable time and the logger is denied its vendor property
there and then, leaving a header and no writes.

**3. There is no root on this device, so none of the remaining levers exist.**

| Command | Result |
| :--- | :--- |
| `adb root` | `adbd cannot run as root in production builds` |
| `adb shell id` | `uid=2000(shell) groups=… net_bt_admin, net_bt …` |
| `su -c …` | `su: inaccessible or not found` |
| `ls /data/misc/bluetooth{,/logs,/../bluedroid}` | `Permission denied` |
| `setprop persist.bluetooth.btsnoopenable true` | not reachable |

An earlier note in this file took `adb` reporting itself rooted at face value.
It is not — dumpstate inside `adb bugreport` runs as root and can read
`/data/misc/bluetooth/`, which is the only reason any log has ever been seen.

Note also `persist.bluetooth.btsnoopenable` is **empty** and
`persist.bluetooth.btsnoopdefaultmode` is `disabled`, even though the UI says
enabled. The UI writes `Settings.Global.bluetooth_hci_snoop_log_mode` (= `full`);
the `persist.*` property is read at init and cannot be set without root. Do not
treat `dumpsys` and the UI as redundant — here they genuinely disagree.

`dumpsys bluetooth_manager` is not a substitute for the bonding database
either: it masks the controller address (`XX:XX:XX:XX:27:54`) and exposes no
bonded-device list to `shell`.

**4. The decisive test: real traffic, forced flush, still nothing.** Ran our
own app's unfiltered BLE scan for ~20 s (so the controller is genuinely
exchanging HCI packets), then `adb shell svc bluetooth disable` — which is what
forces the snoop logger to close and flush its file — and took a fresh
`adb bugreport`. Result:

```
FS/data/misc/bluetooth/logs/BT_HCI_2026_1012_085534.cfa    16 bytes   08:55
```

That is the **same** 16-byte file from the morning, unchanged. No new log file
was created at the flush, and the existing one was not appended to, despite
real traffic and an explicit close. This settles it: on this build the snoop
logger does not write the file sink at all, in any circumstance reachable
without root. There is no further adb trick to find.

**Conclusion: stop relying on OS-level snoop on this device.** The next route is
capturing from inside our own app — `dev.tvsopen.log.Log` already hex-dumps
every frame in and out, and the app can hold the GATT link itself. That is
strictly better for our purposes: the trigger order is ours to choose, and it
needs neither root nor a working snoop sink.

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