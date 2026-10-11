# Building

## Current state

**The app builds.** `:app:assembleDebug` succeeds with zero warnings and
produces `app/build/outputs/apk/debug/app-debug.apk` (~17 MB, 8 DEX files).

Verified in the built artifact's merged manifest: **no
`android.permission.INTERNET`**. The full permission set is BLUETOOTH (legacy,
SCAN, CONNECT), location (coarse + fine), notification-listener binding,
foreground service, post-notifications and wake lock — no network capability of
any kind. The no-account claim is enforced by the build, not promised.

Not yet done: installed on a device, or exercised against a scooter.

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

## CPU budget — read this before building

Per [AGENTS.md](../AGENTS.md) the machine has 4 cores and the owner needs the
laptop usable while a build runs.

```bash
export JAVA_HOME=~/.local/share/mise/installs/java/21.0.2
export ANDROID_HOME=~/Android/Sdk
setsid nohup nice -n 15 taskset -c 0,1 \
  ./gradlew :app:assembleDebug --no-daemon --max-workers=2 --no-parallel \
  > build.log 2>&1 < /dev/null &
disown

# The forked Gradle daemon does NOT inherit the taskset mask.
for p in $(pgrep -f GradleDaemon); do
  taskset -cpc 0,1 "$p"; renice -n 15 -p "$p"
done
```

That last loop is not optional. Gradle forks a single-use daemon, and
observation showed it coming up on **all four cores** despite the launcher
being pinned. Pin it right after launch, then verify:

```bash
taskset -pc $(pgrep -f GradleDaemon | head -1)   # expect: 0,1
```

`gradle.properties` already sets `org.gradle.workers.max=2`,
`org.gradle.daemon=false` and `kotlin.compiler.execution.strategy=in-process`.

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

## Next steps

1. Install on a device and smoke-test **without** a scooter: the app should
   start, the More tab should render the transparency panel, permissions should
   prompt, and the dashboard should show `—` rather than zeroes.
2. Wire the UI to live service state — `MainActivity` still uses
   `remember { Telemetry() }`, a hardcoded empty object.
3. Scan for and connect to a scooter.
4. Then follow [CAPTURE.md](CAPTURE.md).

## If the build pulls too hard on the CPU

```bash
ionice -c 3 nice -n 19 taskset -c 0 gradle :app:assembleDebug \
  --no-daemon --max-workers=1 --no-parallel
```