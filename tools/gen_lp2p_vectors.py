#!/usr/bin/env python3
# Golden-vector generator for the :core:wifi-ap LP2P port.
#
# Business logic: runs the REAL OpenKart reference implementation under fully deterministic,
# recorded randomness and clock, and emits everything the Kotlin tests need to reproduce each
# output byte-for-byte:
#   * core/wifi-ap/src/commonTest/resources/lp2p_vectors.json
#       - derive_keys vectors              (Lp2pKeys)
#       - encrypt_network vectors          (Lp2pBeacon): psk, nonces, every os.urandom draw, ies, ccmp key
#       - nonce-table samples              (NonceTable): plaintext + encrypted nonce for chosen indices
#       - get_nonce_pair scenario          (NonceTable): fixed clock, scripted random draws, results
#   * core/wifi-ap/src/jvmSharedMain/resources/lp2p_nonces.bin
#       - the decoded NONCES table (65536 * 16 = 1048576 bytes), loaded by the Kotlin NonceTable.
#
# Ported from / generated against OpenKart https://github.com/OpenKart-SDK,
# openkartd/lp2p.py and openkartd/lp2p_nonces.py (BSD-3-Clause). Copyright 2021 Sam Edwards.
#
# Usage (from the repository root):
#   python -I tools/gen_lp2p_vectors.py
# The OpenKart checkout is located via the OPENKART_DIR environment variable, defaulting to
# C:\Workspace\openkart-master.

import binascii
import hashlib
import json
import os
import socket
import sys
import types
from pathlib import Path

# openkartd/lp2p.py does `from socket import AF_UNIX` for the hostapd control socket. CPython on
# Windows has no AF_UNIX, which would abort the import. The constant is never used by the functions
# exercised here, so a placeholder is enough.
if not hasattr(socket, "AF_UNIX"):
    socket.AF_UNIX = -1

OPENKART_DIR = Path(os.environ.get("OPENKART_DIR", r"C:\Workspace\openkart-master"))
sys.path.insert(0, str(OPENKART_DIR))

import openkartd.lp2p as lp2p  # noqa: E402
import openkartd.lp2p_nonces as nonces  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
VECTORS_PATH = REPO_ROOT / "core/wifi-ap/src/commonTest/resources/lp2p_vectors.json"
NONCE_BIN_PATH = REPO_ROOT / "core/wifi-ap/src/jvmSharedMain/resources/lp2p_nonces.bin"

NONCE_COUNT = 0x10000
NONCE_SIZE = 16

# Fixed pre-shared key used by the encrypt_network cases (32 bytes, as GroupInfo requires).
FIXED_PSK = bytes((0x80 + i) & 0xFF for i in range(0x20))

# Fixed wall clock for every get_seq() call. The fractional part proves int() truncation.
FIXED_TIME = 1760000000.75

NONCE_SAMPLE_INDICES = [0, 1, 2, 3, 4, 0x1234, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF]


def hx(data: bytes) -> str:
    return binascii.hexlify(data).decode()


class DeterministicSources:
    """Replaces every non-deterministic input of encrypt_network and records each draw.

    - urandom(n): a counter that yields 0x01, 0x02, ... across consecutive draws (the draws are
      recorded in order so the Kotlin test can feed back the exact same bytes).
    - randrange(n): a scripted FIFO of (bound, value) pairs; asserts the bound matches.
    - time.time(): FIXED_TIME.
    """

    def __init__(self, urandom_start: int, randrange_script):
        self._counter = urandom_start
        self._script = list(randrange_script)
        self.urandom_draws = []
        self.randrange_draws = []

    def urandom(self, n: int) -> bytes:
        draw = bytes(((self._counter + i) & 0xFF) for i in range(n))
        self._counter += n
        self.urandom_draws.append(draw)
        return draw

    def randrange(self, n: int) -> int:
        bound, value = self._script.pop(0)
        assert bound == n, f"unexpected randrange bound {n}, script expected {bound}"
        assert 0 <= value < n
        self.randrange_draws.append({"bound": n, "value": value})
        return value

    def install(self):
        """Patch the OpenKart modules; fresh sequence state so every scenario is independent."""
        lp2p.os = types.SimpleNamespace(urandom=self.urandom)
        nonces.random = types.SimpleNamespace(randrange=self.randrange)
        nonces.time = types.SimpleNamespace(time=lambda: FIXED_TIME)
        nonces._seq = None


def derive_keys_vector(name: str, nonce: bytes, psk: bytes) -> dict:
    ka, kc = lp2p.LP2PAP.derive_keys(nonce, psk)
    return {"name": name, "nonce": hx(nonce), "psk": hx(psk), "ka": hx(ka), "kc": hx(kc)}


def encrypt_network_case(name: str, temporary: bool, urandom_start: int, randrange_script) -> dict:
    sources = DeterministicSources(urandom_start, randrange_script)
    sources.install()

    # Record the nonce pair that the real get_nonce_pair hands to encrypt_network.
    recorded = {}
    real_get_nonce_pair = nonces.get_nonce_pair

    def recording_get_nonce_pair(*, pure_random=False):
        plain, encrypted = real_get_nonce_pair(pure_random=pure_random)
        recorded["pure_random"] = pure_random
        recorded["plain"] = plain
        recorded["encrypted"] = encrypted
        return plain, encrypted

    lp2p.get_nonce_pair = recording_get_nonce_pair
    try:
        ccmp_key, ies = lp2p.LP2PAP.encrypt_network(FIXED_PSK, temporary=temporary)
    finally:
        lp2p.get_nonce_pair = real_get_nonce_pair

    assert recorded["pure_random"] == temporary
    assert len(sources.urandom_draws) == 2, "encrypt_network is expected to draw exactly twice"

    return {
        "name": name,
        "psk": hx(FIXED_PSK),
        "temporary": temporary,
        "time": FIXED_TIME,
        "randrange_draws": sources.randrange_draws,
        "plain_nonce": hx(recorded["plain"]),
        "encrypted_nonce": hx(recorded["encrypted"]),
        "urandom_draws": [hx(d) for d in sources.urandom_draws],
        "ies": hx(ies),
        "ccmp_key": hx(ccmp_key),
    }


def nonce_pair_scenario() -> dict:
    """Three sequential (time-based) calls then one pure-random call, sharing the _seq state."""
    sources = DeterministicSources(0, [(16, 5), (0x10000, 0xBEEF)])
    sources.install()

    calls = []
    for pure_random in (False, False, False, True):
        plain, encrypted = nonces.get_nonce_pair(pure_random=pure_random)
        index = int.from_bytes(plain[-2:], "big")
        calls.append(
            {
                "pure_random": pure_random,
                "index": index,
                "plain_nonce": hx(plain),
                "encrypted_nonce": hx(encrypted),
            }
        )

    return {
        "time": FIXED_TIME,
        "seq_period": nonces.SEQ_PERIOD,
        "randrange_draws": sources.randrange_draws,
        "calls": calls,
    }


def write_nonce_table() -> bytes:
    table = binascii.a2b_base64(nonces.NONCES)
    expected_size = NONCE_COUNT * NONCE_SIZE
    assert len(table) == expected_size, f"decoded table is {len(table)} bytes, expected {expected_size}"

    # Prove the flat i*16 slice is exactly what OpenKart's base64 block arithmetic returns, for
    # every index (this is what the Kotlin NonceTable relies on).
    for i in range(NONCE_COUNT):
        assert nonces.get_encrypted_nonce(i) == table[i * NONCE_SIZE:(i + 1) * NONCE_SIZE], i

    NONCE_BIN_PATH.parent.mkdir(parents=True, exist_ok=True)
    NONCE_BIN_PATH.write_bytes(table)
    return table


def main():
    table = write_nonce_table()
    print(f"wrote {NONCE_BIN_PATH} ({NONCE_BIN_PATH.stat().st_size} bytes)")

    # derive_keys vectors: the PSK path (ka, kc) and the LOCAL_COMMUNICATION_ID path (kb, kd).
    plain_1234 = nonces.get_plaintext_nonce(0x1234)
    lcid_le = lp2p.LOCAL_COMMUNICATION_ID.to_bytes(8, "little")
    derive_vectors = [
        derive_keys_vector("psk path, nonce index 0x1234", plain_1234, FIXED_PSK),
        derive_keys_vector("local-communication-id path (little-endian), nonce index 0x1234", plain_1234, lcid_le),
        derive_keys_vector("all-zero nonce, all-zero psk", bytes(16), bytes(32)),
    ]

    encrypt_cases = [
        encrypt_network_case("sequential nonce (temporary=false)", False, 0x01, [(16, 9)]),
        encrypt_network_case("pure-random nonce (temporary=true)", True, 0x41, [(0x10000, 0x1234)]),
    ]

    samples = [
        {
            "index": i,
            "plaintext_nonce": hx(nonces.get_plaintext_nonce(i)),
            "encrypted_nonce": hx(nonces.get_encrypted_nonce(i)),
        }
        for i in NONCE_SAMPLE_INDICES
    ]

    vectors = {
        "source": "OpenKart openkartd/lp2p.py and openkartd/lp2p_nonces.py (BSD-3-Clause, Copyright 2021 Sam Edwards)",
        "generator": "tools/gen_lp2p_vectors.py",
        "constants": {
            "local_communication_id": f"{lp2p.LOCAL_COMMUNICATION_ID:016x}",
            "well_known": nonces.WELL_KNOWN.decode(),
            "revision": nonces.REVISION,
            "seq_period": nonces.SEQ_PERIOD,
        },
        "derive_keys": derive_vectors,
        "encrypt_network": encrypt_cases,
        "nonce_samples": samples,
        "nonce_pair": nonce_pair_scenario(),
        "nonce_table": {
            "size": len(table),
            "sha256": hashlib.sha256(table).hexdigest(),
        },
    }

    VECTORS_PATH.parent.mkdir(parents=True, exist_ok=True)
    VECTORS_PATH.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {VECTORS_PATH}")
    print("json keys: " + ", ".join(vectors.keys()))


if __name__ == "__main__":
    main()
