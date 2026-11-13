#!/usr/bin/env python3
"""Drive the Jupiter BLE bridge with scripted telemetry so the app can be
tested with no scooter in range.

The bridge (upstream: sudo-Mystic/Scoot-releases, sim/bridge/
jupiter-ble-bridge.py, mirrored locally by tools/fetch-scoot-sim.sh) is a
BlueZ GATT peripheral that advertises the Jupiter service UUID and relays raw
20-byte frames between BLE and a WebSocket on port 8765. This script is the
other end of that WebSocket: it emits cluster->phone frames and prints every
phone->cluster frame the app sends, decoded.

Layout of every frame built here comes from docs/PROTOCOL.md (the Scoot
simulator spec), so a mistake in the app's decoder shows up as a wrong number
on the phone rather than as silence.

Typical session:

    tools/fetch-scoot-sim.sh                    # once
    tools/jupiter-bridge.sh                     # terminal 1, needs root
    tools/jupiter-bench.py --scenario ride      # terminal 2
    android run --apks=/tmp/apk/app-debug.apk   # terminal 3, then connect

`--self-test` needs neither the bridge nor a radio.

This is a display/telemetry bench only. It never answers the 0x9A/0xF2 auth
challenge and has no lock, immobilizer, throttle or brake path (see the
upstream SAFETY.md, mirrored under research/vendor/scoot-sim/docs/).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time

SERVICE = "5456534d-5647-5341-5342-454e544f5251"
SIZE = 20
END = 0xFF
STARTED = time.monotonic()
INBOUND_START = 0x5A
OUTBOUND_START = 0x5B

# Phone -> cluster IDs we know how to name.
OUTBOUND_NAMES = {
    0x22: "user-id",
    0x43: "caller",
    0x4A: "keepalive",
    0x4C: "text-row-1",
    0x4E: "nav-control",
    0x4F: "nav-text",
    0x50: "pictogram",
    0x52: "rider-name",
    0x53: "sms",
    0x60: "play-pause",
    0x61: "media-meta",
    0x62: "media-meta",
    0x65: "media-meta",
    0x6B: "music-command",
    0x73: "calibration",
    0xF1: "brightness",
}


def checksum(frame: bytes) -> int:
    """255 - (sum(bytes[0..17]) % 256), per PROTOCOL.md section 2."""
    return (255 - (sum(b & 0xFF for b in frame[:18]) % 256)) & 0xFF


def finish(b: bytearray, *, start: int = INBOUND_START) -> bytes:
    """Close an inbound frame.

    No checksum is written. Byte 18 of an inbound 0x10 is the **RPM low
    byte**, not a checksum: the cluster does not compute the phone-side
    formula there, and the real app never verifies inbound checksums
    (PROTOCOL.md section 1 and the upstream verification report). Writing one
    here would silently corrupt the RPM.
    """
    b[0] = start
    b[19] = END
    return bytes(b)


def be(value: int, width: int) -> bytes:
    return max(0, min(int(value), (1 << (8 * width)) - 1)).to_bytes(width, "big")


# ---- inbound (cluster -> phone) builders ----------------------------------


def primary(
    speed=0,
    odo_km=0.0,
    fuel_raw=0,
    avg_speed=0,
    mode=0,
    top_speed=0,
    throttle=0,
    backlight=1,
    ign=128,
    t60=0,
    trip_km=0.0,
    rpm=0,
) -> bytes:
    """0x10. odometer and trip are raw tenths of a km."""
    b = bytearray(SIZE)
    b[1] = 0x10
    b[2] = speed & 0xFF
    b[3:6] = be(round(odo_km * 10), 3)
    b[6] = fuel_raw & 0xFF
    b[7] = avg_speed & 0xFF
    b[8] = mode & 0xFF
    b[9] = top_speed & 0xFF
    b[10] = throttle & 0xFF
    b[11] = backlight & 0xFF
    b[12] = ign & 0xFF
    b[13] = t60 & 0xFF
    b[14:17] = be(round(trip_km * 10), 3)
    b[17:19] = be(rpm, 2)
    return finish(b)


def service(
    reminder=0,
    mil_blink=0,
    diagnostics=0,
    model=2,
    sw_version=5,
    vehicle_state=2,
    isg_blink=0,
    indicators=0,
    theme=2,
) -> bytes:
    """0x11. diagnostics bit 2 is the MIL lamp."""
    b = bytearray(SIZE)
    b[1] = 0x11
    b[4] = reminder & 0xFF
    b[6] = vehicle_state & 0xFF
    b[7] = sw_version & 0xFF
    b[8] = mil_blink & 0xFF
    b[9] = model & 0xFF
    b[10] = diagnostics & 0xFF
    b[11] = isg_blink & 0xFF
    b[12] = indicators & 0xFF
    b[14] = theme & 0xFF
    return finish(b)


def engine(
    load=0,
    injection_ms=0,
    map_kpa=0,
    baro_u714=0,
    intake_c=0,
    coolant_c=0,
    baro_hpa=1000,
    battery_v=12.4,
    runtime_s=0,
    distance_km=0,
    injection_volume=0,
) -> bytes:
    """0x18. battery is raw tenths of a volt (byte 11), no offset."""
    b = bytearray(SIZE)
    b[1] = 0x18
    b[2] = load & 0xFF
    b[3:5] = be(injection_ms, 2)
    b[5] = map_kpa & 0xFF
    b[6] = baro_u714 & 0xFF
    b[7] = (intake_c + 40) & 0xFF  # stock sends ambient + 40
    b[8] = coolant_c & 0xFF
    b[9:11] = be(baro_hpa, 2)
    b[11] = round(battery_v * 10) & 0xFF
    b[12:14] = be(runtime_s, 2)
    b[14:16] = be(distance_km, 2)
    b[16:18] = be(injection_volume, 2)
    return finish(b)


def economy(
    iss_duration=0, ota=1, avg_fe=0, instant_fe=0, dte_km=0, iss_count=0
) -> bytes:
    """0x19. average FE is byte 8, instantaneous is byte 9."""
    b = bytearray(SIZE)
    b[1] = 0x19
    b[2:5] = be(iss_duration, 3)
    b[5] = ota & 0xFF
    b[8] = avg_fe & 0xFF
    b[9] = instant_fe & 0xFF
    b[11:13] = be(dte_km, 2)
    b[15:18] = be(iss_count, 3)
    return finish(b)


# ---- scenarios ------------------------------------------------------------


class Ride:
    """A small deterministic ride so two runs produce identical frames."""

    def __init__(self, name: str):
        self.name = name
        self.t0 = time.monotonic()
        self.odo = 1234.5
        self.trip = 0.0

    def elapsed(self) -> float:
        return time.monotonic() - self.t0

    def tick(self, dt: float) -> dict[int, bytes]:
        t = self.elapsed()
        out: dict[int, bytes] = {}

        if self.name == "idle":
            # Key off: ignition byte 64, everything else zero. The app should
            # show dashes, not zeros, for the values it cannot know.
            out[0x10] = primary(ign=64)
            out[0x11] = service(indicators=0)
            out[0x18] = engine(battery_v=12.1)
            out[0x19] = economy(dte_km=0)
            return out

        if self.name == "fault":
            out[0x10] = primary(speed=0, odo_km=self.odo, ign=64, fuel_raw=0x35)
            out[0x11] = service(reminder=1, mil_blink=3, diagnostics=0x04)
            out[0x18] = engine(battery_v=12.0, coolant_c=41)
            out[0x19] = economy(dte_km=0)
            return out

        # ride / fuel-low: accelerate to 42, hold, then coast.
        if t < 5:
            speed = int(42 * t / 5)
        elif t < 25:
            speed = 42
        else:
            speed = max(0, int(42 - (t - 25) * 4))

        self.odo += speed * dt / 3600.0
        self.trip += speed * dt / 3600.0
        rpm = speed * 85

        fuel = 0x35
        dte = 180
        if self.name == "fuel-low":
            fuel = 0x13  # 125: 3 bars of 15; 110: raw 19
            dte = 12

        out[0x10] = primary(
            speed=speed,
            odo_km=self.odo,
            fuel_raw=fuel,
            avg_speed=38,
            mode=1,
            top_speed=89,
            throttle=64 if speed else 0,
            ign=128,
            trip_km=self.trip,
            rpm=rpm,
        )
        out[0x11] = service(indicators=(1 if t < 2 else 0))
        out[0x18] = engine(
            load=45,
            injection_ms=300,
            map_kpa=78,
            baro_u714=101,
            intake_c=35,
            coolant_c=87,
            baro_hpa=1000,
            battery_v=12.6,
            runtime_s=int(t),
            distance_km=int(self.trip),
            injection_volume=600,
        )
        out[0x19] = economy(
            iss_duration=30,
            ota=1,
            avg_fe=52,
            instant_fe=45,
            dte_km=dte,
            iss_count=7,
        )
        return out


# ---- outbound (phone -> cluster) decoding ---------------------------------


def text_of(frame: bytes, start: int) -> str:
    raw = bytes(b for b in frame[start:] if b not in (0x00, 0xFF))
    return raw.decode("utf-8", "replace").strip()


def decode_outbound(frame: bytes) -> str:
    if len(frame) != SIZE:
        return f"length {len(frame)}"
    start, data_id = frame[0], frame[1]
    name = OUTBOUND_NAMES.get(data_id)
    if start == 0x5A and data_id == 0x4E:
        dist = int.from_bytes(frame[2:4], "big")
        eta = int.from_bytes(frame[4:6], "big")
        rem = int.from_bytes(frame[6:9], "big")
        return (
            f"nav-control dist={dist}m eta={eta}min remaining={rem}m "
            f"pictogram={frame[9]} rows={frame[10]} status=0x{frame[11]:02X}"
        )
    if start == 0x5A and data_id == 0xF1:
        wire = frame[2]
        level = 1 if wire == 1 else max(1, min(5, wire // 2))
        return f"brightness level={level} (wire {wire})"
    if data_id == 0x4A:
        day, month, year = frame[12], frame[13], frame[14]
        return (
            f"keepalive {frame[15]:02d}:{frame[16]:02d} "
            f"{day:02d}/{month:02d}/{year:02d} "
            f"find-me={frame[17]} checksum=0x{frame[18]:02X}"
        )
    if data_id in (0x4C, 0x4F, 0x50, 0x52, 0x53):
        return f"{name} {text_of(frame, 2)!r}"
    if data_id == 0x43:
        sub = {1: "number", 2: "name", 3: "country"}.get(frame[2], hex(frame[2]))
        return f"caller {sub} {text_of(frame, 3)!r}"
    if data_id in (0x61, 0x62, 0x65):
        return f"{name} id=0x{data_id:02X} {text_of(frame, 2)!r}"
    if data_id == 0x6B:
        cmd = frame[2]
        names = {
            0: "play",
            1: "pause",
            2: "toggle",
            3: "next",
            4: "previous",
            5: "volume-up",
            6: "volume-down",
        }
        return f"music-command {names.get(cmd, f'0x{cmd:02X}')}"
    if data_id == 0x22:
        return f"user-id {frame[2:8].hex()} pad={frame[8]:02X}"
    if data_id == 0x73:
        return (
            f"calibration sub=0x{frame[4]:02X} value=0x"
            f"{int.from_bytes(frame[5:7], 'little'):04X}"
        )
    if data_id == 0x60:
        return f"play-pause state=0x{frame[7]:02X}"
    return f"id=0x{data_id:02X} start=0x{start:02X} {frame[:8].hex()}"


def check_outbound(frame: bytes) -> list[str]:
    """Structural complaints about a frame the app sent.

    The checksum covers bytes 0..17 with the section 2 formula. Inbound
    checksums are informational, but everything the phone sends is supposed to
    carry one, so a mismatch here is a real bug in our builders.
    """
    problems = []
    if len(frame) != SIZE:
        problems.append(f"length {len(frame)} != 20")
        return problems
    if frame[19] != END:
        problems.append(f"end byte 0x{frame[19]:02X} != 0xFF")
    # 0x4E and 0xF1 lead with 0x5A; everything else leads with 0x5B.
    allowed = {0x5A} if frame[1] in (0x4E, 0xF1) else {OUTBOUND_START}
    if frame[0] not in allowed:
        problems.append(f"start byte 0x{frame[0]:02X} not in {sorted(allowed)}")
    # The nav-control and brightness frames are exempt from the checksum.
    if frame[1] not in (0x4E, 0xF1, 0x73) and frame[18] != checksum(frame):
        problems.append(
            f"checksum 0x{frame[18]:02X} != 0x{checksum(frame):02X}"
        )
    return problems


# ---- transport ------------------------------------------------------------


async def run(args) -> int:
    import websockets

    ride = Ride(args.scenario)
    next_tick: dict[int, float] = {}

    async with websockets.connect(args.url, max_size=None) as ws:
        await ws.send(json.dumps({"t": "join", "role": "sim"}))
        first = await ws.recv()
        log_line(f"bridge: {first}")

        outbox: asyncio.Queue[bytes | None] = asyncio.Queue()

        async def reader():
            async for message in ws:
                if isinstance(message, str):
                    log_line(f"bridge: {message}")
                    continue
                frame = bytes(message)
                if len(frame) != SIZE:
                    log_line(f"app: {len(frame)} bytes (ignored)")
                    continue
                problems = check_outbound(frame)
                log_line(f"app: {decode_outbound(frame)}")
                for p in problems:
                    log_line(f"app: BAD FRAME {p}: {frame.hex()}")
                if args.jsonl:
                    record(args.jsonl, "in", frame)

        async def emitter():
            tick = 0
            while True:
                now = time.monotonic()
                for data_id, frame in ride.tick(args.tick / 1000).items():
                    due = next_tick.get(data_id, 0)
                    if now >= due:
                        # 0x10 streams at the tick rate, the rest once a second.
                        next_tick[data_id] = now + (
                            args.tick / 1000 if data_id == 0x10 else 1.0
                        )
                        await outbox.put(frame)
                tick += 1
                await asyncio.sleep(args.tick / 1000)

        async def sender():
            while True:
                frame = await outbox.get()
                if frame is None:
                    return
                await ws.send(frame)
                if args.jsonl:
                    record(args.jsonl, "out", frame)

        tasks = [
            asyncio.create_task(reader()),
            asyncio.create_task(emitter()),
            asyncio.create_task(sender()),
        ]
        try:
            await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for t in tasks:
                t.cancel()
    return 0


def record(path: str, direction: str, frame: bytes) -> None:
    """Append one JSONL line in the sim recorder's own shape.

    `t` is milliseconds since the bench started, matching the upstream
    recorder, so a recording replays with the original timing.
    """
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(
            json.dumps(
                {
                    "t": int((time.monotonic() - STARTED) * 1000),
                    "dir": direction,
                    "hex": frame.hex(),
                }
            )
            + "\n"
        )


def log_line(message: str) -> None:
    print(f"{time.strftime('%H:%M:%S')} {message}", flush=True)


# ---- self-test ------------------------------------------------------------


def self_test() -> int:
    failures = 0

    def expect(label, actual, wanted):
        nonlocal failures
        if actual != wanted:
            failures += 1
            print(f"FAIL {label}: {actual!r} != {wanted!r}")
        else:
            print(f"ok   {label}")

    # Checksum fixtures from PROTOCOL.md section 2 (first 18 bytes zero).
    zero = bytes(18)
    expect("checksum(all zero)", (255 - sum(zero) % 256) & 0xFF, 255)
    all_ff = bytes([0xFF] * 18)
    expect("checksum(all FF)", (255 - sum(all_ff) % 256) & 0xFF, 17)

    # Golden vectors from the project's verification report.
    p = primary(speed=42, odo_km=12345 / 10, fuel_raw=0x35, rpm=3600)
    expect("0x10 start", p[0], 0x5A)
    expect("0x10 speed", p[2], 42)
    expect("0x10 odometer raw24", int.from_bytes(p[3:6], "big"), 12345)
    expect("0x10 rpm BE", int.from_bytes(p[17:19], "big"), 3600)
    expect("0x10 end", p[19], 0xFF)

    e = engine(battery_v=12.6, coolant_c=87, intake_c=35)
    expect("0x18 battery raw", e[11], 126)
    expect("0x18 coolant", e[8], 87)
    expect("0x18 intake +40", e[7], 75)

    ec = economy(avg_fe=52, instant_fe=45, dte_km=180)
    expect("0x19 avg FE", ec[8], 52)
    expect("0x19 instant FE", ec[9], 45)
    expect("0x19 DTE BE", int.from_bytes(ec[11:13], "big"), 180)

    s = service(reminder=1, mil_blink=3, diagnostics=0x04)
    expect("0x11 reminder", s[4], 1)
    expect("0x11 MIL bit", s[10] & 0x04, 4)

    # Decoder round-trip: a frame the app sends must decode, not crash.
    # 12 header/field bytes, 7 bytes of padding, 0xFF terminator.
    nav = bytes.fromhex("5a4e00fa000c003039020101" + "00" * 7 + "ff")
    expect(
        "decode nav-control",
        decode_outbound(nav),
        "nav-control dist=250m eta=12min remaining=12345m pictogram=2 rows=1 status=0x01",
    )

    # An outbound frame as the app would send it: 0x5B lead, checksum at 18.
    good = bytearray(20)
    good[0] = OUTBOUND_START
    good[1] = 0x4A
    good[18] = checksum(bytes(good))
    good[19] = END
    expect("no complaints on a valid frame", check_outbound(bytes(good)), [])

    bad = bytearray(good)
    bad[18] = (bad[18] + 1) & 0xFF
    expect("checksum mismatch detected", len(check_outbound(bytes(bad))), 1)

    print(f"\n{failures} failure(s)")
    return 0 if failures == 0 else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="ws://127.0.0.1:8765")
    parser.add_argument(
        "--scenario",
        choices=["idle", "ride", "fuel-low", "fault"],
        default="ride",
        help="idle = key off, ride = 42 km/h sweep, fuel-low = 3 bars, fault = MIL on",
    )
    parser.add_argument("--tick", type=int, default=100, help="milliseconds per tick")
    parser.add_argument("--jsonl", help="append a recording in the sim recorder format")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0
    except OSError as exc:
        print(f"cannot reach the bridge at {args.url}: {exc}", file=sys.stderr)
        print("start tools/jupiter-bridge.sh first", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
