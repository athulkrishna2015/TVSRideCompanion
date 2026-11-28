# Changelog

Highlights only. Protocol evidence and open questions live in
[docs/PROTOCOL.md](docs/PROTOCOL.md), [docs/NAVIGATION.md](docs/NAVIGATION.md)
and [docs/CAPTURE.md](docs/CAPTURE.md).

## v0.3.0 — 2026-11-28 (versionCode 19)

[Release](https://github.com/athulkrishna2015/TVSRideCompanion/releases/tag/v0.3.0).
First release carrying the v0.2.x work below.

- Navigation: any maps app via notification listener; extra maps, message and
  call apps selectable in More. Two-line cluster layout — maneuver on the
  10-character line 1, place/ETA rolling on the 12-character line 2.
- Spanish instructions parsed from notification content (not a UI locale
  switch); roundabout exits 1–7 and street-name road-word abbreviations.
- Alerts: notification-repost dedup, 60 s stale-nav watchdog, and a More-tab
  notification-access state that reflects the real listener bind.
- Telemetry/UI: low-fuel and engine warm-up/hot labels computed phone-side from
  telemetry (never sent to the cluster), model-aware Home cards configurable in
  More, `Exit app` stops the background service and companion observation,
  `Keep screen on` is foreground-scoped.
- Log/export: `Export…` uses the system Create Document picker (SAF); the
  app-scoped `adb pull` route still works. Log and JSON exports are redacted
  (MAC, nav text, call/message details, rider name, coordinates).
- Development: `tools/ntorq-bridge.py` is a local Ntorq bridge on
  `ws://127.0.0.1:8766` (`TVSBT800450`, no service UUID) — an app-level aid,
  not hardware verification. Outbound nav byte order remains UNVERIFIED.
- No TVS account/server; `INTERNET` is only for fixed-host map tiles, search and
  routing.

## v0.2.x — earlier releases (v0.2.0 … v0.2.15)

- v0.2.15: SAF log export, alert dedup / stale-nav watchdog / progress
  distance, low-fuel and engine warm-up/hot labels, listener bind state,
  tolerance of all-zero connect frames, documented outbound byte-order gap.
- v0.2.14: Spanish/content-driven navigation and roundabout rules, place-name
  fitting and rolling, route replay, ride-flow fixes, explicit `Exit app` action.
- v0.2.13: exported-log privacy fixes, media command constants, telemetry
  formatting fixes.
- v0.2.0–v0.2.12: protocol/simulator work and per-model drivers; see
  `git log v0.2.0..v0.2.12` for the individual changes.
