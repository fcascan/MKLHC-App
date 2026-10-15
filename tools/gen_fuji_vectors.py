#!/usr/bin/env python3
# Golden-vector generator for the :core:kart-protocol Fuji control / pairing client port.
#
# Business logic: runs the REAL OpenKart reference implementation (openkartd/fuji.py) with the RCD
# transport replaced by a recorder, and emits everything the Kotlin tests need to reproduce each
# request and each decoded response byte-for-byte:
#   core/kart-protocol/src/commonTest/resources/fuji_vectors.json
#     - padding:          util.pad_to samples
#     - system_info:      FujiSystemInfo.decode inputs -> (boot_version, system_version, sha1)
#     - product_code:     FujiProductCode.decode inputs -> (unk1, character, unk2, serial)
#     - get_system_info:  the (service, command, data) FujiControlClient.get_system_info invokes
#     - set_param:        the request set_param builds (0x80-padded name + 0x10-padded BE length + value)
#     - connection_info:  the request set_connection_info builds ('<HHHHQ' value inside set_param)
#     - get_param:        the request get_param builds, plus wire responses -> returned slice
#     - set_state:        the request set_state builds
#     - shutdown:         the (service, command, data) shutdown invokes
#     - set_group_info:   the request FujiPairingClient.set_group_info builds
#     - read_application_data: the ReadApplicationData (command 0x12) request, replicated from
#                         mk-live's layout (this command is NOT in OpenKart's fuji.py, see below).
#
# Provenance:
#   * Everything except read_application_data is generated against
#     OpenKart https://github.com/OpenKart-SDK, openkartd/fuji.py (BSD-3-Clause).
#     Copyright 2021 Sam Edwards.
#   * read_application_data is adapted from mk-live https://github.com/olus85/mk-live,
#     patches/openkartd/fuji.py (MIT). That patch is not present in the OpenKart checkout, so its
#     documented wire layout is replicated here in read_application_data_request(): the zero-padded
#     (0x10) ASCII application id followed by the 4-byte big-endian maximum size, the whole request
#     zero-padded to 0x20 octets, sent as command 0x12 of the Fuji control service.
#
# Usage (from the repository root):
#   python -I tools/gen_fuji_vectors.py
# The OpenKart checkout is located via the OPENKART_DIR environment variable, defaulting to
# C:\Workspace\openkart-master.

import asyncio
import binascii
import json
import os
import socket
import sys
from pathlib import Path

# openkartd/fuji.py imports openkartd.lp2p, which does `from socket import AF_UNIX` for the hostapd
# control socket. CPython on Windows has no AF_UNIX, which would abort the import. The constant is
# never used by the functions exercised here, so a placeholder is enough.
if not hasattr(socket, "AF_UNIX"):
    socket.AF_UNIX = -1

OPENKART_DIR = Path(os.environ.get("OPENKART_DIR", r"C:\Workspace\openkart-master"))
sys.path.insert(0, str(OPENKART_DIR))

import openkartd.fuji as fuji  # noqa: E402
from openkartd.lp2p import GroupInfo  # noqa: E402
from openkartd.util import pad_to  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
VECTORS_PATH = REPO_ROOT / "core/kart-protocol/src/commonTest/resources/fuji_vectors.json"

# The companion-application id ReadApplicationData is queried with (mk-live), 0x10 octets long.
APPLICATION_ID = "YVCOQ00000000XFB"


def hx(data: bytes) -> str:
    return binascii.hexlify(data).decode()


class RecordingClient:
    """Builds a real reference client without a socket and records what it would invoke.

    The reference clients are asyncio protocols; their request building lives in the async methods
    and ends in `self.invoke(service, command, data)`. Bypassing __init__ and replacing `invoke`
    on the instance exercises exactly the production request-building code with no network.
    """

    def __init__(self, cls, response: bytes = b""):
        self.client = object.__new__(cls)
        self.calls = []
        self.response = response

        async def invoke(service, command, data=b"", *, timeout=1.0):
            self.calls.append((service, command, data))
            return self.response

        self.client.invoke = invoke

    def run(self, coro):
        return asyncio.run(coro)

    def single_call(self):
        assert len(self.calls) == 1, f"expected exactly one invoke, got {len(self.calls)}"
        return self.calls[0]


def call_json(service: int, command: int, data: bytes) -> dict:
    return {"service": service, "command": command, "request": hx(data)}


def padding_vectors() -> list:
    cases = [
        ("pad short to 0x10", b"\x00\x05", 0x10),
        ("already full length", bytes(range(0x10)), 0x10),
        ("empty to 0x20", b"", 0x20),
        ("one byte to 0x10", b"\x01", 0x10),
    ]
    return [
        {"name": n, "input": hx(x), "length": ln, "padded": hx(pad_to(x, ln))}
        for n, x, ln in cases
    ]


def system_info_vectors() -> list:
    sha = b"0123456789abcdef0123456789abcdef01234567"
    cases = [
        ("typical, NUL terminated", bytes([1, 2, 3, 4]) + sha + b"\x00" + b"\xff\xff"),
        ("no terminator", bytes([0, 255, 128, 7]) + b"deadbeef"),
        ("empty sha", bytes([9, 8, 7, 6])),
        ("immediately NUL", bytes([9, 8, 7, 6, 0, 0x41])),
        ("latin1 high bytes", bytes([1, 1, 2, 2]) + b"ab\xe9\xff\x00zz"),
    ]
    out = []
    for name, data in cases:
        info = fuji.FujiSystemInfo.decode(data)
        out.append({
            "name": name,
            "input": hx(data),
            "boot_version": list(info.boot_version),
            "system_version": list(info.system_version),
            # The text form is emitted only when it is plain ASCII: the test JSON reader has no
            # unicode-escape support, so non-ASCII (latin1) results are asserted via the code points.
            "sha1": info.sha1 if info.sha1.isascii() else None,
            "sha1_codepoints": [ord(c) for c in info.sha1],
        })
    return out


def product_code_vectors() -> list:
    # struct '<HHB' header: unk1 (u16 LE), character (u16 LE), unk2 (u8), then the serial.
    cases = [
        ("character 1, serial", b"\x03\x00" + b"\x01\x00" + b"\x07" + b"SER1234567\x00" + b"\xaa\xaa"),
        ("character 2, serial", b"\x00\x00" + b"\x02\x00" + b"\x00" + b"ABCDEF\x00"),
        ("big-endian trap: 0x0102 / 0x0304", b"\x02\x01" + b"\x04\x03" + b"\xff" + b"XYZ"),
        ("empty serial", b"\x01\x00\x01\x00\x01"),
        ("immediately NUL serial", b"\x01\x00\x05\x00\x01\x00junk"),
    ]
    out = []
    for name, data in cases:
        code = fuji.FujiProductCode.decode(data)
        out.append({
            "name": name,
            "input": hx(data),
            "unk1": code.unk1,
            "character": code.character,
            "unk2": code.unk2,
            "serial": code.serial,
        })
    return out


def get_system_info_vector() -> dict:
    rec = RecordingClient(fuji.FujiControlClient, response=bytes([1, 2, 3, 4]) + b"abc\x00")
    info = rec.run(rec.client.get_system_info())
    service, command, data = rec.single_call()
    out = call_json(service, command, data)
    out["response"] = hx(rec.response)
    out["boot_version"] = list(info.boot_version)
    out["system_version"] = list(info.system_version)
    out["sha1"] = info.sha1
    return out


def set_param_vectors() -> list:
    cases = [
        ("short value", "test_param", bytes([1, 2, 3, 4, 5])),
        ("empty value", "empty", b""),
        ("two-byte length (0x0123 octets)", "big_param", bytes((i * 7) & 0xFF for i in range(0x123))),
        ("longest name (0x7f octets)", "n" * 0x7F, b"\xaa"),
    ]
    out = []
    for name, param, value in cases:
        rec = RecordingClient(fuji.FujiControlClient)
        rec.run(rec.client.set_param(param, value))
        service, command, data = rec.single_call()
        entry = call_json(service, command, data)
        entry.update({"name": name, "param": param, "value": hx(value)})
        out.append(entry)
    return out


def connection_info_vectors() -> list:
    cases = [
        ("announced ports", 5116, 5032, 5016, 1, 1760000000),
        ("unk 0, 64-bit timestamp, high port bytes", 0x1234, 0xABCD, 0x00FF, 0, 0x1122334455667788),
        ("all-zero timestamp", 1, 2, 3, 1, 0),
    ]
    out = []
    for name, telemetry, control, stream, unk, timestamp in cases:
        rec = RecordingClient(fuji.FujiControlClient)
        rec.run(rec.client.set_connection_info(
            telemetry_port=telemetry, video_control_port=control,
            video_stream_port=stream, unk=unk, timestamp=timestamp))
        service, command, data = rec.single_call()
        entry = call_json(service, command, data)
        entry.update({
            "name": name,
            "telemetry_port": telemetry,
            "video_control_port": control,
            "video_stream_port": stream,
            "unk": unk,
            # A 64-bit value does not survive a JSON double, so it travels as a decimal string.
            "timestamp": str(timestamp),
        })
        out.append(entry)
    return out


def get_param_vectors() -> list:
    def wire(payload: bytes, declared: int = None) -> bytes:
        size = len(payload) if declared is None else declared
        return pad_to(size.to_bytes(2, "big"), 0x10) + payload

    cases = [
        ("product_code, exact size", "product_code", wire(b"\x01\x00\x01\x00\x01SER9\x00")),
        ("declared size shorter than the data", "product_code", wire(b"abcdefgh", declared=3)),
        ("two-byte size (0x0102)", "big", wire(bytes(range(256)) + b"\x07", declared=0x0102)),
        ("declared size longer than the data", "short", wire(b"abcd", declared=0x20)),
        ("empty value", "empty", wire(b"")),
    ]
    out = []
    for name, param, response in cases:
        rec = RecordingClient(fuji.FujiControlClient, response=response)
        result = rec.run(rec.client.get_param(param))
        service, command, data = rec.single_call()
        entry = call_json(service, command, data)
        entry.update({"name": name, "param": param, "response": hx(response), "result": hx(result)})
        out.append(entry)
    return out


def product_code_flow_vector() -> dict:
    payload = b"\x03\x00\x01\x00\x07SER1234567\x00"
    response = pad_to(len(payload).to_bytes(2, "big"), 0x10) + payload
    rec = RecordingClient(fuji.FujiControlClient, response=response)
    code = rec.run(rec.client.get_product_code())
    service, command, data = rec.single_call()
    out = call_json(service, command, data)
    out.update({
        "response": hx(response),
        "unk1": code.unk1,
        "character": code.character,
        "unk2": code.unk2,
        "serial": code.serial,
    })
    return out


def set_state_vectors() -> list:
    out = []
    for state in (1, 0, 255):
        rec = RecordingClient(fuji.FujiControlClient)
        rec.run(rec.client.set_state(state))
        service, command, data = rec.single_call()
        entry = call_json(service, command, data)
        entry["state"] = state
        out.append(entry)
    return out


def shutdown_vector() -> dict:
    rec = RecordingClient(fuji.FujiControlClient)
    rec.run(rec.client.shutdown())
    service, command, data = rec.single_call()
    return call_json(service, command, data)


def read_application_data_request(application_id: str, max_size: int) -> bytes:
    """mk-live's ReadApplicationData request (command 0x12): see the provenance note up top."""
    app_id = application_id.encode("ascii")
    assert len(app_id) <= 0x10
    request = pad_to(app_id, 0x10) + max_size.to_bytes(4, "big")
    return pad_to(request, 0x20)


def read_application_data_vectors() -> list:
    cases = [
        ("companion application id, default size", APPLICATION_ID, 0x800),
        ("companion application id, 0x1000", APPLICATION_ID, 0x1000),
        ("short id is zero padded", "ABC", 0x0102),
    ]
    out = []
    for name, app_id, max_size in cases:
        request = read_application_data_request(app_id, max_size)
        out.append({
            "name": name,
            "service": fuji.FujiControlClient.SERVICE_ID,
            "command": 0x12,
            "application_id": app_id,
            "max_size": max_size,
            "request": hx(request),
        })
    return out


def set_group_info_vectors() -> list:
    cases = [
        ("typical", "KartNet-01", bytes((0x10 + i) & 0xFF for i in range(0x20))),
        ("ssid of 31 octets (longest accepted)", "s" * 0x1F, bytes([0xA5] * 0x20)),
        ("empty ssid", "", bytes(range(0x20))),
    ]
    out = []
    for name, ssid, psk in cases:
        rec = RecordingClient(fuji.FujiPairingClient)
        rec.run(rec.client.set_group_info(GroupInfo(ssid=ssid, psk=psk, channel=6)))
        service, command, data = rec.single_call()
        entry = call_json(service, command, data)
        entry.update({"name": name, "ssid": ssid, "psk": hx(psk)})
        out.append(entry)
    return out


def main():
    vectors = {
        "source": "OpenKart openkartd/fuji.py (BSD-3-Clause, Copyright 2021 Sam Edwards); "
                  "read_application_data adapted from mk-live patches/openkartd/fuji.py (MIT)",
        "generator": "tools/gen_fuji_vectors.py",
        "constants": {
            "control_service_id": fuji.FujiControlClient.SERVICE_ID,
            "pairing_service_id": fuji.FujiPairingClient.SERVICE_ID,
            "drive_port": fuji.DRIVE_PORT,
            "control_port": fuji.CONTROL_PORT,
            "pairing_port": fuji.PAIRING_PORT,
            "event_port": fuji.EVENT_PORT,
            "application_id": APPLICATION_ID,
        },
        "padding": padding_vectors(),
        "system_info": system_info_vectors(),
        "product_code": product_code_vectors(),
        "get_system_info": get_system_info_vector(),
        "set_param": set_param_vectors(),
        "connection_info": connection_info_vectors(),
        "get_param": get_param_vectors(),
        "product_code_flow": product_code_flow_vector(),
        "set_state": set_state_vectors(),
        "shutdown": shutdown_vector(),
        "read_application_data": read_application_data_vectors(),
        "set_group_info": set_group_info_vectors(),
    }

    VECTORS_PATH.parent.mkdir(parents=True, exist_ok=True)
    VECTORS_PATH.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {VECTORS_PATH}")
    print("json keys: " + ", ".join(vectors.keys()))


if __name__ == "__main__":
    main()
