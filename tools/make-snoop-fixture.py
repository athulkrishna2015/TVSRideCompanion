#!/usr/bin/env python3
"""Build a synthetic btsnoop_hci.log for testing tools/decode-snoop.py.

The real captures live in captures/ and are gitignored, so the decoder would
otherwise have no test at all. This generates a file with known contents and a
known answer, so a regression in the parser shows up as a diff rather than as a
mystery later during an actual capture.

Exercises the parts that are easy to get wrong:

  * direction, which comes from the btsnoop flags word rather than the packet
  * the HCI ACL header, which must be stripped before the L2CAP length is read
  * the L2CAP CID filter, so non-ATT channels are dropped
  * non-ACL packets (events) being skipped
  * multi-byte little-endian fields in an ATT payload
  * the macOS epoch offset in the timestamp

Run:
    tools/make-snoop-fixture.py /tmp/synthetic.btsnoop_hci.log
    tools/decode-snoop.py /tmp/synthetic.btsnoop_hci.log
"""

import struct
import sys

MAC_EPOCH_DELTA = 0x00DCDDB30F2F8000
DATALINK_HCI = 1002


def record(flags, data, epoch_seconds):
    ts = int((epoch_seconds + MAC_EPOCH_DELTA / 1_000_000) * 1_000_000)
    return struct.pack(">IIIIq", len(data), len(data), flags, 0, ts) + data


def acl(handle, att, pb=2):
    """Wrap ATT in L2CAP + an HCI ACL packet."""
    l2cap = struct.pack("<HH", len(att), 0x0004) + att
    return (
        bytes([0x02])
        + struct.pack("<H", (handle & 0x0FFF) | (pb << 12))
        + struct.pack("<H", len(l2cap))
        + l2cap
    )


def l2cap(cid, payload):
    return struct.pack("<HH", len(payload), cid) + payload


def build():
    out = b"btsnoop\x00" + struct.pack(">II", 1, DATALINK_HCI)
    t = 1_758_000_000

    # OUT: Write Command carrying a navigation control frame (0x5A 0x4E).
    out += record(
        0x00,  # flags bit0 clear = host -> controller
        acl(0x2A, bytes([0x52]) + bytes.fromhex("5A4E0100" + "00" * 14)),
        t,
    )

    # IN: Notification whose value is also a 0x5A frame. Handle is little-endian.
    out += record(
        0x01,  # flags bit0 set = controller -> host
        acl(0x2A, bytes([0x1B]) + struct.pack("<H", 0x0035)
            + bytes.fromhex("5A4E00" + "FF" * 10)),
        t + 1,
    )

    # A non-ATT L2CAP channel: must be filtered out by the CID check.
    out += record(0x01, l2cap(0x0005, bytes.fromhex("00112233")), t + 1)

    # An ordinary ATT write with no SmartXonnect start byte.
    out += record(0x00, acl(0x2A, bytes([0x52, 0x01, 0x02, 0x03])), t + 2)

    # An HCI event, not ACL: must be skipped entirely.
    out += record(0x01, bytes([0x04, 0x0E, 0x04, 0x01, 0x00, 0x00, 0x00]), t + 2)

    return out


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/synthetic.btsnoop_hci.log"
    data = build()
    with open(path, "wb") as fh:
        fh.write(data)
    print(f"wrote {len(data)} bytes to {path}")
    print("expect: 3 ATT packets (1 in, 2 out), 2 rows tagged <5A>")
