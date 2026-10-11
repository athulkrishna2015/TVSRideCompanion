# UI design reference

This app's interface follows two existing apps. Neither is ours; both are
credited, and neither is copied.

## 1. Third-party Jupiter companion (`com.tvs.jupiter`)

- Repo: <https://github.com/overclock98/JupiterRideCompanion>
- Licence: **CC BY-NC 4.0**
- Screenshots: `JupiterRideCompanion/docs/assets/screenshots/`

**What we took from it: the design language only.**

- Material 3 with a two-column card grid for telemetry
- A prominent full-width action card for the highest-value control
  (Find My Scooter)
- A bottom navigation bar with two destinations
- Tap-through chips for gesture → action mapping
- Showing `—` for values not yet received rather than a misleading `0`

The Jupiter companion's author redacted their own parked address and device
name before publishing. We followed that precedent — see §3.

## 2. Official TVS Connect (`com.tvsm.connect`) v8.14.1

Reference screenshots for the feature surface we intend to replace without an
account.

| Screen | What we learned |
| :--- | :--- |
| Dashboard | Vehicle model is reported as **NTORQ 125**; ride totals are cloud data |
| Vehicle Overview | Displays exactly the `NtorqSpeedOMeter1` fields we decoded — top speed, top RPM, best 0-60, distance, fuel gauge. A direct cross-check of our byte offsets. |
| Rides | Ride history and modes (`Race`, `Race x Street`, `Street`) — cloud-backed, maps to `RIDE_MODE` / `STREET_MODE_CODE` / `RACE_MODE_CODE` over BLE |
| Find My Vehicle | **Cloud + account required.** We do this locally with phone GPS. |
| Settings | Brightness, overspeed alert, DND are cluster-settable (`BACKLIGHT`, `OVERSPEED_SETTING`, `DND_STATE`) |

Screenshots live in `ref/`, which is **gitignored** — they contain the owner's
registration number and home location and must not be committed.

## 3. Privacy

`ref/` is excluded in `.gitignore`. It holds real screenshots from the owner's
own account, including a vehicle registration number and a parked-location map.

Rule for this repo: **no screenshot containing personal data is ever committed.**
If reference material is needed in the README, redact first — crop or black out
the registration number, address and map before committing, and say that it was
redacted.

## 4. Deliberate deviations

- **Not TVS's colours.** The official app's racing red/black is a brand
  identity. This project is unaffiliated, so borrowing it would imply
  endorsement. The theme is blue/teal — also more legible at a glance.
- **No cloud screens.** Rides, community, shop, and account sections are absent
  entirely rather than stubbed, because they cannot exist without TVS's
  servers.
- **Transparency panel.** The More tab states which protocol facts are verified
  and which still need hardware confirmation. A reverse-engineering app that
  hides its uncertainty is worse than useless.