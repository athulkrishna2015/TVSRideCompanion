# Forum posts — TVSRideCompanion

Copy-paste ready. Replace `<GITHUB>` with https://github.com/athulkrishna2015/TVSRideCompanion
and `<RELEASE>` with the latest release APK link when you post.

---

## 1. Reddit — r/indianbikes (and r/tvsmotor)

**Title:** I got frustrated with TVS Connect too, so I reverse-engineered the Ntorq protocol and built a free, account-free companion app (open source)

Hey everyone,

Like a lot of you I was frustrated with the official TVS Connect app — background disconnects, the MapMyIndia navigation that sends you the wrong way, the fact that it needs a TVS account and cloud for things that should be local.

So I reverse-engineered the SmartXonnect Bluetooth protocol from the official APK and built **TVSRideCompanion** — a third-party companion app for TVS vehicles (Ntorq, Jupiter, Apache, Raider, iQube) that talks the instrument cluster's BLE protocol directly.

**What it does:**
- 🗺️ Mirrors turn-by-turn from **any maps app** (Google Maps, Waze, HERE, Organic Maps…) onto the cluster. No MapMyIndia, no account.
- 📊 Live telemetry on your phone: speed, odometer, fuel, trip, RPM, engine temp, etc.
- 🛵 Find-my-scooter: flash indicators + horn from the phone.
- 📞 Caller ID / SMS on the cluster display.
- 📍 Last parked location (stored locally, never uploaded).
- 👤 Custom rider name on the cluster greeting.
- 🌙 Dark/light/auto theme.

**What it deliberately does NOT do:**
- No internet permission. No account. No cloud. Enforced by the build. Everything runs locally over Bluetooth.

**Status / honesty:**
This is a reverse-engineering project. The protocol facts I'm sure about are marked verified; the ones that still need a live scooter are marked as such in the app's "Protocol transparency" panel. My Ntorq's battery is currently flat, so some items (fuel scale, how the cluster places a 17-byte packet across its 10/12-char rows, caller ID end-to-end) are still unverified. That's where I need help.

**Repo:** <GITHUB>
**Latest APK:** <RELEASE>

If you have a SmartXonnect TVS vehicle and a few minutes, I'd love:
1. A connection log from your bike (the app has a Log tab + a pullable file).
2. Whether navigation actually renders on your cluster (and which model).
3. Screenshots of anything that looks wrong.

Happy to answer questions about the protocol or the reverse-engineering approach. PRs welcome.

---

## 2. TechEnclave — Software & Tools thread (reply to the "Making alternative to TVS connect app" thread)

**Title:** TVSRideCompanion — open-source alternative, no account, no cloud, protocol fully documented

Hi all,

I've been following this thread (and the JupiterRideCompanion work) and wanted to share what I've built and documented, in case it helps anyone here.

**TVSRideCompanion** — https://github.com/athulkrishna2015/TVSRideCompanion

Open-source Kotlin/Compose Android app that speaks the SmartXonnect BLE protocol directly. No TVS account, no server, no INTERNET permission. Targets Ntorq, Jupiter, Apache, Raider, iQube.

Things that may be useful to others in this thread:
- Full protocol write-up in `docs/PROTOCOL.md` and `docs/NAVIGATION.md` — packet layouts (0x5A/0x5B frames), GATT UUIDs, keepalive, nav control/text rows, pictogram table, sanitiser rules, checksum placement.
- Per-model driver abstraction (`ProtocolDriver`) so adding a model is data, not transport changes.
- `NavParsing` handles both the Google Maps shape (distance in title) and the TVS-app shape (maneuver in title, metrics in text).
- Nav compression: verbose instructions → short codes (LFT/RGT/SLF/SRG/UTN/RND/MRG/…), ASCII arrow prefix, rolling overflow for long text, two-row send (0x4F + 0x50).
- A `Confidence` enum (VERIFIED_FROM_APK / INHERITED_UNVERIFIED / NEEDS_CAPTURE) on every fact, surfaced in the app's transparency panel.

**Where I'm stuck / need help:**
- Ntorq fuel scale: only one anchor point (raw 7 = empty). Need one capture with fuel in the tank.
- Cluster row placement: 10 chars line 1, 12 line 2, but how a 17-byte packet is placed (wrap/scroll/truncate) needs a live capture.
- Caller ID + SMS end-to-end (green LED + number).
- Bike mode never exercised.
- Jupiter/Apache have drivers but only Ntorq has a transcribed frame table; need captures from other models.

If anyone has a working scooter and 10 minutes to run a capture (the app's Log tab + `adb exec-out run-as dev.tvsopen cat files/logs/tvsride.log`), I'd be grateful. I can walk through it.

Happy to answer protocol questions. PRs welcome.

---

## 3. XDA Developers — General Development / Android (or a new thread in "Android Apps and Games")

**Title:** [APP][Open Source] TVSRideCompanion — third-party companion for TVS SmartXonnect scooters/bikes (no account, no cloud)

Hi XDA,

Sharing an open-source Android app I built for TVS SmartXonnect vehicles (Ntorq 125, Jupiter, Apache RTR, Raider, iQube).

**TVSRideCompanion** — https://github.com/athulkrishna2015/TVSRideCompanion

**Why:** The official TVS Connect app requires a TVS account and cloud, has background disconnect issues, and uses MapMyIndia for navigation. This app speaks the cluster's BLE protocol directly, needs no account, and has no INTERNET permission (enforced by build).

**Tech stack:** Kotlin, Jetpack Compose, pure-BLE (no maps SDK), per-model protocol drivers, unit-tested protocol layer (NavParsing, packet sanitiser, nav short-code compression, row-width scrolling).

**Reverse-engineering notes (in docs/):** GATT UUIDs, frame layouts transcribed from the official APK, multi-byte fields confirmed big-endian, pictogram table, sanitiser rules, nav control/text packet shapes, checksum placement. Confidence levels attached to every fact.

**Looking for:** testers with real SmartXonnect hardware, especially non-Ntorq models, and anyone interested in contributing capture data or driver tables.

APK attached to GitHub releases. Feedback welcome.

---

## 4. xBhp — TVS Ntorq 125 Ownership Thread (reply)

Hi everyone,

I've built a free, open-source alternative companion app for the Ntorq (and other TVS SmartXonnect bikes) that doesn't need a TVS account or the cloud, and mirrors Google Maps turn-by-turn onto the cluster instead of MapMyIndia.

**TVSRideCompanion:** https://github.com/athulkrishna2015/TVSRideCompanion

Features: live telemetry, Google Maps/Waze nav mirroring, find-my-scooter, caller ID, last parked, rider name on greeting, dark/light theme. No internet permission.

It's a reverse-engineering project and I'm still verifying a few things on real hardware (fuel scale, caller ID end-to-end, how the cluster places long text). My scooter's battery is flat at the moment, so I'm especially looking for owners willing to run a 5-minute capture (the app has a built-in log; I can share exact steps).

If anyone is interested in testing or contributing, please DM or open an issue on GitHub. Happy to share the APK and a short how-to.

---

## 5. Team-BHP — Ntorq ownership thread (reply)

(Shorter, owner-focused.)

Hi all,

For anyone frustrated with the official TVS Connect app (account required, MapMyIndia navigation, background disconnects), I've built an open-source alternative: **TVSRideCompanion**.

- No TVS account, no cloud, no internet permission — everything over Bluetooth.
- Mirrors Google Maps / Waze / any maps app turn-by-turn onto the cluster.
- Live telemetry, find-my-scooter, caller ID, last parked, rider name.
- Open source, APK on GitHub.

Repo: https://github.com/athulkrishna2015/TVSRideCompanion

It's reverse-engineered from the official APK, so a few things still need verification on real bikes (fuel gauge scale, caller ID, navigation rendering). If anyone with a running Ntorq is willing to test and share a log, I'd appreciate it. Happy to share steps.

---

## 6. ZigWheels Ntorq 125 community (short Q&A style)

**Question:** Has anyone tried a third-party companion app for the Ntorq that works without the TVS Connect account?

**Body:** I built one — TVSRideCompanion (open source, free). It connects over Bluetooth like the official app but needs no account/cloud and mirrors Google Maps navigation onto the cluster. Repo: https://github.com/athulkrishna2015/TVSRideCompanion. Looking for Ntorq owners to test and report whether navigation renders correctly on their cluster. Happy to share the APK.

---

## 7. GitHub — issue/PR template text for contributors

**Title:** [Testing] Ntorq 125 — navigation renders on cluster

**Body:**
- Vehicle model: Ntorq 125 (variant: ___)
- App version/commit: ___
- Maps app used: Google Maps / Waze / other
- Did navigation appear on the cluster? Yes/No/Partial
- What was shown (line 1 / line 2 / arrow): ___
- Attached log: `adb exec-out run-as dev.tvsopen cat files/logs/tvsride.log`

---

## Notes for posting
- Post the Reddit ones as text posts, not links, so the community can discuss.
- On TechEnclave, reply in the existing thread rather than starting a new one.
- On xBhp/Team-BHP, reply in the existing ownership thread; don't start a new thread (moderators prefer that).
- Attach a screenshot of the transparency panel — it's the strongest credibility signal for a reverse-engineering project.
- Be explicit that the scooter's battery is flat and some features are unverified; honesty builds trust in this community.
