#!/usr/bin/env python3
"""Decode a btsnoop_hci.log into readable ATT exchanges.

A capture is the only thing that can answer "what does the cluster actually
reply", which decompiling the official app cannot. But a raw btsnoop file is
16-byte-framed binary with a macOS epoch and no field names, which makes it
useless without tooling. This decodes it to one line per ATT packet:

    23:41:02.118  OUT  handle=0x002a  WriteReq  5A 4E 01 00 ...
    23:41:02.131  IN   handle=0x002a  Notify    5A 4E 00 ...

Usage:
    tools/decode-snoop.py capture.btsnoop_hci.log            # summary + samples
    tools/decode-snoop.py capture.btsnoop_hci.log --frames   # only our app frames
    tools/decode-snoop.py capture.btsnoop_hci.log -n 500     # more rows

Why the app frames matter: the protocol here is identified by its start bytes.
Packets beginning 0x5A or 0x5B are navigation control and text; 0x4A is the
presence ping. Filtering on those turns a multi-megabyte capture into the
handful of packets that actually encode the protocol.
"""

import argparse
import struct
import sys
from datetime import datetime, timedelta, timezone

BTSNOOP_MAGIC = b"btsnoop\x00"

# btsnoop timestamps count microseconds from 0000-01-01; the delta to the Unix
# epoch is the macOS offset below.
MAC_EPOCH_DELTA = 0x00DCDDB30F2F8000

# HCI packet types
HCI_CMD, HCI_ACL, HCI_SCO, HCI_EVT = 1, 2, 3, 4

# ATT opcodes we care about. The rest are named generically.
ATT_OPS = {
    0x01: "ErrorRsp", 0x02: "ExchangeMtuReq", 0x03: "ExchangeMtuRsp",
    0x04: "FindInfoReq", 0x05: "FindInfoRsp", 0x06: "FindTypeReq",
    0x07: "FindTypeRsp", 0x08: "ReadByTypeReq", 0x09: "ReadByTypeRsp",
    0x0A: "ReadReq", 0x0B: "ReadRsp", 0x0C: "ReadBlobReq", 0x0D: "ReadBlobRsp",
    0x0E: "ReadMultipleReq", 0x0F: "ReadMultipleRsp",
    0x10: "ReadByGroupReq", 0x11: "ReadByGroupRsp",
    0x12: "WriteReq", 0x13: "WriteRsp", 0x16: "PrepareWriteReq",
    0x17: "PrepareWriteRsp", 0x18: "ExecuteWriteReq", 0x19: "ExecuteWriteRsp",
    0x1B: "HandleValueNotification", 0x1D: "HandleValueIndication",
    0x1E: "HandleValueConfirmation",
    0x52: "WriteCommand", 0xD2: "SignedWriteCommand",
}

L2CAP_ATT_CID = 0x0004


def parse(path):
    """Yield (timestamp, is_inbound, handle, att_payload) for every ATT packet."""
    with open(path, "rb") as fh:
        blob = fh.read()

    if not blob.startswith(BTSNOOP_MAGIC):
        raise SystemExit("not a btsnoop file (bad magic)")

    # version(4) datalink(4)
    _version, datalink = struct.unpack_from(">II", blob, 8)
    if datalink not in (1001, 1002):
        print(f"warning: datalink {datalink} is not HCI; parsing anyway",
              file=sys.stderr)

    offset = 16
    total = len(blob)
    while offset + 24 <= total:
        orig_len, incl_len, _flags, _drops, ts = struct.unpack_from(
            ">IIIIq", blob, offset
        )
        offset += 24
        if incl_len == 0 or offset + incl_len > total:
            break
        pkt = blob[offset:offset + incl_len]
        offset += incl_len
        _ = orig_len

        if not pkt:
            continue
        ptype = pkt[0]
        if ptype != HCI_ACL or len(pkt) < 5:
            continue  # commands, events and SCO carry no ATT

        flags = struct.unpack_from(">I", blob, offset - incl_len - 24 + 8)[0]
        is_inbound = bool(flags & 0x01)

        handle_pb_bc = struct.unpack_from("<H", pkt, 1)[0]
        handle = handle_pb_bc & 0x0FFF
        acl_len = struct.unpack_from("<H", pkt, 3)[0]
        l2cap = pkt[5:5 + acl_len]
        if len(l2cap) < 4:
            continue

        l2_len, cid = struct.unpack_from("<HH", l2cap, 0)
        if cid != L2CAP_ATT_CID:
            continue
        yield (ts, is_inbound, handle, l2cap[4:4 + l2_len])


def ts_str(raw_ts):
    secs = raw_ts / 1_000_000 - MAC_EPOCH_DELTA / 1_000_000
    dt = datetime.fromtimestamp(secs, tz=timezone.utc).astimezone()
    return dt.strftime("%H:%M:%S.") + f"{int((secs % 1) * 1000):03d}"


def hexs(data, limit=40):
    out = " ".join(f"{b:02X}" for b in data[:limit])
    if len(data) > limit:
        out += f" …+{len(data) - limit}"
    return out


def protocol_frame(payload):
    """Extract a SmartXonnect frame if the payload carries one.

    Start bytes: 0x5A navigation control, 0x5B text/pictogram, 0x4A presence
    ping. Returns a short tag or None.
    """
    for b in payload:
        if b in (0x5A, 0x5B, 0x4A):
            return f"{b:02X}"
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("capture")
    ap.add_argument("-n", "--limit", type=int, default=200,
                    help="max rows to print (default 200)")
    ap.add_argument("--frames", action="store_true",
                    help="only rows that look like SmartXonnect frames")
    args = ap.parse_args()

    total = shown = 0
    inbound = outbound = 0
    opcounts = {}
    frametags = {}
    handles = {}

    for ts, is_in, handle, payload in parse(args.capture):
        total += 1
        if is_in:
            inbound += 1
        else:
            outbound += 1
        handles[handle] = handles.get(handle, 0) + 1

        if not payload:
            continue
        op = payload[0]
        name = ATT_OPS.get(op, f"Op{op:02X}")
        opcounts[name] = opcounts.get(name, 0) + 1
        tag = protocol_frame(payload)
        if tag:
            frametags[tag] = frametags.get(tag, 0) + 1

        if args.frames and not tag:
            continue
        if shown < args.limit:
            shown += 1
            print(f"{ts_str(ts)}  {'IN ' if is_in else 'OUT'}  "
                  f"handle=0x{handle:04x}  {name:<26} {hexs(payload)}"
                  + (f"   <{tag}>" if tag else ""))

    print("\n--- summary ---", file=sys.stderr)
    print(f"ATT packets: {total}  (in {inbound} / out {outbound})",
          file=sys.stderr)
    if handles:
        top = sorted(handles.items(), key=lambda kv: -kv[1])[:8]
        print("handles: " + ", ".join(
            f"0x{h:04x}={c}" for h, c in top), file=sys.stderr)
    if opcounts:
        print("ATT ops: " + ", ".join(
            f"{k}={v}" for k, v in sorted(opcounts.items(),
                                          key=lambda kv: -kv[1])[:10]),
            file=sys.stderr)
    if frametags:
        print("SmartXonnect frames by start byte: " + ", ".join(
            f"0x{k}={v}" for k, v in sorted(frametags.items())), file=sys.stderr)
    else:
        print("no SmartXonnect frames (0x5A/0x5B/0x4A) seen", file=sys.stderr)
    print(f"printed {shown} rows", file=sys.stderr)


if __name__ == "__main__":
    main()
