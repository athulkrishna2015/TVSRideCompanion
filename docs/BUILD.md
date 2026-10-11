# Building

## Current state

**The app has not been compiled yet.** This document records what is needed and
what still has to happen. Everything under `docs/` is verified against shipped
APKs; nothing under `app/` has been through a compiler.

## Prerequisites

| Requirement | Needed | Notes |
| --- | --- | --- |
| JDK 17+ | yes | JDK 26 works |
| Android SDK | yes | platform **34**, build-tools 34.0.0 |
| Gradle 8.11.1 | yes | not on `PATH` by default on this machine |
| `ANDROID_HOME` | yes | `~/Android/Sdk` here |

`compileSdk`/`targetSdk` are pinned to **34** because that is the platform
installed locally. Raising them needs the matching platform package.

## CPU budget — read this before building

Per [AGENTS.md](AGENTS.md) the machine has 4 cores and the owner needs the
laptop usable while a build runs. Always:

```bash
export ANDROID_HOME=~/Android/Sdk
gradle :app:assembleDebug \
  --no-daemon \
  --max-workers=2 \
  --no-parallel
```

wrapped in the CPU cap:

```bash
setsid nohup nice -n 15 taskset -c 0,1 \
  gradle :app:assembleDebug --no-daemon --max-workers=2 --no-parallel \
  > build.log 2>&1 < /dev/null &
disown
```

`gradle.properties` already sets `org.gradle.workers.max=2`,
`org.gradle.daemon=false` and `kotlin.compiler.execution.strategy=in-process`.
Passing the flags explicitly as well is belt and braces — the daemon is the part
that otherwise keeps running in the background after the command returns.

Verify:

```bash
taskset -pc $(pgrep -f GradleDaemon | head -1)   # expect 0,1
```

A first build downloads Compose and AndroidX artifacts, so expect it to be slow
and network-bound. Subsequent builds are much faster.

## Steps to get to a building state

1. Generate the wrapper so the build is reproducible:
   ```bash
   gradle wrapper --gradle-version 8.11.1
   ```
2. Accept SDK licences if prompted:
   ```bash
   yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
   ```
3. First compile, expecting errors — the code has never been through kotlinc.
4. Fix what surfaces. Known-suspect areas, in order of likelihood:
   - `ui/MainActivity.kt` — Compose entry point wiring
   - `transport/RideService.kt` — foreground-service plumbing
   - `nav/NavigationBridge.kt` — coroutine/Flow usage
5. `gradle :app:assembleDebug` until clean.
6. Install and smoke-test **without** a scooter: the app should start, the More
   tab should render the transparency panel, and permissions should prompt.

Only after step 6 does the app exist as something that can be pointed at
hardware. See [CAPTURE.md](CAPTURE.md) for the live session.

## Toolchain location

Gradle was unpacked to `/mnt/0946E88701BE265B/tools/gradle-8.11.1` rather than
`/tmp`, which is nearly full on this machine.

## If the build pulls too hard on the CPU

Drop to one worker and use `ionice` as well:

```bash
ionice -c 3 nice -n 19 taskset -c 0 gradle :app:assembleDebug \
  --no-daemon --max-workers=1 --no-parallel
```