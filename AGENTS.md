# AGENTS.md — Working rules for AI agents on this repo

## CPU budget: hard limit of 2 cores

**This machine has 4 cores and the user needs the laptop for other work while
this job runs. Never saturate the CPU.**

Mandatory for every long-running or heavy command:

- Pin to cores `0,1` with `taskset -c 0,1`.
- Deprioritise with `nice -n 15` (or higher).
- Pass an explicit thread cap where the tool supports one
  (e.g. `jadx --threads-count 2`, Gradle `--max-workers=2`,
  `parallel: { ... } org.gradle.workers.max=2`).

Canonical pattern:

```bash
setsid nohup nice -n 15 taskset -c 0,1 <command> > out.log 2>&1 < /dev/null &
disown
```

Verify after launching that the constraint actually took effect:

```bash
taskset -pc $(pgrep -f <command>)   # expect: 0,1
ps -o ni,pcpu -p $(pgrep -f <command>)
```

Do not run more than one CPU-heavy background job at a time. Do not use
`--threads-count` values equal to `nproc`.

## This is a clean-room interoperability project

The goal is a third-party companion app for the **user's own** TVS Ntorq that
speaks the vehicle's Bluetooth Low Energy protocol directly. TVS's servers,
accounts and cloud APIs are explicitly **out of scope** — see `docs/SCOPE.md`.

- Work only from the user's own hardware and the two local APKs.
- Never authenticate to, intercept, or replay traffic against TVS servers.
- Never use the published CVEs. They are background reading, not a design input.
- Do not redistribute the official APK. It stays in the parent folder, outside
  this repo, referenced only by SHA-256.

## Build notes

- Android app in `app/` (Kotlin). See `app/README.md`.
- No Gradle daemon left running; always `--no-daemon` to avoid background CPU.