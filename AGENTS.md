# AGENTS.md — Working rules for AI agents on this repo

## CPU budget: hard limit of 2 cores

**This machine has 4 cores and the user needs the laptop for other work while
this job runs. Never saturate the CPU.**

Mandatory for every long-running or heavy command:

- Pin to cores `0,1` with `taskset -c 0,1`.
- Deprioritise with `nice -n 15` (or higher).
- Pass an explicit thread cap where the tool supports one
  (e.g. `jadx --threads-count 2`, `--max-workers=2`).

### The Gradle daemon does NOT inherit the mask

This is the trap. Gradle forks a single-use daemon, and it comes up on **all
four cores** even when the launcher is pinned. Always:

```bash
setsid nohup nice -n 15 taskset -c 0,1 \
  ./gradlew :app:assembleDebug --no-daemon --max-workers=2 --no-parallel \
  > build.log 2>&1 < /dev/null &
disown

# IMMEDIATELY after launching:
for p in $(pgrep -f GradleDaemon); do
  taskset -cpc 0,1 "$p"; renice -n 15 -p "$p"
done
```

Then verify — do not assume:

```bash
taskset -pc $(pgrep -f GradleDaemon | head -1)   # must print: 0,1
ps -o ni,pcpu -p $(pgrep -f GradleDaemon | head -1)
```

`gradle.properties` also sets `org.gradle.workers.max=2`,
`org.gradle.daemon=false` and `kotlin.compiler.execution.strategy=in-process`.

Do not run more than one CPU-heavy background job at a time. Do not use a thread
count equal to `nproc`.

## Tests

`./gradlew :app:testDebugUnitTest` — pure JVM, no device, no emulator, ~20 s.
CI runs it before the APK build, so keep it that way.

This is not ceremony. The worst bug in this codebase — odometer reading 10x low
because `decode` and `Session` each divided by 10 — compiled clean, produced no
warning, passed every on-device smoke test, and was only findable by writing a
test. The protocol layer is arithmetic on byte arrays, so anything that scales,
truncates, or orders bytes belongs in `app/src/test/java/dev/tvsopen/protocol/`.

## Build, install, test

Toolchain (all outside the repo, none on `PATH` by default):

```bash
export JAVA_HOME=~/.local/share/mise/installs/java/21.0.2   # JDK 26 is rejected by AGP
export ANDROID_HOME=~/Android/Sdk
```

`compileSdk`/`targetSdk` are 34 — that is the platform installed locally.

```bash
# build (see CPU rules above)
./gradlew :app:assembleDebug --no-daemon --max-workers=2 --no-parallel

# install + launch in one step
android run --apks=app/build/outputs/apk/debug/app-debug.apk
```

The `android` CLI is installed and is the better tool here — it resolves the
launcher activity for you and does delta installs. `adb install -r` still works
when you want the explicit form.

**Do not pass `--debug` to `android run` unless you are attaching a debugger.**
It launches the app with `am set-debug-app -w`, so the process sits in
`Waiting For Debugger:` forever and every later check looks like a hang.

### Smoke test without hardware

```bash
adb logcat -c
adb shell am force-stop dev.tvsopen
adb shell am start -n dev.tvsopen/.ui.MainActivity
sleep 4
adb shell pidof dev.tvsopen || echo "NOT RUNNING"
adb logcat -d -b crash | tail -40
adb logcat -d -s TVSRide:* | tail -40
```

Screenshots — `android screen capture` writes the file directly, which beats
piping `adb exec-out screencap`:

```bash
android screen capture -o /tmp/opencode/shot.png
```

To inspect the view tree instead of squinting at pixels:

```bash
android layout -p
android screen resolve --screenshot=annotated.png --string='tap #3'
```

Device is 1080x2400. The bottom nav tabs sit at roughly y=2199, x=170 (Home),
x=540 (More), x=903 (Log) if you need to `adb shell input tap`.

### Expected behaviour with no scooter

- Home shows `—` in every telemetry tile, not `0`.
- Dashboard header reads "Disconnected" and "Unknown SmartXonnect vehicle".
- More renders the protocol-transparency panel.
- Log shows startup records and a non-zero `File:` size.
- A `RideService` foreground notification appears only once a connect is asked for.

**A `—` is correct. A `0` would mean the UI is inventing data.** If a tile shows
`0` with no scooter connected, that is a bug, not a pass.

## Logs

The logger (`dev.tvsopen.log.Log`) is the evidence base for the whole project:
every BLE frame in and out is hex-dumped with a timestamp, kept in a 4000-entry
in-memory ring and mirrored to a rotating file.

```bash
# live in the app: third tab
# off the device — works because the released APK is debuggable:
adb shell run-as dev.tvsopen ls -l files/logs/
adb exec-out run-as dev.tvsopen cat files/logs/tvsride.log > session.log
```

Rotation keeps three files: `tvsride.log`, `tvsride.2.log`, `tvsride.3.log`,
each capped at 2 MB.

Two rules learned the hard way, both about the same thing:

- **A throttled republish must always publish the head.** The UI flow was only
  updated when `ring.size % 8 == 0`, so the first seven records of any session
  never reached it and the Log tab showed "0 records" while logcat plainly had
  lines. Throttle the tail, never the head.
- **Flush the file writer.** `PrintWriter` over a `BufferedWriter` reports
  `File.length() == 0` for a log that already has hundreds of lines, and loses
  everything on `kill -9`. Flush every 16 records and before reporting a size.

## Testing against real hardware

Do not assume. When a scooter is in range:

1. Confirm the GATT service is `5456534D-5647-5341-5342-454E544F5251`.
2. Connect, then read the Log tab before anything else. Frames in = telemetry.
   No frames = a link problem, not a decode problem.
3. Follow `docs/CAPTURE.md` in order.

Never write calibration frames or calibration ids to a cluster without
explicit owner instruction — they alter cluster behaviour.

## This is a clean-room interoperability project

The goal is a third-party companion app for the **user's own** TVS vehicle that
speaks the instrument cluster's Bluetooth LE protocol directly. TVS's servers,
accounts and cloud APIs are explicitly **out of scope** — see `docs/SCOPE.md`.

- Work only from the user's own hardware and the two local APKs.
- Never authenticate to, intercept, or replay traffic against TVS servers.
- Never use the published CVEs. They are background reading, not a design input.
- Do not redistribute the official APK. It stays in the parent folder, outside
  this repo, referenced only by SHA-256.
- No `INTERNET` permission. The no-account property is enforced by the build and
  must stay enforced — verify it in the merged manifest after changing deps.

## Code review gotchas seen in this repo

All of these cost a real build cycle. Check for them before blaming the toolchain.

- **`?:` binds looser than an infix function.** `x?.toString() ?: "—" to "°C"`
  parses as `x ?: ("—" to "°C")`, so the expression type collapses to `Any` and
  you get "expected Pair<String,String>, actual Serializable" pointing at the
  *function declaration*, not the branch. Parenthesise the elvis, or — better —
  compute the value, then pair it once.
- **`?.let {}` yields `T?`, not `T`.** A `when` whose branches all end in
  `?.let` infers `Pair<String?, String>` against a `Pair<String, String>`
  signature. Apply the fallback once at the end rather than per branch.
- **Kotlin nests block comments.** A `/*` inside a KDoc opens a nested comment,
  so the KDoc's own `*/` closes the nested one and the outer never terminates.
  This produced 12 phantom "Unresolved reference" errors in one go. A brace
  counter will *not* catch it — check comment nesting.
- **Android BLE write APIs return `Int`, not `Boolean`.**
  `writeCharacteristic(...)` and the API-33 `writeDescriptor(desc, value)` give
  a status code; only the deprecated `writeDescriptor(desc)` returns `Boolean`.
  Mixing branches makes `if/else` infer `Comparable & Serializable`.
  Compare against `BluetoothGatt.GATT_SUCCESS`.
- **`android.bluetooth.BluetoothGattStatusCodes` is not public API.** Use
  `BluetoothGatt.GATT_SUCCESS`.
- **`ByteArray.take(n)` returns `List<Byte>`,** which has no `copyInto`.
  Convert with `.toByteArray()` first.
- **`props and FLAG != 0` does not parse as intended.** `and` is an infix
  function, so parenthesise: `(props and FLAG) != 0`.
- **`AppCompatActivity` needs a `Theme.AppCompat` descendant.** The manifest
  theme was `android:Theme.Material`, which compiles fine and then crashes on
  the first `setContentView` with "You need to use a Theme.AppCompat theme".
  Compose supplies the real colours; the XML theme only has to not fight
  AppCompat. Cost one crash-on-launch to find out.
- **A crash on a blank screen is often the theme, not the layout.** Compose
  composables that never ran produce no useful log line; check the manifest
  theme before reading the composable.
- **A `connectedDevice` foreground service needs Bluetooth permissions
  *granted*, not merely declared.** Calling `startForeground` before the runtime
  permission dialog is answered throws `SecurityException` and kills the
  process. Degrade to a plain notification and retry once connected.
- **Icons used by this app are deprecated in Material 3** (`Article`,
  `DirectionsBike`). Use the `AutoMirrored` variants; the build is warning-clean
  by policy and these are the only warnings it produced.
- **`android-actions/setup-android@v3` is broken** on current runners
  ("Failed to find package 'tools'"). The workflow locates the runner's own
  `sdkmanager` instead. Do not reintroduce that action.

## Repository and releases

`master`, remote on GitHub. CI is `.github/workflows/android.yml`.

- Every push and PR builds and asserts the manifest invariants.
- A GitHub Release gets the APK only on a `v*` tag or a manual dispatch, so the
  download page is not buried under one release per commit.
- **The attached APK is `assembleDebug`, deliberately.** There is no signing key,
  so `assembleRelease` produces an unsigned, uninstallable APK. A debug build is
  signed with the runner's debug key, installs with a plain `adb install`, and —
  the actual reason — stays debuggable so `run-as` can pull the log file.
  Add a keystore via secrets before switching to a real release build.

Two checks run in CI that are worth not breaking:

- **No `android.permission.INTERNET` in the merged manifest.** This is the
  mechanism behind "no account, no cloud". A transitive dependency could add it,
  so assert it rather than trust review.
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and `POST_NOTIFICATIONS` must be present.

## Layout

```
protocol/    constants, per-model drivers, packet builders, pictograms
transport/   GATT transport and the foreground ride service
nav/         notification listener (any maps app) and the HUD bridge
session/     process-scoped store the UI observes
log/         logger: ring buffer, rotating file sink, hex frame capture
ui/          Compose screens (Home, More, Log)
```

No Gradle daemon left running after a build. Always `--no-daemon`.