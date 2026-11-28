# Building

## Current state

**The app builds in CI.** As of **v0.3.0** (versionCode 19, 2026-11-28) the
`app-debug` artifact is attached to every successful run and to the
[v0.3.0 release](https://github.com/athulkrishna2015/TVSRideCompanion/releases/tag/v0.3.0).
It has been installed and driven on a real phone, and the owner's Ntorq 125 was
connected on 2026-10-13; the cluster is currently out of service.

The merged manifest declares `android.permission.INTERNET` **deliberately** for
embedded map tiles, place search and routing (fixed hosts only). CI asserts it
is present, along with `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and
`POST_NOTIFICATIONS`, and asserts `ANSWER_PHONE_CALLS` and `CALL_PHONE` remain
absent. There is **no TVS account, no TVS server and no telemetry upload** —
the no-TVS-contact guarantee is a code property, not a manifest rule.

## Prerequisites

| Requirement | Needed | Notes |
| --- | --- | --- |
| JDK 17-21 | yes | **JDK 26 fails** — AGP 8.7.3 rejects it |
| Android SDK | yes | platform **34**, build-tools 34.0.0 |
| Gradle 8.11.1 | yes | wrapper committed; distribution at `/mnt/0946E88701BE265B/tools/` |
| `ANDROID_HOME` | yes | `~/Android/Sdk` here |

JDK 21.0.2 was installed via `mise install java@21`. `compileSdk`/`targetSdk`
are pinned to **34** — that is the platform installed locally, and the
dependency set (Compose BOM 2024.09.03, core-ktx 1.13.1) is the newest that
compiles against it. Raising to 35 needs platform 35 installed *and* those
bumped together.

## Builds are CI-only

The rider asked for local builds to stop: this machine is theirs and in use, so
the compiler is `.github/workflows/android.yml`. Push, then take the APK from
CI:

```bash
git push origin master
gh run list --limit 1                 # wait for 'completed  success'
gh run download <run-id> -n app-debug -D /tmp/apk
adb install -r /tmp/apk/app-debug.apk
```

CI runs `:app:testDebugUnitTest :app:assembleDebug --max-workers=2
--no-parallel --no-daemon`. A full cycle is about 90 seconds and costs the
laptop nothing. Do **not** run Gradle locally, not even to check one file.

## CPU budget (if a local build is ever unavoidable)

Per [AGENTS.md](../AGENTS.md) the machine has 4 cores and the owner needs the
laptop usable while a build runs. The limit is **not** in `gradle.properties`:
a committed `workers.max`/`daemon=false` would also throttle CI. Pass it on
every invocation, and pin the forked daemon immediately:

```bash
export JAVA_HOME=~/.local/share/mise/installs/java/21.0.2
export ANDROID_HOME=~/Android/Sdk
setsid nohup nice -n 15 taskset -c 0,1 \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  --no-daemon --max-workers=2 --no-parallel \
  > build.log 2>&1 < /dev/null &
disown

# The forked Gradle daemon does NOT inherit the taskset mask.
for p in $(pgrep -f GradleDaemon); do
  taskset -cpc 0,1 "$p"; renice -n 15 -p "$p"
done
taskset -pc $(pgrep -f GradleDaemon | head -1)   # expect: 0,1
```

`kotlin.compiler.execution.strategy=in-process` stays in `gradle.properties`:
it avoids a second JVM for the Kotlin compiler daemon. Check comment balance
before pushing (`grep -c '/\*'` vs `grep -c '\*/'` per file) — with no local
compiler a stray KDoc opener is invisible until CI.

## Gotchas encountered

Each of these cost a build cycle; recorded so nobody repeats them.

| Symptom | Cause |
| --- | --- |
| `Plugin [id: 'com.android.application'] was not found` | no `pluginManagement.repositories` block — Gradle searched only the plugin portal |
| `What went wrong: 26.0.2.1` | AGP 8.7.3 does not support JDK 26 |
| `Unresolved reference: KotlinCompile` | `tasks.withType<org.gradle.api.tasks.compile.KotlinCompile>` is invalid in Gradle 8.11 |
| *Compose Compiler Gradle plugin is required* | Kotlin 2.0 dropped `kotlinCompilerExtensionVersion` |
| `requires ... to compile against version 35` | Compose BOM 2024.12 / core-ktx 1.15 exceed the installed SDK |
| `Unclosed comment`, causing **12** knock-on errors | **Kotlin nests block comments.** A `/*` inside a KDoc opens a nested comment, so the KDoc's own `*/` closes the nested one and the outer never terminates. Two occurrences in `VehicleModel.kt`. |
| `Comparable<Nothing> & Serializable` | `writeCharacteristic`/`writeDescriptor` return `Int`; mixing one branch with a `Boolean` branch makes the `if/else` unify to `Comparable & Serializable` |
| `suspend fun withLock ... only from a coroutine` | `disconnect()` called `withLock` but was not `suspend` |

## Verify a downloaded APK

CI attaches `assembleDebug`, which installs with a plain `adb install` and stays
debuggable for `run-as` log access. Smoke-test with no scooter: Home shows `—`
in every telemetry tile (never `0`), the header reads "Disconnected", More
renders its connection/permissions/alert/settings cards, and the Log tab shows
startup records with a non-zero `File:` size. Then follow
[CAPTURE.md](CAPTURE.md) or [SIMULATOR.md](SIMULATOR.md).

## If the build pulls too hard on the CPU

```bash
ionice -c 3 nice -n 19 taskset -c 0,1 ./gradlew :app:assembleDebug \
  --no-daemon --max-workers=1 --no-parallel
```

Remember to pin the forked daemon as shown above — this is still a fallback,
not the normal workflow.