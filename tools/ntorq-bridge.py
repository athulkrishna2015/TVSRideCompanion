#!/usr/bin/env python3
"""
Ntorq BLE bridge: makes the Linux laptop look like a TVS Ntorq 125
SmartXonnect cluster, and pipes 20-byte Ntorq frames to/from a WebSocket
client (the Scoot sim webapp, or any controller script).

    phone app  <--BLE GATT-->  this bridge  <--WebSocket-->  controller

Clean-room tool for TVSRideCompanion: the outbound decoders mirror the
app's own builders byte-for-byte (Keepalive.kt, PacketBuilder.kt,
NtorqDriver.kt) and the inbound frames follow NtorqDriver's field tables
cross-checked against the 2026-10-13 capture in docs/PROTOCOL.md §4a.
It is deliberately NOT in research/vendor/scoot-sim/ — that directory
stays a verbatim upstream mirror.

Ntorq fidelity notes (docs/PROTOCOL.md §4b):
  * The real cluster advertises NO service UUID — discovery happens by
    name only. Default advertisement therefore carries a local name
    (default "TVSBT800450") and no UUID; pass --advertise-uuid to make
    the "Only SmartXonnect" scan filter able to see it (unfaithful
    convenience mode).
  * Telemetry flows only with ignition ON (ctl JSON or --ignition).
  * Two all-zero 20-byte frames arrive at connect time before real
    telemetry; replicated here.
  * One `5B 42` frame carrying the cluster address at idx 7..12 is
    emitted once at link-up, as observed in the capture.
  * 0x16 arrives about as often as 0x10 on real hardware and has no
    known layout; the bridge re-emits the byte pattern captured in §4a
    (static, honestly unknown) unless --no-16.
  * 0x19 was never observed on this cluster; off unless --with-19.

SAFETY BOUNDARY (same as the Jupiter bridge, non-negotiable):
  telemetry out over BLE notify, app writes decoded and logged in.
  No lock/unlock, no immobilizer, no throttle/brake control, and auth
  frames (0xF1/0xF2 challenge-response) are detected and dropped, never
  answered. Calibration writes (0x37) are logged only — never answered,
  because calibrating a cluster alters its behaviour.

Wire protocol on the WebSocket:
  client -> bridge text : {"t":"join","role":"sim"}    (any client)
                          {"t":"ctl", ...}             (control, below)
  bridge -> client text : {"t":"joined"}
                          {"t":"ble","connected":bool} on link change
                          {"t":"state", {...}}          periodic sim state
                          {"t":"app", {...}}            per decoded app write
  client -> bridge bin  : 20 raw bytes -> forwarded to the phone as-is
                          (manual frame injection; overrides nothing)
  bridge -> client bin  : 20 raw bytes notified to the phone

Control messages ({"t":"ctl", ...}):
  {"ignition": true|false}        ignition state (telemetry gate)
  {"cruise": <kph>}               target speed, 0 = coast to a stop
  {"fuel": <0..6>}                fuel gauge bars
  {"odo": <km>}                   set odometer
  {"resetTrip": true}             zero trip F / run time
  {"music": <0..6>}               emit a 0x6B cluster music-key frame
CLI pre-sets: --ignition --cruise KPH --fuel N --odo KM.

Run:
  python3 tools/ntorq-bridge.py                 # BLE on, WS on :8766
  python3 tools/ntorq-bridge.py --selftest      # offline layout checks
Requires: websockets, dbus-next (BLE only), BlueZ running, and the user
in the `bluetooth` group (or root) to register a GATT application.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import struct
import sys
import time

log = logging.getLogger("ntorq-bridge")

FRAME_SIZE = 20
START_CLUSTER = 0x5A  # cluster -> phone frames
START_PHONE = 0x5B    # phone -> cluster frames
START_AUTH_A = 0x9A   # challenge-response (inbound direction observed)
START_AUTH_B = 0x9B   # challenge / calibration
END = 0xFF

SERVICE_UUID = "5456534D-5647-5341-5342-454E544F5251"
WRITE_UUID = "00005352-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "00005354-0000-1000-8000-00805f9b34fb"

# Cadence (ms), matched to the 2026-10-13 capture (~2-3 frames/s total).
CADENCE = {"0x10": 1000, "0x11": 2000, "0x16": 1000, "0x18": 1000, "0x19": 5000}

# Captured 0x16 pattern (docs/PROTOCOL.md §4a). Layout unknown; re-emitted
# statically rather than invented per-field.
FRAME_16_CAPTURE = bytes.fromhex("5A160000000003280045000000960900000000FF")


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def put_u16_be(b: bytearray, off: int, v: int) -> None:
    v = max(0, min(0xFFFF, int(v))) & 0xFFFF
    b[off] = (v >> 8) & 0xFF
    b[off + 1] = v & 0xFF


def put_u24_be(b: bytearray, off: int, v: int) -> None:
    v = max(0, min(0xFFFFFF, int(v))) & 0xFFFFFF
    b[off] = (v >> 16) & 0xFF
    b[off + 1] = (v >> 8) & 0xFF
    b[off + 2] = v & 0xFF


def get_u16_be(b: bytes, off: int) -> int:
    return (b[off] << 8) | b[off + 1]


def get_u24_be(b: bytes, off: int) -> int:
    return (b[off] << 16) | (b[off + 1] << 8) | b[off + 2]


def new_frame(data_id: int, start: int = START_CLUSTER) -> bytearray:
    b = bytearray(FRAME_SIZE)
    b[0] = start
    b[1] = data_id
    b[19] = END
    return b


def ascii_payload(b: bytearray, text: str, first: int, last: int) -> bytearray:
    """Text rows carry ASCII in [first..last] inclusive (17 chars for nav)."""
    data = text.encode("ascii", "replace")[: last - first + 1]
    b[first : first + len(data)] = data
    return b


# ---------------------------------------------------------------------------
# inbound builders (cluster -> phone), following NtorqDriver's tables
# ---------------------------------------------------------------------------

def build_sm1(speed, odo_km, fuel_bars, avg_speed, top_speed, throttle_pct,
              trip_f_km, rpm, ignition) -> bytearray:
    # SM1 (0x10): SPEED[2] ODO[3..5]x0.1 FUEL[6] AVG[7] TRIP_STATUS[8]
    # TOP[9] THROTTLE[10] BACKLIGHT[11] LOCATION_TAG[12] TIME60[13]
    # TRIP_F[14..16]x0.1 RPM[17..18] END[19].
    # Byte 12: 128 = ignition ON, 64 = off (Scoot ntorq profile, dex-verified).
    # Byte 11 mirrors the capture (0x50: illumination nibble 5, pair 0).
    b = new_frame(0x10)
    b[2] = max(0, min(255, round(speed))) & 0xFF
    put_u24_be(b, 3, round(odo_km * 10))
    b[6] = max(0, min(6, fuel_bars)) & 0x0F
    b[7] = max(0, min(255, round(avg_speed))) & 0xFF
    b[8] = 0  # TRIP_STATUS: 0
    b[9] = max(0, min(255, round(top_speed))) & 0xFF
    b[10] = max(0, min(100, round(throttle_pct))) & 0xFF
    b[11] = 0x50
    b[12] = 128 if ignition else 64
    b[13] = 0  # TIME_60KMPH
    put_u24_be(b, 14, round(trip_f_km * 10))
    put_u16_be(b, 17, max(0, min(0xFFFF, round(rpm))))
    return b


def build_sm2(software_version=0x12, vehicle_model=0xA3) -> bytearray:
    # SM2 (0x11): FUEL_SENSOR[2] VEHICLE_STATE[3] SERVICE_REMINDER[4]
    # VEHICLE_STATE_1[6] SOFTWARE_VERSION[7] MIL_BLINK[8] VEHICLE_MODEL[9]
    # DIAGNOSTICS[10] ISG_BLINK[11] TURN_INDICATOR[12] CONNECTOR[13] END[19].
    # 0xA3 and 0x20 are the values observed in the §4a capture.
    b = new_frame(0x11)
    b[3] = 1   # VEHICLE_STATE: 1 = awake/armed
    b[7] = software_version & 0xFF
    b[9] = vehicle_model & 0xFF
    b[10] = 0x20
    return b


def build_sm4(phys) -> bytearray:
    # SM4 (0x18): ENGINE_LOAD[2] ACCUM_FUEL_INJ[3..4] BARO[6] INTAKE[7]
    # ENGINE_TEMP[8] FUEL_INJ_TIME[9..10] BATTERY[11]x0.1 RUN_TIME[12..13]
    # DISTANCE[14..15] FUEL_INJ_VOLUME[16..17] RESERVED[18] END[19].
    b = new_frame(0x18)
    b[2] = max(0, min(100, round(phys.throttle_pct))) & 0xFF
    put_u16_be(b, 3, round(phys.fuel_inj_ms))
    b[6] = 101  # barometric kPa, ~sea level (capture read 0x64 = 100)
    b[7] = max(0, min(255, round(phys.intake_temp))) & 0xFF
    b[8] = max(0, min(255, round(phys.engine_temp))) & 0xFF
    put_u16_be(b, 9, round(phys.fuel_inj_ms * 10) & 0xFFFF)
    b[11] = max(0, min(255, round(phys.battery_v * 10))) & 0xFF
    put_u16_be(b, 12, round(phys.run_time_s) & 0xFFFF)
    put_u16_be(b, 14, round(phys.trip_km * 10) & 0xFFFF)
    put_u16_be(b, 16, round(phys.trip_fuel_l * 1000) & 0xFFFF)
    return b


def build_sm3(avg_kmpl, instant_kmpl, dte_km, iss_count) -> bytearray:
    # SM3 (0x19): ISS_DURATION[2..4] OTA[5] AVG_ECO[8] INST_ECO[9]
    # DTE[11..12] ISS_COUNT[15..17] END[19]. Not emitted by default —
    # the real cluster never sent it (§4a).
    b = new_frame(0x19)
    put_u24_be(b, 2, 0)
    b[8] = max(0, min(255, round(avg_kmpl))) & 0xFF
    b[9] = max(0, min(255, round(instant_kmpl))) & 0xFF
    put_u16_be(b, 11, max(0, min(0xFFFF, round(dte_km * 10))))
    put_u24_be(b, 15, max(0, min(0xFFFFFF, round(iss_count))))
    return b


def build_address_frame(addr: str) -> bytearray:
    # Observed once per link: `5B 42`, cluster's own BLE address at [7..12].
    parts = addr.replace(":", " ").split()
    b = new_frame(0x42, start=START_PHONE)
    for i, p in enumerate(parts[:6]):
        b[7 + i] = int(p, 16)
    return b


def build_music(command: int) -> bytearray:
    # 0x6B carries the cluster music-key command map 0..6 (play, pause,
    # toggle, next, previous, vol+, vol-).
    b = new_frame(0x6B)
    b[2] = max(0, min(6, command)) & 0xFF
    return b


# ---------------------------------------------------------------------------
# outbound decoders (phone -> cluster), mirroring the app's builders
# ---------------------------------------------------------------------------

def decode_keepalive(b: bytes) -> dict:
    """Keepalive.kt layout: [2] sig<<4|batt, [3] overspeed, [4] temp+40,
    [5]=2, [6..8] h/m/s (hour 1..12), [9] am/pm, [10] missed calls,
    [12..14] day/month/year%100, [17] find-my flag."""
    return {
        "kind": "keepalive",
        "signal": (b[2] >> 4) & 0x0F,
        "battery": b[2] & 0x0F,
        "overspeedLimit": b[3],
        "ambientTempC": (b[4] - 40) if b[4] else None,
        "hour12": b[6],
        "minute": b[7],
        "second": b[8],
        "pm": bool(b[9]),
        "missedCalls": b[10],
        "day": b[12],
        "month": b[13],
        "year": 2000 + b[14],
        "findMy": bool(b[17]),
    }


def decode_nav_control(b: bytes) -> dict:
    """PacketBuilder.navigationControl: BE u16 distance[2..3], u16 eta[4..5],
    u24 remainingTrip[6..8], pictogram[9], rows[10], status[11]."""
    return {
        "kind": "nav-control",
        "distanceM": get_u16_be(b, 2),
        "etaMinutes": get_u16_be(b, 4),
        "remainingTripM": get_u24_be(b, 6),
        "pictogram": b[9],
        "rows": b[10],
        "status": b[11],
    }


def decode_outbound(b: bytes) -> dict | None:
    """Decode one app write. Returns None for frames this bridge does not
    interpret. Auth (0xF1/0xF2) is reported as {kind:'auth'} and dropped
    by the caller — never answered."""
    if len(b) < FRAME_SIZE or b[19] != END:
        return {"kind": "bad-frame", "len": len(b), "end": b[19] if len(b) > 19 else None}
    start, data_id = b[0], b[1]
    if data_id in (0xF1, 0xF2):
        return {"kind": "auth", "start": start, "dataId": data_id}
    if data_id == 0x37:
        # Calibration write. Logged only: never answer, never replay.
        return {"kind": "calibration", "command": b[2], "calId": b[3],
                "len": b[4]}
    if start == START_PHONE and data_id == 0x4A:
        return decode_keepalive(b)
    if start == START_CLUSTER and data_id == 0x4E:
        return decode_nav_control(b)
    if start == START_PHONE and data_id in (0x4F, 0x50):
        return {"kind": "nav-text", "dataId": data_id,
                "text": b[2:19].decode("ascii", "replace").rstrip("\x00\x20")}
    if start == START_PHONE and data_id in (0x4C, 0x63):
        return {"kind": "text-row", "row": 1 if data_id == 0x4C else 2,
                "text": b[2:19].decode("ascii", "replace").rstrip("\x00\x20")}
    if start == START_PHONE and data_id == 0x52:
        return {"kind": "rider-name",
                "text": b[2:19].decode("ascii", "replace").rstrip("\x00\x20")}
    if start == START_PHONE and data_id == 0x43:
        return {"kind": "caller", "text": b[2:19].decode("ascii", "replace").rstrip("\x00\x20")}
    if start == START_PHONE and data_id == 0x53:
        return {"kind": "message", "text": b[2:19].decode("ascii", "replace").rstrip("\x00\x20")}
    return {"kind": "unknown", "start": start, "dataId": data_id,
            "hex": b.hex(" ")}


# ---------------------------------------------------------------------------
# physics — minimal Ntorq-125-ish model, all UNVERIFIED tuning constants
# ---------------------------------------------------------------------------

class Physics:
    def __init__(self, odo_km: float, fuel_bars: int, cruise: float):
        self.ignition = False
        self.speed = 0.0
        self.cruise = cruise
        self.odo_km = odo_km
        self.fuel_bars = fuel_bars
        self.top_speed = 0.0
        self.trip_km = 0.0
        self.trip_fuel_l = 0.0
        self.run_time_s = 0.0
        self.battery_v = 12.4
        self.engine_temp = 22.0
        self.intake_temp = 27.0
        self.throttle_pct = 0.0
        self.fuel_inj_ms = 0.0
        self.iss_count = 0
        self.dt = 1.0

    def tick(self) -> None:
        if not self.ignition:
            self.speed = max(0.0, self.speed - 8 * self.dt)
            self.throttle_pct = 0.0
            self.fuel_inj_ms = 0.0
            self.battery_v += (12.4 - self.battery_v) * (1 - pow(2.718, -self.dt / 20))
            self.engine_temp += (22.0 - self.engine_temp) * (1 - pow(2.718, -self.dt / 600))
            return
        # speed chases the cruise target at ~3 kph/s (UNVERIFIED feel)
        target = max(0.0, self.cruise)
        step = 3.0 * self.dt
        if self.speed < target:
            self.speed = min(target, self.speed + step)
        else:
            self.speed = max(target, self.speed - step * 1.5)
        self.throttle_pct = min(100.0, self.speed / 100 * 60 + 8)
        km = self.speed / 3600 * self.dt
        self.odo_km += km
        self.trip_km += km
        burned = km / 40.0  # ~40 km/L average (UNVERIFIED)
        self.trip_fuel_l += burned
        self.run_time_s += self.dt
        self.iss_count += 1
        self.top_speed = max(self.top_speed, self.speed)
        rpm = 0.0 if self.speed < 0.5 else 1400 + (self.speed / 98) * 6100 * (0.55 + 0.45 * self.throttle_pct / 100)
        self.fuel_inj_ms = rpm / 60 * 2 * 0.004 * 1000  # UNVERIFIED
        self.battery_v += (13.4 - self.throttle_pct / 100 * 0.7 - self.battery_v) \
            * (1 - pow(2.718, -self.dt / 20))
        self.engine_temp += (90.0 - self.engine_temp) * (1 - pow(2.718, -self.dt / 120))

    def state(self) -> dict:
        return {
            "ignition": self.ignition, "speedKph": round(self.speed, 1),
            "cruiseKph": self.cruise, "odoKm": round(self.odo_km, 1),
            "fuelBars": self.fuel_bars, "topSpeedKph": round(self.top_speed, 1),
            "tripFKm": round(self.trip_km, 2), "runTimeS": round(self.run_time_s),
            "batteryV": round(self.battery_v, 1),
            "engineTempC": round(self.engine_temp, 1),
            "rpm": round(0 if self.speed < 0.5 else 1400 + (self.speed / 98) * 6100),
        }


# ---------------------------------------------------------------------------
# telemetry source: paced inbound frames to the phone
# ---------------------------------------------------------------------------

class Telemetry:
    def __init__(self, phys: Physics, opts) -> None:
        self.phys = phys
        self.opts = opts
        self.next_at = {k: 0.0 for k in CADENCE}
        self.address_frame_done = False
        self.zero_frames_left = 2  # capture: two all-zero frames at connect

    def poll(self, now: float) -> list[bytes]:
        """Return the frames due now. Called every ~100 ms."""
        p = self.phys
        out: list[bytes] = []
        if self.zero_frames_left and p.ignition:
            out.extend([bytes(FRAME_SIZE) for _ in range(self.zero_frames_left)])
            self.zero_frames_left = 0
        if not self.address_frame_done:
            out.append(build_address_frame(self.opts.address))
            self.address_frame_done = True
        if not p.ignition:
            return out
        p.tick()
        for key, period in CADENCE.items():
            if key == "0x19" and not self.opts.with_19:
                continue
            if key == "0x16" and self.opts.no_16:
                continue
            if now >= self.next_at[key]:
                self.next_at[key] = now + period / 1000.0
                if key == "0x10":
                    out.append(build_sm1(p.speed, p.odo_km, p.fuel_bars,
                                         p.trip_km / (p.run_time_s / 3600) if p.run_time_s else 0,
                                         p.top_speed, p.throttle_pct,
                                         p.trip_km, 1400 + (p.speed / 98) * 6100 if p.speed >= 0.5 else 0,
                                         p.ignition))
                elif key == "0x11":
                    out.append(build_sm2())
                elif key == "0x16":
                    out.append(FRAME_16_CAPTURE)
                elif key == "0x18":
                    out.append(build_sm4(p))
                elif key == "0x19":
                    out.append(build_sm3(40, p.throttle_pct / 8, 60, p.iss_count))
        return out


# ---------------------------------------------------------------------------
# WebSocket hub (single sim client; extra joins replace it)
# ---------------------------------------------------------------------------

class Hub:
    def __init__(self) -> None:
        self.client = None
        self.lock = asyncio.Lock()
        self.app_events = asyncio.Queue(maxsize=64)

    async def set_client(self, ws) -> None:
        async with self.lock:
            old, self.client = self.client, ws
        if old is not None and old is not ws:
            try:
                await old.close()
            except Exception:
                pass

    async def drop(self, ws) -> None:
        async with self.lock:
            if self.client is ws:
                self.client = None

    async def send_json(self, obj: dict) -> None:
        async with self.lock:
            ws = self.client
        if ws is None:
            return
        try:
            await ws.send(json.dumps(obj))
        except Exception:
            pass

    async def send_binary(self, data: bytes) -> None:
        async with self.lock:
            ws = self.client
        if ws is None:
            return
        try:
            await ws.send(data)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# BLE layer (BlueZ D-Bus GATT server). dbus-next imported lazily.
# ---------------------------------------------------------------------------

BLUEZ = "org.bluez"
DBUS = "org.freedesktop.DBus"
PROPS_IFACE = "org.freedesktop.DBus.Properties"
DEVICE_IFACE = "org.bluez.Device1"
GATT_MANAGER_IFACE = "org.bluez.GattManager1"
ADV_MANAGER_IFACE = "org.bluez.LEAdvertisingManager1"

APP_PATH = "/dev/tvsopen/ntorq"
APP_SVC_PATH = "/dev/tvsopen/ntorq/svc"
APP_WRITE_PATH = "/dev/tvsopen/ntorq/write"
APP_NOTIFY_PATH = "/dev/tvsopen/ntorq/notify"
APP_ADV_PATH = "/dev/tvsopen/ntorq/adv"


def _dbus():
    try:
        import dbus_next
        from dbus_next import BusType, Variant
        from dbus_next.aio import MessageBus
        from dbus_next.message import Message, MessageType
        from dbus_next.service import PropertyAccess, ServiceInterface, \
            dbus_property, method
        return (dbus_next, BusType, Variant, MessageBus, Message, MessageType,
                PropertyAccess, ServiceInterface, dbus_property, method)
    except ImportError:
        log.error("FATAL: dbus-next is not installed. "
                  "pip install dbus-next websockets")
        sys.exit(2)


def _props_dict(iface, ServiceInterface):
    props = {}
    for prop in ServiceInterface._get_properties(iface):
        try:
            value = getattr(iface, prop.name)
        except Exception:
            continue
        from dbus_next import Variant
        props[prop.name] = Variant(prop.signature, value)
    return {iface.name: props}


class BleLayer:
    """Registers the SmartXonnect GATT service and a LE advertisement.

    The advertisement carries NO service UUID by default — the real Ntorq
    cluster does not advertise one (docs/PROTOCOL.md §4b), so discovery is
    by name. Pass advertise_uuid=True to make the "Only SmartXonnect"
    scan filter able to see the sim (unfaithful convenience mode).
    """

    def __init__(self, name: str, advertise_uuid: bool, on_write, on_link) -> None:
        self.name = name
        self.advertise_uuid = advertise_uuid
        self.on_write = on_write      # async fn(bytes)
        self.on_link = on_link        # async fn(bool)
        self.notifying = False
        self.centrals: set[str] = set()
        self._bus = None
        self._loop = None
        self._notify_char = None
        self._adapter_path = None

    async def start(self, adapter: str = "hci0") -> None:
        (dbus_next, BusType, Variant, MessageBus, Message, MessageType,
         PropertyAccess, ServiceInterface, dbus_property, method) = _dbus()
        self._loop = asyncio.get_running_loop()
        self._adapter_path = f"/org/bluez/{adapter}"
        self._dn = (dbus_next, BusType, Variant, MessageBus, Message,
                    MessageType, PropertyAccess, ServiceInterface,
                    dbus_property, method)

        outer = self

        class Application(ServiceInterface):
            def __init__(self, managed):
                super().__init__("org.freedesktop.DBus.ObjectManager")
                self._managed = managed

            @method()
            def GetManagedObjects(self) -> "a{oa{sa{sv}}}":  # noqa: F821
                objs = {}
                for path, ifaces in self._managed:
                    entry = {}
                    for iface in ifaces:
                        entry.update(_props_dict(iface, ServiceInterface))
                    objs[path] = entry
                return objs

        class GattService(ServiceInterface):
            def __init__(self):
                super().__init__("org.bluez.GattService1")

            @dbus_property(PropertyAccess.READ)
            def UUID(self) -> "s":  # noqa: F821
                return SERVICE_UUID

            @dbus_property(PropertyAccess.READ)
            def Primary(self) -> "b":  # noqa: F821
                return True

        class WriteCharacteristic(ServiceInterface):
            def __init__(self):
                super().__init__("org.bluez.GattCharacteristic1")

            @dbus_property(PropertyAccess.READ)
            def UUID(self) -> "s":  # noqa: F821
                return WRITE_UUID

            @dbus_property(PropertyAccess.READ)
            def Service(self) -> "o":  # noqa: F821
                return APP_SVC_PATH

            @dbus_property(PropertyAccess.READ)
            def Flags(self) -> "as":  # noqa: F821
                return ["write"]

            @method()
            def WriteValue(self, value: "ay", options: "a{sv}"):  # noqa: F821
                data = bytes(bytearray(value))
                log.debug("BLE write: %s", data.hex(" "))
                asyncio.run_coroutine_threadsafe(outer.on_write(data), outer._loop)

        class NotifyCharacteristic(ServiceInterface):
            def __init__(self):
                super().__init__("org.bluez.GattCharacteristic1")
                self._value = b""
                self._subscribers = 0

            @dbus_property(PropertyAccess.READ)
            def UUID(self) -> "s":  # noqa: F821
                return NOTIFY_UUID

            @dbus_property(PropertyAccess.READ)
            def Service(self) -> "o":  # noqa: F821
                return APP_SVC_PATH

            @dbus_property(PropertyAccess.READ)
            def Flags(self) -> "as":  # noqa: F821
                return ["notify"]

            @dbus_property(PropertyAccess.READ)
            def Value(self) -> "ay":  # noqa: F821
                return self._value

            @method()
            def StartNotify(self):
                self._subscribers += 1
                outer.notifying = True
                log.info("notify subscribed — link considered up")
                asyncio.run_coroutine_threadsafe(outer.on_link(True), outer._loop)

            @method()
            def StopNotify(self):
                self._subscribers = max(0, self._subscribers - 1)
                if self._subscribers == 0:
                    outer.notifying = False
                    log.info("notify unsubscribed — link down")
                    asyncio.run_coroutine_threadsafe(outer.on_link(False), outer._loop)

            @property
            def notifying(self):
                return self._subscribers > 0

            async def do_notify(self, frame: bytes):
                if not self.notifying:
                    raise RuntimeError("notify with no subscriber")
                payload = bytes(frame)
                self._value = payload
                self.emit_properties_changed({"Value": payload})

        class Advertisement(ServiceInterface):
            def __init__(self):
                super().__init__("org.bluez.LEAdvertisement1")

            @method()
            def Release(self):
                log.info("advertisement released by BlueZ")

            @dbus_property(PropertyAccess.READ)
            def Type(self) -> "s":  # noqa: F821
                return "peripheral"

            @dbus_property(PropertyAccess.READ)
            def LocalName(self) -> "s":  # noqa: F821
                return outer.name

            @dbus_property(PropertyAccess.READ)
            def ServiceUUIDs(self) -> "as":  # noqa: F821
                return [SERVICE_UUID] if outer.advertise_uuid else []

            @dbus_property(PropertyAccess.READ)
            def Includes(self) -> "as":  # noqa: F821
                return []

        svc = GattService()
        write_char = WriteCharacteristic()
        notify_char = NotifyCharacteristic()
        adv = Advertisement()
        managed = [
            (APP_SVC_PATH, [svc]),
            (APP_WRITE_PATH, [write_char]),
            (APP_NOTIFY_PATH, [notify_char]),
        ]
        app = Application(managed)

        self._bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        bus = self._bus
        self._notify_char = notify_char
        self._adv = adv
        self._app = app

        for path, ifaces in {
            APP_PATH: [app],
            APP_SVC_PATH: [svc],
            APP_WRITE_PATH: [write_char],
            APP_NOTIFY_PATH: [notify_char],
            APP_ADV_PATH: [adv],
        }.items():
            for iface in ifaces:
                bus.export(path, iface)

        # track central connect/disconnect so a dropped link clears subscribers
        def on_signal(msg):
            if msg.message_type != MessageType.SIGNAL:
                return False
            if (msg.interface == PROPS_IFACE and msg.member == "PropertiesChanged"
                    and msg.body and msg.body[0] == DEVICE_IFACE):
                changed = msg.body[1]
                if "Connected" in changed and not changed["Connected"].value:
                    self.centrals.discard(msg.path)
                    if not self.centrals:
                        notify_char._subscribers = 0
                        if self.notifying:
                            self.notifying = False
                            asyncio.run_coroutine_threadsafe(self.on_link(False), self._loop)
                elif "Connected" in changed and changed["Connected"].value:
                    self.centrals.add(msg.path)
            return False

        bus.add_message_handler(on_signal)

        introspection = await bus.introspect(BLUEZ, self._adapter_path)
        adapter_obj = bus.get_proxy_object(BLUEZ, self._adapter_path, introspection)
        gatt_mgr = adapter_obj.get_interface(GATT_MANAGER_IFACE)
        adv_mgr = adapter_obj.get_interface(ADV_MANAGER_IFACE)

        log.info("registering GATT application on %s ...", self._adapter_path)
        await gatt_mgr.call_register_application(APP_PATH, {})
        log.info("GATT registered: %s  write=%s notify=%s",
                 SERVICE_UUID, WRITE_UUID, NOTIFY_UUID)

        log.info("starting advertisement as %r (service UUID in adv: %s) ...",
                 self.name, self.advertise_uuid)
        await adv_mgr.call_register_advertisement(APP_ADV_PATH, {})
        log.info("advertising as %r", self.name)

    async def notify_frame(self, data: bytes) -> None:
        if not self.notifying or self._notify_char is None:
            return
        try:
            await self._notify_char.do_notify(data)
        except Exception as exc:
            log.warning("notify failed: %s", exc)

    async def stop(self) -> None:
        if self._bus:
            try:
                await self._bus.disconnect()
            except Exception:
                pass
            self._bus = None

class Bridge:
    def __init__(self, opts) -> None:
        self.opts = opts
        self.hub = Hub()
        self.phys = Physics(opts.odo, opts.fuel, opts.cruise)
        self.telemetry = Telemetry(self.phys, opts)
        self.ble: BleLayer | None = None
        self.link_up = False
        self.stats = {"rx": 0, "tx": 0, "authDropped": 0, "unknown": 0}

    async def on_link(self, up: bool) -> None:
        self.link_up = up
        if up:
            self.telemetry.address_frame_done = False
        await self.hub.send_json({"t": "ble", "connected": bool(up)})
        log.info("link %s", "UP (notify subscribed)" if up else "DOWN")

    async def on_write(self, data: bytes) -> None:
        if len(data) != FRAME_SIZE:
            log.warning("write with odd length %d dropped", len(data))
            return
        d = decode_outbound(data)
        self.stats["rx"] += 1
        if d is None:
            return
        kind = d.get("kind")
        if kind == "auth":
            self.stats["authDropped"] += 1
            log.warning("auth frame detected and DROPPED (safety boundary): %s",
                        data.hex(" "))
            await self.hub.send_json({"t": "app", **d, "dropped": True})
            return
        if kind == "calibration":
            log.info("calibration write logged only, never answered: cmd=%s id=%s",
                     d.get("command"), d.get("calId"))
            await self.hub.send_json({"t": "app", **d, "answered": False})
            return
        if kind in ("bad-frame", "unknown"):
            self.stats["unknown"] += 1
            log.info("uninterpreted write: %s", d.get("hex") or data.hex(" "))
            await self.hub.send_json({"t": "app", **d})
            return
        log.info("app -> cluster: %s", d)
        if d.get("findMy"):
            log.info("FIND MY: cluster would flash indicators and beep")
        if kind == "music" or d.get("kind") == "keepalive":
            pass
        await self.hub.send_json({"t": "app", **d})

    async def ctl(self, obj: dict) -> None:
        p = self.phys
        changed = False
        if "ignition" in obj:
            p.ignition = bool(obj["ignition"])
            changed = True
        if "cruise" in obj:
            p.cruise = max(0.0, float(obj["cruise"]))
            changed = True
        if "fuel" in obj:
            p.fuel_bars = max(0, min(6, int(obj["fuel"])))
            changed = True
        if "odo" in obj:
            p.odo_km = max(0.0, float(obj["odo"]))
            changed = True
        if obj.get("resetTrip"):
            p.trip_km = p.trip_fuel_l = p.run_time_s = p.top_speed = 0.0
            changed = True
        if "music" in obj:
            await self.ble_notify(build_music(int(obj["music"])))
            log.info("emitted 0x6B music command %s", obj["music"])
        if changed:
            log.info("ctl: %s", {k: v for k, v in obj.items() if k != "t"})
            await self.send_state()

    async def ble_notify(self, data: bytes) -> None:
        """Send one frame to the phone over BLE, iff subscribed, and mirror
        it to the WS client so a controller can see what was sent."""
        if self.ble and self.link_up:
            await self.ble.notify_frame(data)
            self.stats["tx"] += 1
            await self.hub.send_binary(data)

    async def send_state(self) -> None:
        await self.hub.send_json({"t": "state", "link": self.link_up,
                                  **self.phys.state(), "stats": dict(self.stats)})

    async def run(self) -> None:
        import websockets
        self.ble = BleLayer(self.opts.name, self.opts.advertise_uuid,
                            self.on_write, self.on_link)
        try:
            await self.ble.start()
        except Exception as exc:
            log.error("BLE setup failed (%s). Is BlueZ running, and are you in "
                      "the bluetooth group / root?", exc)
            if not self.opts.ws_only:
                raise
            log.warning("continuing in --ws-only mode (no BLE)")

        async def ws_server(ws):
            await self.hub.set_client(ws)
            await self.hub.send_json({"t": "joined"})
            await self.send_state()
            try:
                async for msg in ws:
                    if isinstance(msg, bytes):
                        # manual frame injection: straight to the phone
                        await self.ble_notify(bytes(msg))
                        continue
                    try:
                        obj = json.loads(msg)
                    except ValueError:
                        continue
                    t = obj.get("t")
                    if t == "join":
                        await self.hub.send_json({"t": "joined"})
                    elif t == "ctl":
                        await self.ctl(obj)
                    else:
                        log.debug("ignored ws message: %s", obj)
            finally:
                await self.hub.drop(ws)

        if self.opts.ignition:
            self.phys.ignition = True
            log.info("ignition ON (--ignition)")

        async def telemetry_loop():
            interval = 0.1
            state_every = 5.0
            next_state = time.monotonic() + state_every
            while True:
                now = time.monotonic()
                frames = self.telemetry.poll(now)
                for frame in frames:
                    await self.ble_notify(frame)
                    if len(frames) > 1:
                        await asyncio.sleep(0.02)
                if now >= next_state:
                    next_state = now + state_every
                    await self.send_state()
                await asyncio.sleep(interval)

        server = await websockets.serve(ws_server, "127.0.0.1", self.opts.port)
        log.info("WS listening on ws://127.0.0.1:%s (controller: connect and "
                 "send {\"t\":\"ctl\",...})", self.opts.port)
        log.info("waiting for a phone to connect over BLE as '%s' ...", self.opts.name)
        try:
            await asyncio.gather(telemetry_loop(), server.serve_forever())
        finally:
            await self.ble.stop()


# ---------------------------------------------------------------------------
# self-test: layout checks against known-answer values, offline
# ---------------------------------------------------------------------------

def selftest() -> int:
    failures = []

    def check(label, got, want):
        if got != want:
            failures.append(f"{label}: got {got!r} want {want!r}")

    # 0x10 with the shape of the §4a capture frame (odo 16593.0, speed 0).
    f = build_sm1(0, 16593.0, 2, 0, 9, 0, 0, 0, True)
    check("0x10 start", f[0], 0x5A)
    check("0x10 id", f[1], 0x10)
    check("0x10 odo", get_u24_be(f, 3), 165930)
    check("0x10 fuel", f[6], 2)
    check("0x10 location/ign", f[12], 128)
    check("0x10 end", f[19], 0xFF)

    # speed + rpm encode round-trip
    f = build_sm1(42.6, 0, 6, 0, 42, 40, 12.3, 5230, True)
    check("0x10 speed", f[2], 43)
    check("0x10 rpm", get_u16_be(f, 17), 5230)
    check("0x10 tripF", get_u24_be(f, 14), 123)

    # keepalive decode: the exact layout of the rider log frame
    # 2026-11-13 18:26:35 (`5B 4A 43 00 00 02 06 1A 23 01 ...`), rebuilt by
    # hand — signal 4, battery 3, clock 6:26:35 PM, date 13/11/26.
    ka = bytearray(20)
    ka[0], ka[1] = 0x5B, 0x4A
    ka[2], ka[5], ka[6], ka[7], ka[8], ka[9] = 0x43, 2, 6, 26, 35, 1
    ka[12], ka[13], ka[14] = 13, 11, 26
    ka[19] = 0xFF
    d = decode_keepalive(bytes(ka))
    check("ka signal", d["signal"], 4)
    check("ka battery", d["battery"], 3)
    check("ka hour", d["hour12"], 6)
    check("ka minute", d["minute"], 26)
    check("ka second", d["second"], 35)
    check("ka pm", d["pm"], True)
    check("ka date", (d["day"], d["month"], d["year"]), (13, 11, 2026))
    check("ka findMy", d["findMy"], False)

    # nav control: the app builds big-endian (putUInt16/24)
    b = bytearray(20)
    b[0], b[1] = 0x5A, 0x4E
    b[19] = 0xFF
    put_u16_be(b, 2, 550)
    put_u16_be(b, 4, 411)
    put_u24_be(b, 6, 1250)
    b[9], b[10], b[11] = 7, 1, 1
    d = decode_nav_control(bytes(b))
    check("nav dist", d["distanceM"], 550)
    check("nav eta", d["etaMinutes"], 411)
    check("nav remaining", d["remainingTripM"], 1250)
    check("nav pictogram", d["pictogram"], 7)

    # nav text round-trip
    t = ascii_payload(new_frame(0x4F, start=START_PHONE), "toward Example Rd", 2, 18)
    check("navtext id", t[1], 0x4F)
    d = decode_outbound(bytes(t))
    check("navtext decode", d["text"], "toward Example Rd")

    # auth is detected and flagged, never decoded as telemetry
    auth = bytearray(20)
    auth[0], auth[1], auth[2] = 0x9B, 0xF2, 0x9B
    auth[19] = 0xFF
    check("auth kind", decode_outbound(bytes(auth))["kind"], "auth")

    # rider name
    r = ascii_payload(new_frame(0x52, start=START_PHONE), "Rider", 2, 18)
    check("rider", decode_outbound(bytes(r))["text"], "Rider")

    # music frame
    m = build_music(3)
    check("music id", m[1], 0x6B)
    check("music cmd", m[2], 3)

    # 0x18 battery encodes as raw x 0.1 V like the capture's 0x7C = 12.4 V
    check("0x18 battery x0.1", build_sm4(Physics(0, 3, 0))[11], 124)

    if failures:
        print("SELFTEST FAILED:")
        for f_ in failures:
            print("  -", f_)
        return 1
    print("selftest OK")
    return 0


# ---------------------------------------------------------------------------

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8766,
                    help="WebSocket port (default 8766; Jupiter bridge uses 8765)")
    ap.add_argument("--name", default="TVSBT800450",
                    help="advertised local name, matched by the app's TVSBT… scan (default TVSBT800450)")
    ap.add_argument("--advertise-uuid", action="store_true",
                    help="include the SmartXonnect service UUID in the advertisement "
                         "(UNFAITHFUL: the real cluster does not; use only if you want the "
                         "'Only SmartXonnect' scan filter to see the sim)")
    ap.add_argument("--ignition", action="store_true", help="start with ignition ON")
    ap.add_argument("--cruise", type=float, default=0.0, help="target speed kph (default 0)")
    ap.add_argument("--fuel", type=int, default=3, help="fuel gauge bars 0..6 (default 3)")
    ap.add_argument("--odo", type=float, default=0.0, help="starting odometer km (default 0)")
    ap.add_argument("--with-19", action="store_true",
                    help="also emit 0x19 (the real cluster never sent it)")
    ap.add_argument("--no-16", action="store_true",
                    help="do not emit 0x16 (real clusters send it constantly)")
    ap.add_argument("--address", default="02:1A:7F:00:42:01",
                    help="fake cluster address for the 0x42 frame")
    ap.add_argument("--ws-only", action="store_true",
                    help="skip BLE (controller/decoder testing only)")
    ap.add_argument("--selftest", action="store_true",
                    help="run offline layout checks and exit")
    ap.add_argument("-v", "--verbose", action="store_true")
    opts = ap.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if opts.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    if opts.selftest:
        sys.exit(selftest())
    try:
        asyncio.run(Bridge(opts).run())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
