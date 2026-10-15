#!/usr/bin/env python3
# Derived from OpenKart openkartd/config.py: Copyright 2021, Sam Edwards <CFSworks@gmail.com>
# SPDX-License-Identifier: BSD-3-Clause
#
# Golden-vector generator for the :core:data identity derivation.
#
# Business logic: the app derives the whole network and RCD identity of a kart installation from ONE
# master secret, exactly as the reference does it. This script runs the REAL OpenKart reference
# (openkartd/config.py, Config.process and its derive() helper) on fixed secrets and emits, for each
# secret, the byte-exact outputs the Kotlin derivation must reproduce:
#   core/data/src/commonTest/resources/identity_vectors.json
#     - wireless/ssid  derive('wireless/ssid', 0xC)  -> base64 text; the reference prepends its own
#                      brand prefix, which this app replaces, so only the base64 TAIL is recorded
#     - wireless/psk   derive('wireless/psk', 0x20)  -> 32 octets
#     - rcd/ident      derive('rcd/ident', 0x10)     -> 16 octets
#     - rcd/master_key derive('rcd/master_key', 0x40) -> 64 octets
#
# Ported from / generated against OpenKart https://github.com/OpenKart-SDK, openkartd/config.py
# (BSD-3-Clause). Copyright 2021 Sam Edwards.
#
# The reference accepts the secret as a config-file STRING (secret.encode() is the HMAC key). To also
# cover a raw 32-octet master secret (what the app generates), this script replicates the reference's
# derive() line for line and, for every string secret, asserts that the replica equals what the real
# Config.process produced -- so the replica used for the raw-octet vector is proven faithful.
#
# Usage (from the repository root):
#   python -I tools/gen_identity_vectors.py
# The OpenKart checkout is located via the OPENKART_DIR environment variable, defaulting to
# C:\Workspace\openkart-master.

import binascii
import hmac
import io
import json
import os
import socket
import sys
import types
from pathlib import Path

# openkartd/lp2p.py does `from socket import AF_UNIX` for the hostapd control socket, and importing
# openkartd.config pulls openkartd in. CPython on Windows has no AF_UNIX, which would abort the import;
# the constant is never used by the code exercised here, so a placeholder is enough.
if not hasattr(socket, "AF_UNIX"):
    socket.AF_UNIX = -1


def _install_aiohttp_stub():
    """openkartd.config imports the web layer (aiohttp) that serves its HTTP API.

    Config.process() never touches it, so when aiohttp is not installed a permissive stub is enough
    to satisfy the import-time references (class bases and route decorators).
    """
    try:
        import aiohttp  # noqa: F401
        return
    except ImportError:
        pass

    class _Application:
        def __init__(self, *args, **kwargs):
            pass

    class _RouteTableDef:
        def __getattr__(self, name):
            return lambda *args, **kwargs: (lambda f: f)

    web = types.ModuleType("aiohttp.web")
    web.Application = _Application
    web.RouteTableDef = _RouteTableDef
    aiohttp = types.ModuleType("aiohttp")
    aiohttp.web = web
    aiohttp.WSMsgType = types.SimpleNamespace(TEXT=1, ERROR=258)
    sys.modules["aiohttp"] = aiohttp
    sys.modules["aiohttp.web"] = web


OPENKART_DIR = Path(os.environ.get("OPENKART_DIR", r"C:\Workspace\openkart-master"))
sys.path.insert(0, str(OPENKART_DIR))

_install_aiohttp_stub()

from openkartd.config import Config  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
VECTORS_PATH = REPO_ROOT / "core/data/src/commonTest/resources/identity_vectors.json"

# Fixed secrets. The first two are config-file strings (the reference path); the third is a raw
# 32-octet master secret (the app path), derived with the line-for-line replica of derive().
SECRET_ASCII = "mklhc-test-secret-0001"
SECRET_UNICODE = "s\u00e9cret-\u00f1-\u2603"
SECRET_RAW = bytes((0xA0 + i) & 0xFF for i in range(0x20))


def hx(data: bytes) -> str:
    return binascii.hexlify(data).decode()


def derive(secret: bytes, name: str, length: int) -> bytes:
    """Line-for-line replica of the derive() helper inside Config.process (openkartd/config.py)."""
    assert length <= 0x40
    h = hmac.HMAC(digestmod='sha512', key=secret, msg=name.encode())
    return h.digest()[:length]


def reference_identity(secret: str) -> dict:
    """Runs the real Config.process on [secret] and returns what it derived."""
    text = "\n".join([
        "[general]",
        # A '%' would be interpolated by ConfigParser; the fixed secrets contain none.
        f"secret = {secret}",
        "[wireless]",
        "interface = wlan0",
        "channel = 6",
        "",
    ])
    cfg = Config()
    cfg.read(io.StringIO(text))
    cfg.process()
    ssid = cfg.group_info.ssid
    # The reference's brand prefix is everything up to the first underscore (base64 has none); only
    # the random tail is recorded, so no third-party string lands in the Kotlin test resources.
    tail = ssid.split("_", 1)[1]
    return {
        "ssid_tail_b64": tail,
        "psk": cfg.group_info.psk,
        "rcd_ident": cfg.rcd_info.ident,
        "rcd_master_key": cfg.rcd_info.master_key,
    }


def vector_for_string(name: str, secret: str) -> dict:
    ref = reference_identity(secret)
    key = secret.encode()

    # The replica must agree with the real reference on every output.
    ssid_random = derive(key, "wireless/ssid", 0xC)
    assert binascii.b2a_base64(ssid_random).decode().strip() == ref["ssid_tail_b64"]
    assert derive(key, "wireless/psk", 0x20) == ref["psk"]
    assert derive(key, "rcd/ident", 0x10) == ref["rcd_ident"]
    assert derive(key, "rcd/master_key", 0x40) == ref["rcd_master_key"]

    return {
        "name": name,
        "secret_hex": hx(key),
        "secret_text": secret,
        "ssid_random_hex": hx(ssid_random),
        "ssid_tail_b64": ref["ssid_tail_b64"],
        "psk": hx(ref["psk"]),
        "rcd_ident": hx(ref["rcd_ident"]),
        "rcd_master_key": hx(ref["rcd_master_key"]),
    }


def vector_for_raw(name: str, secret: bytes) -> dict:
    ssid_random = derive(secret, "wireless/ssid", 0xC)
    return {
        "name": name,
        "secret_hex": hx(secret),
        "secret_text": None,
        "ssid_random_hex": hx(ssid_random),
        "ssid_tail_b64": binascii.b2a_base64(ssid_random).decode().strip(),
        "psk": hx(derive(secret, "wireless/psk", 0x20)),
        "rcd_ident": hx(derive(secret, "rcd/ident", 0x10)),
        "rcd_master_key": hx(derive(secret, "rcd/master_key", 0x40)),
    }


def main() -> None:
    vectors = [
        vector_for_string("ascii string secret (reference path)", SECRET_ASCII),
        vector_for_string("non-ASCII string secret, UTF-8 encoded", SECRET_UNICODE),
        vector_for_raw("raw 32-octet master secret (app path)", SECRET_RAW),
    ]
    doc = {
        "source": "OpenKart openkartd/config.py Config.process derive(); HMAC-SHA512(key=secret, msg=name)[:length]",
        "lengths": {"ssid_random": 0xC, "psk": 0x20, "rcd_ident": 0x10, "rcd_master_key": 0x40},
        "vectors": vectors,
    }
    VECTORS_PATH.parent.mkdir(parents=True, exist_ok=True)
    # LF line endings on every host, so the committed file does not depend on who regenerated it.
    with open(VECTORS_PATH, "w", encoding="utf-8", newline="\n") as out:
        out.write(json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {VECTORS_PATH} ({len(vectors)} vectors)")


if __name__ == "__main__":
    main()
