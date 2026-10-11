# Scope

## What this project is

A third-party Android companion app for **TVS SmartXonnect vehicles** — Ntorq,
Jupiter, Apache, Raider, iQube, and the rest — that talks to the vehicle's
instrument cluster over Bluetooth Low Energy and mirrors navigation from *any*
maps app the user already runs (Google Maps, MapMyIndia, Organic Maps, …).

It is **universal by architecture, Ntorq-verified by hardware.** The official app
already splits its protocol code one package per model, so we mirror that: one
shared core, one small driver per model, selected at runtime. Models without a
finished driver still connect and report raw frames instead of failing. Only the
Ntorq has been confirmed against a real scooter so far; see
`research/SOURCES.md` §4 for what each other model still needs.

No account. No TVS server. No cloud.

## Explicitly out of scope

| Out of scope | Why |
| --- | --- |
| TVS account registration or login | The user does not want an account. The app must be fully functional signed-out. |
| TVS cloud / SmartXonnect backend APIs | Everything works locally over BLE. No round trip to TVS. |
| Reusing the CVEs in the FEV VAPT report (CVE-2024-33308/33309/35537) | Those are server-side flaws. Using them would mean attacking someone else's data. They are **not** a design input. |
| Re-deriving or repackaging the official APK | The official APK is read as a *specification source only*, kept outside this repo. |
| Fingerprinting or tracking other vehicles | The app only talks to the one scooter the owner pairs. |

## Why BLE-only is the right architecture

The instrument cluster has its own BLE link to the phone. Everything the
dashboard renders — navigation arrow, distance, street name, caller ID, song
title, odometer, range — arrives over that link. None of it requires the TVS
backend. The backend exists for *remote* features (live tracking while the
phone is away, geofencing, service booking), which are not needed when the
phone is physically on the scooter.

Dropping the cloud dependency is what makes "no login" possible at all, and it
is also what removes the failure modes the official app is criticised for.

## Legal posture

Interoperability work on hardware you own is legitimate. This repo is
non-commercial and publishes the protocol documentation it derives, which is
the part that helps other owners. See `LICENSE`.

Derived documentation in `docs/PROTOCOL.md` reuses prose and the maneuver
byte table from `overclock98/JupiterRideCompanion` (CC BY-NC 4.0), under
attribution and on the same non-commercial terms. That upstream constraint
therefore applies to those files.