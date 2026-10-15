#!/usr/bin/env python3
# Golden-vector generator for the :core:kart-protocol RCD port.
#
# Business logic: runs the REAL OpenKart reference implementation (openkartd/rcd.py) under fully
# deterministic, recorded randomness and emits everything the Kotlin tests need to reproduce each
# output byte-for-byte:
#   core/kart-protocol/src/commonTest/resources/rcd_vectors.json
#     - msg_encode:    RcdMsg.encode() samples (service/command/status/flags/data -> exact bytes)
#     - msg_size:      RcdMsg.size(buf) samples, including a short buffer (< header -> header size)
#     - msg_decode:    raw buffers -> decoded fields (flags with spare bits set -> is_response)
#     - pairing_keys:  RcdServerInfo.get_pairing_keys(master_key, device_ident, device_name)
#     - handshake:     a full transcript for a KNOWN device (cmd1 begin, cmd2 negotiate, cmd4 finalize)
#                      with the server's os.urandom challenge pinned, so the Kotlin handshake --
#                      including the running-SHA-256 finalize digest -- can be asserted byte-for-byte.
#     - pairing_handshake: the same, but pairing=True against an UNKNOWN device, so cmd2 routes through
#                      cmd3 get_secret_key before cmd4 finalize -- exercising the pairing branch.
#
# Ported from / generated against OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py
# (BSD-3-Clause). Copyright 2021 Sam Edwards.
#
# Usage (from the repository root):
#   python -I tools/gen_rcd_vectors.py
# The OpenKart checkout is located via the OPENKART_DIR environment variable, defaulting to
# C:\Workspace\openkart-master.

import asyncio
import binascii
import hashlib
import json
import os
import socket
import sys
import types
from pathlib import Path

# openkartd/lp2p.py does `from socket import AF_UNIX` for the hostapd control socket, and importing
# openkartd.rcd pulls openkartd in. CPython on Windows has no AF_UNIX, which would abort the import;
# the constant is never used by the functions exercised here, so a placeholder is enough.
if not hasattr(socket, "AF_UNIX"):
    socket.AF_UNIX = -1

OPENKART_DIR = Path(os.environ.get("OPENKART_DIR", r"C:\Workspace\openkart-master"))
sys.path.insert(0, str(OPENKART_DIR))

import openkartd.rcd as rcd  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
VECTORS_PATH = REPO_ROOT / "core/kart-protocol/src/commonTest/resources/rcd_vectors.json"

# Fixed server identity for the pairing-key and handshake vectors.
SERVER_NAME = "KartHub"
SERVER_IDENT = bytes(range(0x10, 0x20))                      # 0x10 bytes
MASTER_KEY = bytes((0x40 + i) & 0xFF for i in range(0x40))   # 0x40 bytes

# The server challenge begin_handshake draws from os.urandom(0x20); pinned so it is reproducible.
BEGIN_CHALLENGE = bytes((0xC0 + i) & 0xFF for i in range(0x20))


def hx(data: bytes) -> str:
    return binascii.hexlify(data).decode()


def msg_encode_vectors() -> list:
    cases = [
        ("request, empty data", 0x0001, 1, 0, False, b""),
        ("request, short data", 0x0001, 2, 0, False, bytes([0xDE, 0xAD, 0xBE, 0xEF])),
        ("response, status ok", 0x0001, 3, 0, True, bytes(range(16))),
        ("response, error status (BadRequest)", 0x1234, 7, 0x710e8, True, b""),
        ("response, large status (HandshakeUnrecognizedDevice)", 0xABCD, 4, 0x850e8, True, bytes([0xAA] * 5)),
    ]
    out = []
    for name, service, command, status, is_response, data in cases:
        msg = rcd.RcdMsg(service=service, command=command, status=status,
                         is_response=is_response, data=data)
        out.append({
            "name": name,
            "service": service,
            "command": command,
            "status": status,
            "is_response": is_response,
            "data": hx(data),
            "encoded": hx(msg.encode()),
        })
    return out


def msg_size_vectors() -> list:
    header = rcd.RcdMsg.HEADER.size
    # A full 20-byte buffer (16 header + 4 data), declaring data_size=4.
    full = rcd.RcdMsg(service=1, command=1, status=0, is_response=False,
                      data=bytes([1, 2, 3, 4])).encode()
    # A header-only buffer that still declares data_size=4: size() trusts the header, so 20.
    header_only = full[:header]
    cases = [
        ("shorter than header -> header size", bytes([0x00, 0x01, 0x00, 0x02])),
        ("header only, declares 4 data bytes -> 20", header_only),
        ("complete 20-byte message -> 20", full),
    ]
    return [{"name": n, "buffer": hx(b), "size": rcd.RcdMsg.size(b)} for n, b in cases]


def msg_decode_vectors() -> list:
    # A response buffer whose flags byte has spare bits set (0x03): is_response is flags & 1.
    spare = rcd.RcdMsg.HEADER.pack(0x0001, 5, 2, 0x708e8, 0x03) + bytes([0x11, 0x22])
    out = []
    for name, buf in [("flags 0x03 -> is_response true, spare bits ignored", spare)]:
        msg = rcd.RcdMsg.decode(buf)
        out.append({
            "name": name,
            "buffer": hx(buf),
            "service": msg.service,
            "command": msg.command,
            "status": msg.status,
            "is_response": msg.is_response,
            "data": hx(msg.data),
        })
    return out


def pairing_keys_vectors() -> list:
    info = rcd.RcdServerInfo(name=SERVER_NAME, ident=SERVER_IDENT, master_key=MASTER_KEY)
    cases = [
        ("named device", bytes(range(0x20, 0x30)), "Wheel-1"),
        ("empty name", bytes([0x55] * 0x10), ""),
        ("other device", bytes([0xEE] * 0x10), "Pad_A"),
    ]
    out = []
    for name, device_ident, device_name in cases:
        pairing_id, secret_key = info.get_pairing_keys(device_ident, device_name)
        out.append({
            "name": name,
            "master_key": hx(MASTER_KEY),
            "device_ident": hx(device_ident),
            "device_name": device_name,
            "pairing_id": hx(pairing_id),
            "secret_key": hx(secret_key),
        })
    return out


def build_begin_request(device_name: str, device_ident: bytes) -> bytes:
    buf = bytearray(0x50)
    buf[0] = 1  # handshake_version
    name_bytes = device_name.encode("utf8")
    assert len(name_bytes) < 0x10
    buf[0x10:0x10 + len(name_bytes)] = name_bytes
    buf[0x20:0x30] = device_ident
    # bytes[0x01:0x10] and [0x30:0x50] are ignored by begin_handshake but DO feed the running hash;
    # left as the deterministic zeros above so the Kotlin test can replay them exactly.
    return bytes(buf)


async def handshake_transcript() -> dict:
    info = rcd.RcdServerInfo(name=SERVER_NAME, ident=SERVER_IDENT, master_key=MASTER_KEY,
                             versions=(2, 1))

    # Pin the server challenge so begin_handshake is reproducible.
    draws = []

    def fake_urandom(n: int) -> bytes:
        assert n == 0x20, f"unexpected os.urandom({n})"
        draws.append(n)
        return BEGIN_CHALLENGE

    rcd.os = types.SimpleNamespace(urandom=fake_urandom)

    svc = rcd.RcdHandshakeService(info, handler=None, pairing=False)
    channel = types.SimpleNamespace(address="10.0.0.2")

    device_name = "Wheel-1"
    device_ident = bytes(range(0x20, 0x30))
    pairing_id, secret_key = info.get_pairing_keys(device_ident, device_name)

    steps = []

    async def run(command: int, data: bytes) -> bytes:
        msg = rcd.RcdMsg(service=rcd.RcdHandshakeService.SERVICE_ID, command=command,
                         status=0, is_response=False, data=data)
        return await svc.dispatch(channel, msg)

    # cmd 1: begin_handshake
    req1 = build_begin_request(device_name, device_ident)
    resp1 = await run(1, req1)
    steps.append({"command": 1, "request": hx(req1), "response": hx(resp1)})

    # cmd 2: negotiate_version -- offer versions [2, 1]; the server picks 2 (first of its (2, 1)).
    req2 = pairing_id + bytes([2]) + bytes([2, 1])
    resp2 = await run(2, req2)
    steps.append({"command": 2, "request": hx(req2), "response": hx(resp2)})
    selected_version = resp2[0x20]

    # cmd 4: finalize -- the client must echo the server's running-hash digest of everything so far
    # (the 64-byte-aligned flushed portion). Read it straight from the reference service.
    finalize_input = svc.sha256.copy().digest()
    resp4 = await run(4, finalize_input)
    steps.append({
        "command": 4,
        "request": hx(finalize_input),
        "response": hx(resp4),
        "finalize_check_input": hx(finalize_input),
        "finalize_digest": hx(resp4),
    })

    return {
        "server_info": {
            "name": SERVER_NAME,
            "ident": hx(SERVER_IDENT),
            "master_key": hx(MASTER_KEY),
            "versions": [2, 1],
        },
        "device": {"name": device_name, "ident": hx(device_ident)},
        "pairing_id": hx(pairing_id),
        "secret_key": hx(secret_key),
        "selected_version": selected_version,
        "begin_challenge": hx(BEGIN_CHALLENGE),
        "steps": steps,
    }


async def pairing_handshake_transcript() -> dict:
    # Drives the SAME reference RcdHandshakeService but with pairing=True against an UNKNOWN device, so
    # negotiate_version routes to next_cmd=3 (get_secret_key) before finalize. This exercises the whole
    # cmd 1 -> 2 -> 3 -> 4 pairing branch, which the non-pairing transcript (cmd 1 -> 2 -> 4) skips.
    info = rcd.RcdServerInfo(name=SERVER_NAME, ident=SERVER_IDENT, master_key=MASTER_KEY,
                             versions=(2, 1))

    # Pin the server challenge so begin_handshake is reproducible.
    def fake_urandom(n: int) -> bytes:
        assert n == 0x20, f"unexpected os.urandom({n})"
        return BEGIN_CHALLENGE

    rcd.os = types.SimpleNamespace(urandom=fake_urandom)

    svc = rcd.RcdHandshakeService(info, handler=None, pairing=True)
    channel = types.SimpleNamespace(address="10.0.0.3")

    # A first-time (unknown) device: it does not yet know its pairing id, so it offers an all-zero one,
    # which does NOT match the server's derived pairing id -> with pairing on, next_cmd becomes 3.
    device_name = "Pad_A"
    device_ident = bytes([0xEE] * 0x10)
    pairing_id, secret_key = info.get_pairing_keys(device_ident, device_name)
    unknown_pairing_id = bytes(0x20)

    steps = []

    async def run(command: int, data: bytes) -> bytes:
        msg = rcd.RcdMsg(service=rcd.RcdHandshakeService.SERVICE_ID, command=command,
                         status=0, is_response=False, data=data)
        return await svc.dispatch(channel, msg)

    # cmd 1: begin_handshake
    req1 = build_begin_request(device_name, device_ident)
    resp1 = await run(1, req1)
    steps.append({"command": 1, "request": hx(req1), "response": hx(resp1)})

    # cmd 2: negotiate_version -- offer the unknown (all-zero) pairing id so pairing routes to cmd 3.
    req2 = unknown_pairing_id + bytes([2]) + bytes([2, 1])
    resp2 = await run(2, req2)
    steps.append({"command": 2, "request": hx(req2), "response": hx(resp2)})
    selected_version = resp2[0x20]

    # cmd 3: get_secret_key -- payload is a 0x20 pairing id (content unused); response is the secret key.
    req3 = unknown_pairing_id
    resp3 = await run(3, req3)
    steps.append({"command": 3, "request": hx(req3), "response": hx(resp3)})
    assert resp3 == secret_key, "get_secret_key must return the device secret key"

    # cmd 4: finalize -- echo the server's running-hash digest of everything so far (cmd 3 included).
    finalize_input = svc.sha256.copy().digest()
    resp4 = await run(4, finalize_input)
    steps.append({
        "command": 4,
        "request": hx(finalize_input),
        "response": hx(resp4),
        "finalize_check_input": hx(finalize_input),
        "finalize_digest": hx(resp4),
    })

    return {
        "server_info": {
            "name": SERVER_NAME,
            "ident": hx(SERVER_IDENT),
            "master_key": hx(MASTER_KEY),
            "versions": [2, 1],
        },
        "device": {"name": device_name, "ident": hx(device_ident)},
        "offered_pairing_id": hx(unknown_pairing_id),
        "pairing_id": hx(pairing_id),
        "secret_key": hx(secret_key),
        "selected_version": selected_version,
        "begin_challenge": hx(BEGIN_CHALLENGE),
        "steps": steps,
    }


def main():
    vectors = {
        "source": "OpenKart openkartd/rcd.py (BSD-3-Clause, Copyright 2021 Sam Edwards)",
        "generator": "tools/gen_rcd_vectors.py",
        "constants": {
            "handshake_service_id": rcd.RcdHandshakeService.SERVICE_ID,
            "header_size": rcd.RcdMsg.HEADER.size,
            "max_data_size": 0x1000,
            "error_codes": {
                "missized_payload": f"{rcd.RcdErrorMissizedPayload.CODE:x}",
                "bad_request": f"{rcd.RcdErrorBadRequest.CODE:x}",
                "handshake_version_mismatch": f"{rcd.RcdErrorHandshakeVersionMismatch.CODE:x}",
                "handshake_sequence": f"{rcd.RcdErrorHandshakeSequence.CODE:x}",
                "handshake_bad_versions": f"{rcd.RcdErrorHandshakeBadVersions.CODE:x}",
                "handshake_hash": f"{rcd.RcdErrorHandshakeHash.CODE:x}",
                "handshake_unrecognized_device": f"{rcd.RcdErrorHandshakeUnrecognizedDevice.CODE:x}",
            },
        },
        "msg_encode": msg_encode_vectors(),
        "msg_size": msg_size_vectors(),
        "msg_decode": msg_decode_vectors(),
        "pairing_keys": pairing_keys_vectors(),
        "handshake": asyncio.run(handshake_transcript()),
        "pairing_handshake": asyncio.run(pairing_handshake_transcript()),
    }

    VECTORS_PATH.parent.mkdir(parents=True, exist_ok=True)
    VECTORS_PATH.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {VECTORS_PATH}")
    print("json keys: " + ", ".join(vectors.keys()))
    print("handshake steps: " + ", ".join(str(s["command"]) for s in vectors["handshake"]["steps"]))
    print("pairing handshake steps: " +
          ", ".join(str(s["command"]) for s in vectors["pairing_handshake"]["steps"]))


if __name__ == "__main__":
    main()
