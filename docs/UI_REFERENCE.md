# UI design reference

This app's interface follows two existing apps. Neither is ours. Both are
credited, and one of them is actually reused under its licence rather than
merely referenced.

## 1. Third-party Jupiter companion (`com.tvs.jupiter`)

- Repo: <https://github.com/overclock98/JupiterRideCompanion>
- Licence: **CC BY-NC 4.0** — the same licence this project ships under, so the
  reuse below is compatible rather than a waiver.
- Screenshots: `JupiterRideCompanion/docs/assets/screenshots/`

**What we reused from it.** Layout and palette, with attribution, per CC BY-NC 4.0:

- Two large telemetry tiles over three small ones, in that arrangement
- Soft pastel tile backgrounds and the rounded action-card pattern
- Header shape: app name, a connection pill, a status dot
- Showing an em dash for values not yet received rather than a misleading `0`
- A prominent full-width action card for the highest-value control
  (Find My Scooter)

**What we did not take.** Their branding. The TVS logo and the Jupiter wordmark
are absent from this app by choice: that project is the Jupiter's companion,
this one is unaffiliated and model-universal, and copying their marks would imply
endorsement. That is a trademark question, separate from the copyright licence
that permits everything in the list above.

Their button-gesture mapping and media-features settings are **not** implemented
and **not** copied — scoped out for now.

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
  endorsement. The pastel palette comes from the Jupiter companion under its
  licence, which is also well suited to a glance.
- **Three tabs, not two.** The Jupiter companion has Home and More. This app
  adds a Log tab, because for a protocol reverse-engineering tool the live frame
  log is a primary surface, not a debug leftover.
- **No cloud screens.** Rides, community, shop, and account sections are absent
  entirely rather than stubbed, because they cannot exist without TVS's
  servers.
- **Transparency panel.** The More tab states which protocol facts are verified
  and which still need hardware confirmation. A reverse-engineering app that
  hides its uncertainty is worse than useless.