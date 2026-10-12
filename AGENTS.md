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

**Those flags are now mandatory, not advisory.** `gradle.properties` used to
also set `org.gradle.workers.max=2` and `org.gradle.daemon=false`, which made
the limit impossible to forget — and which also applied to CI, holding the
runner to half the parallelism it had for a constraint that means nothing
anywhere but this laptop. Those two properties were removed; the committed file
is now CI-shaped and the limit lives only in the command line.

So a bare `./gradlew :app:assembleDebug` **will saturate all four cores.** Pass
the flags every time:

```bash
./gradlew :app:assembleDebug --no-daemon --max-workers=2 --no-parallel
```

`kotlin.compiler.execution.strategy=in-process` stays in `gradle.properties`:
it avoids a second JVM for the Kotlin compiler daemon, which is worth having on
both sides.

Do not run more than one CPU-heavy background job at a time. Do not use a thread
count equal to `nproc`.

## Builds are CI-only

The rider asked for local builds to stop, because this machine is theirs and in
use. **CI is the compiler.** `./gradlew :app:testDebugUnitTest :app:assembleDebug
--max-workers=2 --no-parallel --no-daemon` is what to run instead of any local
build; it is the same 2-core budget, it just does not cost the laptop anything
to verify.

That moves the cost of a typo to a CI run, which takes ~90 s. Two habits follow:

- **Check comment balance before pushing.** With no local compiler, a stray
  `/**` is invisible until CI. A duplicated KDoc opener nests the comment and
  swallows the code after it, and the resulting "Unresolved reference" errors
  point at unrelated lines. `grep -c '/\*'` vs `grep -c '\*/'` per file catches
  it in a second.
- **Do not infer a build result from task ordering.** "It got past
  `validateSigningDebug`, so it compiled" was wrong — that task ran before
  `compileDebugKotlin` failed. Read the actual `BUILD SUCCESSFUL|FAILED`.

## Tests

`:app:testDebugUnitTest` — pure JVM, no device, no emulator, ~20 s. It runs in
CI **in the same Gradle invocation** as the assemble, before it, so a broken
test stops the APK being built. Keep it that way.

This is not ceremony. The worst bug in this codebase — odometer reading 10x low
because `decode` and `Session` each divided by 10 — compiled clean, produced no
warning, passed every on-device smoke test, and was only findable by writing a
test. The protocol layer is arithmetic on byte arrays, so anything that scales,
truncates, or orders bytes belongs in `app/src/test/java/dev/tvsopen/protocol/`.

## Build: push, then take the APK from CI

**There is no local build.** The rider asked for this — the machine is theirs and
in use — so the compiler is `.github/workflows/android.yml`, and a build is a
push followed by a wait.

```bash
git push origin master
gh run list --limit 1                     # wait for 'completed  success'
gh run download <run-id> -n app-debug -D /tmp/apk
adb install /tmp/apk/app-debug.apk        # NOT -r, see below
```

A full cycle is about 90 seconds of waiting and costs the laptop nothing. An
artifact is attached to every run, so this works for any commit — you do not
need a tag. Only `v*` tags and manual dispatches also publish to a GitHub
Release.

**Always `adb install`, never `adb install -r`, for a CI-built APK.** Each CI
run signs with *that runner's* debug key, so a CI APK is not upgrade-compatible
with any other CI APK, nor with a locally-built one:

```
Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package dev.tvsopen
signatures do not match newer version; ignoring!]
```

That reads like a broken artifact; it is just a different debug keystore. The
fix is `adb uninstall dev.tvsopen` first, which also wipes the log file and
prefs. Everything in `files/logs/` is a debug artefact and is cheap to lose —
but pull it first if a session's frames matter.

To check the result without guessing:

```bash
gh run view <run-id> --log-failed | grep -E "^e: |BUILD (SUCCESSFUL|FAILED)"
gh run list --limit 3
```

Two failure modes this workflow makes worth knowing:

- **A syntax slip costs a CI round trip, not a local edit.** Check comment
  balance before pushing: `grep -c '/\*'` vs `grep -c '\*/'` per file. A
  duplicated `/**` nests the comment and swallows the code after it, and the
  resulting errors point at unrelated lines far from the cause.
- **Do not infer a build result from which tasks appear in the log.**
  `validateSigningDebug` runs *before* `compileDebugKotlin`, so "it got that
  far" is not evidence it compiled. Read `BUILD SUCCESSFUL|FAILED`.

### Do not run Gradle locally

Not even to check a syntax error, and not even when it is "just one file". A
local build is minutes of the rider's CPU, which is the entire reason this
workflow exists.

## Install and test on the device

```bash
android run --apks=/tmp/apk/app-debug.apk     # install + launch, resolves the activity
adb install -r /tmp/apk/app-debug.apk          # explicit form
```

The `android` CLI is installed and is the better tool — it does delta installs.
**Do not pass `--debug`** unless you are attaching a debugger: it runs
`am set-debug-app -w`, so the process sits in `Waiting For Debugger:` forever and
every later check looks like a hang.

The APK is debuggable on purpose. There is no signing key, so `assembleRelease`
would be uninstallable, and `run-as` is the only way to pull the on-device log
without root:

```bash
adb shell run-as dev.tvsopen ls -l files/logs/
adb exec-out run-as dev.tvsopen cat files/logs/tvsride.log > session.log
```

The device enumerates intermittently and sometimes comes back under a different
serial. Check `adb devices` before concluding a command hung.

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

Device is 1080x2400. The bottom nav tabs sit at y=2199, and with four items
across 1080 each is 270 wide, so tap the **centre** of the one you want:

| tab | x |
| --- | --- |
| Home | 135 |
| More | 405 |
| Overview | 675 |
| Log | 945 |

`x=540` is the seam between More and Overview and registers on neither. This
cost a round of "the More tab is broken" that was really a mis-aimed tap.

`android layout -p` dumps whichever window has focus, which is often `systemui`
rather than our app. If the output has no `tvsopen` nodes, check focus before
trusting the bounds.

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

**This repository is public. Never commit anything derived from the owner's
actual location, route, device identifiers, or home map.** In practice that
means: invent street names in tests and comments (`Example Road`), never paste a
real nav notification or captured street into a source file, and redact the
street and distance out of any log excerpt before it goes into an issue. The
app's own log records the maneuver text verbatim, so an unredacted export is a
route. `captures/README.md` says the same thing in more detail.

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
  process. Check before starting the service; degrade in the service.
- **`startForeground` on a channel that does not exist kills the process, and
  `try`/`catch` cannot stop it.** The failure arrives as
  `CannotPostForegroundServiceNotificationException: Bad notification for
  startForeground`, posted *by the system* to the app's main thread via
  `throwRemoteServiceException` — not thrown from your call site, so it lands
  outside any handler around `startForeground`. This bit us because the channel
  was only created as a side effect of `notify()`, and `notify()` had never run
  because no connection had ever completed. `buildNotification()` now calls
  `ensureChannel()` itself: anything that builds a notification must be able to
  post it, and a notification that was never posted is invisible in testing
  until the moment it kills the app.
- **The in-app log file lags logcat.** The file sink flushes every 16 records,
  so a just-started service can be "missing" from `files/logs/tvsride.log` while
  `adb logcat -d | grep TVSRide/` already shows it. This produced a false "the
  auto-connect never fires" conclusion. Check logcat before concluding that
  something did not run.
- **`pkill -f GradleDaemon` kills its own shell.** `pkill -f` matches the whole
  command line, and the line running it contains the pattern. Use a bracket:
  `pkill -f "Gradle[D]aemon"`.
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
transport/   BLE scanner, GATT transport, foreground ride service, boot receiver
nav/         notification listener (any maps app) and the HUD bridge
session/     process-scoped store the UI observes
prefs/       ride mode, last known cluster
log/         logger: ring buffer, rotating file sink, hex frame capture
ui/          Compose screens (Home, Overview, More, Scan, Log) + theme
tools/       capture and decode scripts
```

No Gradle daemon left running after a build. Always `--no-daemon`.
## Preserved analysis inputs

`research/vendor/` holds the decompiled vendor code, the trimmed APK used for
REA queries, and the extracted Ntorq manual text. **Gitignored** — it is other
people's proprietary code and hours of 2-core decompilation. `AGENTS.md`-level
rule: never `git add -f` it.

If `/tmp` is cleared, restore it with the commands in
`research/vendor/README.md`; both source APKs remain in the parent folder.

## Capture tooling

`tools/` holds the HCI capture and decode scripts. `make-snoop-fixture.py`
generates a synthetic btsnoop with a known answer, because the real captures are
gitignored and the decoder would otherwise have no test. Run it after any change
to `decode-snoop.py`:

```bash
tools/make-snoop-fixture.py /tmp/fx.log && tools/decode-snoop.py /tmp/fx.log
# expect: 3 ATT packets (1 in, 2 out), 2 rows tagged <5A>
```

**OS-level HCI snoop does not work on this phone, and it is not a settings
problem.** The Developer options toggle *is* on ("Enable Bluetooth HCI snoop log"
→ Enabled, sub-mode *Enabled*), and `dumpsys bluetooth_manager` shows the
toggle's own BT off/on cycle at 08:55:26/08:55:34 — the same second the log file
was created. The file is a 16-byte btsnoop header with zero records because
`auditd` denied `vendor.bthcisnoop.running` to `u:r:bluetooth:s0` at that
moment, and `INIT_gd_hal_snoop_logger_socket=true` routes records to a socket
nobody consumes.

Two facts that cost time:

- **This device has no root.** `adb root` replies `adbd cannot run as root in
  production builds`, `adb shell` is `uid=2000(shell)`, and `su` does not
  exist. `setprop persist.bluetooth.btsnoopenable true` is therefore impossible,
  and `/data/misc/bluetooth/**` is `Permission denied`. `adb bugreport` is the
  only route, because dumpstate inside it runs as root — do not infer from a
  readable bugreport that adb itself is rooted.
- **Don't `pkill -f` your own pattern.** See the gotchas list.

Full diagnosis and the ruled-out list are in `docs/CAPTURE.md` (Attempt 3).
Capture from inside our own app instead — `dev.tvsopen.log.Log` already captures
every frame, and `docs/CAPTURE.md` §0b has the in-app route.
