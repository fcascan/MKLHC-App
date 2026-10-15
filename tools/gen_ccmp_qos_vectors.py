#!/usr/bin/env python3
# Independent CCMP golden generator; CCM core = pyca/cryptography AESCCM; AAD/nonce built per IEEE 802.11.
#
# Business logic: the :core:wifi-ap CCMP implementation hand-rolls AES-CCM (CBC-MAC + CTR) over a
# single-block AES primitive. To pin that implementation against something it shares no code with,
# this generator constructs the 802.11 MAC header, the Additional Authentication Data (AAD) and the
# 13-octet CCM nonce ITSELF, per IEEE Std 802.11 clause 12.5.3.3.3 ("Construct AAD") and the CCMP
# nonce construction, and then delegates the CCM core to pyca/cryptography's AESCCM (an OpenSSL-backed
# implementation, independent of the Kotlin one). It emits one golden vector per case to
#   core/wifi-ap/src/commonTest/resources/ccmp_qos_vectors.json
# carrying, for each case: tk, header, data, the full plaintext MPDU (header+data), headerLength, pn,
# keyId, the expected AAD, the expected nonce, the expected CCMP header and the expected full
# encrypted MPDU (header || ccmpHeader || ciphertext || MIC).
#
# Cases:
#   1. QoS data, 3-address, TID 0, Order=0, Protected (FC = 0x88 0x42). 26-octet header.
#   2. QoS data + HT Control, Order=1, TID 6 (FC = 0x88 0xC2). 30-octet header; the 4 HT Control
#      octets are part of the MAC header but NOT part of the AAD.
# A third internal self-check reproduces the IEEE Std 802.11-2012 M.6.4 non-QoS vector to prove the
# AAD/nonce construction and the AESCCM core agree with the published reference. It is asserted but
# not emitted.
#
# Usage (from the repository root):
#   python -I tools/gen_ccmp_qos_vectors.py

import binascii
import json
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESCCM

REPO_ROOT = Path(__file__).resolve().parent.parent
VECTORS_PATH = REPO_ROOT / "core/wifi-ap/src/commonTest/resources/ccmp_qos_vectors.json"

MIC_LEN = 8
EXT_IV = 0x20


def hx(data: bytes) -> str:
    return binascii.hexlify(data).decode()


def unhx(text: str) -> bytes:
    return binascii.unhexlify(text)


def frame_type(fc0: int) -> int:
    return (fc0 >> 2) & 0x3


def frame_subtype(fc0: int) -> int:
    return (fc0 >> 4) & 0xF


def is_data(hdr: bytes) -> bool:
    return frame_type(hdr[0]) == 2


def is_management(hdr: bytes) -> bool:
    return frame_type(hdr[0]) == 0


def is_qos_data(hdr: bytes) -> bool:
    return is_data(hdr) and (frame_subtype(hdr[0]) & 0x8) != 0


def is_four_address(hdr: bytes) -> bool:
    to_ds = hdr[1] & 0x01
    from_ds = hdr[1] & 0x02
    return bool(to_ds and from_ds)


def qos_control_offset(hdr: bytes) -> int:
    # QoS Control follows the (optional 4th) address; HT Control, when present, follows QoS Control.
    return 30 if is_four_address(hdr) else 24


def build_aad(hdr: bytes) -> bytes:
    # IEEE 802.11 clause 12.5.3.3.3: AAD = masked FC(2) | A1 | A2 | A3 | masked SeqCtrl(2) | [A4] | [QC].
    qos = is_qos_data(hdr)
    four = is_four_address(hdr)

    fc0 = hdr[0]
    if is_data(hdr):
        # Clear the low subtype bits (4,5,6); keep version, type and subtype bit 3 (QoS-data stays 8).
        fc0 &= 0x8F
    fc1 = hdr[1] & 0xC7  # clear Retry (0x08), PwrMgmt (0x10), MoreData (0x20)
    fc1 |= 0x40          # force Protected
    if qos:
        fc1 &= ~0x80 & 0xFF  # clear Order / +HTC bit for QoS data

    aad = bytearray()
    aad.append(fc0)
    aad.append(fc1)
    aad += hdr[4:22]                 # A1, A2, A3
    aad.append(hdr[22] & 0x0F)       # SeqCtrl: keep fragment number
    aad.append(0x00)                 # SeqCtrl: zero the sequence number
    if four:
        aad += hdr[24:30]            # A4
    if qos:
        qc = qos_control_offset(hdr)
        aad.append(hdr[qc] & 0x0F)   # QoS Control: keep TID (bits 0-3), mask A-MSDU-present and the rest
        aad.append(0x00)             # QoS Control: second octet masked to 0
    return bytes(aad)


def build_nonce(hdr: bytes, pn: bytes) -> bytes:
    # 13-octet CCM nonce = nonceFlags(1) | A2(6) | PN(6, big-endian PN5..PN0).
    assert len(pn) == 6
    flags = 0
    if is_qos_data(hdr):
        flags |= hdr[qos_control_offset(hdr)] & 0x0F  # priority = TID
    if is_management(hdr):
        flags |= 0x10  # Management bit
    return bytes([flags]) + hdr[10:16] + pn


def build_ccmp_header(pn: bytes, key_id: int) -> bytes:
    # PN0, PN1, Rsvd(0), (keyId<<6)|ExtIV, PN2, PN3, PN4, PN5; pn is big-endian (pn[0]=PN5..pn[5]=PN0).
    return bytes([pn[5], pn[4], 0x00, (key_id << 6) | EXT_IV, pn[3], pn[2], pn[1], pn[0]])


def make_case(name, tk, header, data, pn, header_length, key_id=0) -> dict:
    assert len(tk) == 16
    assert len(pn) == 6
    assert len(header) == header_length, f"{name}: header is {len(header)} octets, expected {header_length}"
    aad = build_aad(header)
    nonce = build_nonce(header, pn)
    ccmp_header = build_ccmp_header(pn, key_id)

    # AESCCM with tag_length=8 and a 13-octet nonce gives L=2: exactly the CCMP CCM parameters.
    aesccm = AESCCM(tk, tag_length=MIC_LEN)
    ct_and_mic = aesccm.encrypt(nonce, data, aad)  # ciphertext || MIC(8)
    ciphertext = ct_and_mic[:-MIC_LEN]
    mic = ct_and_mic[-MIC_LEN:]
    assert len(ciphertext) == len(data)

    # pyca must round-trip against the same AAD/nonce.
    assert aesccm.decrypt(nonce, ct_and_mic, aad) == data

    encrypted_mpdu = header + ccmp_header + ct_and_mic

    return {
        "name": name,
        "tk": hx(tk),
        "header": hx(header),
        "data": hx(data),
        "mpduPlaintext": hx(header + data),
        "headerLength": header_length,
        "pn": hx(pn),
        "keyId": key_id,
        "aad": hx(aad),
        "nonce": hx(nonce),
        "ccmpHeader": hx(ccmp_header),
        "ciphertext": hx(ciphertext),
        "mic": hx(mic),
        "mpduEncrypted": hx(encrypted_mpdu),
    }


def self_check_m64():
    # IEEE Std 802.11-2012 M.6.4 non-QoS 3-address data vector: prove our construction + AESCCM match.
    tk = unhx("c97c1f67ce371185514a8a19f2bdd52f")
    full = unhx("0848c32c0fd2e128a57c5030f1844408abaea5b8fcba8033f8ba1a55d02f85ae967bb62fb6cda8eb7e78a050")
    header = full[:24]
    data = full[24:]
    pn = unhx("b5039776e70c")
    aad = build_aad(header)
    nonce = build_nonce(header, pn)
    ccmp_header = build_ccmp_header(pn, 0)
    aesccm = AESCCM(tk, tag_length=MIC_LEN)
    ct_and_mic = aesccm.encrypt(nonce, data, aad)
    mpdu = header + ccmp_header + ct_and_mic
    assert hx(aad) == "08400fd2e128a57c5030f1844408abaea5b8fcba0000", hx(aad)
    assert hx(nonce) == "005030f1844408b5039776e70c", hx(nonce)
    assert hx(ccmp_header) == "0ce70020769703b5", hx(ccmp_header)
    assert hx(ct_and_mic[:-MIC_LEN]) == "f3d0a2fe9a3dbf2342a643e43246e80c3c04d019", hx(ct_and_mic)
    assert hx(ct_and_mic[-MIC_LEN:]) == "7845ce0b16f97623", hx(ct_and_mic)
    expected = (
        "0848c32c0fd2e128a57c5030f1844408abaea5b8fcba8033"
        "0ce70020769703b5"
        "f3d0a2fe9a3dbf2342a643e43246e80c3c04d019"
        "7845ce0b16f97623"
    )
    assert hx(mpdu) == expected, hx(mpdu)
    print("M.6.4 non-QoS self-check: OK (AAD/nonce construction and AESCCM match the IEEE reference)")


def case_qos_tid0() -> dict:
    # QoS data, 3-address, from-DS (AP -> STA), TID 0, Order=0, Protected.
    fc = bytes([0x88, 0x42])              # QoS data; fromDS + Protected
    duration = unhx("2c01")              # not in AAD/nonce; proves Duration/ID is excluded
    a1 = unhx("02aabbccdd01")            # DA (station)
    a2 = unhx("021122334455")            # BSSID / TA
    a3 = unhx("02aabbccdd02")            # SA (source)
    seq_ctrl = unhx("3012")             # SeqCtrl: seq=0x123, frag=0 (0x1230 little-endian)
    qos_ctrl = unhx("0000")             # TID 0, all other bits clear
    header = fc + duration + a1 + a2 + a3 + seq_ctrl + qos_ctrl
    data = unhx("aaaa03000000080045000014abcd400040117a3cc0a80101c0a801ff")
    pn = unhx("0102030405a0")
    tk = unhx("000102030405060708090a0b0c0d0e0f")
    return make_case("qos-data-tid0-3addr-protected", tk, header, data, pn, 26, key_id=0)


def case_qos_htc_tid6() -> dict:
    # QoS data + HT Control, 3-address, from-DS, TID 6, Order=1 (transmitted), Protected.
    fc = bytes([0x88, 0xC2])             # QoS data; Order (+HTC) + Protected + fromDS
    duration = unhx("3a00")
    a1 = unhx("02aabbccdd01")
    a2 = unhx("021122334455")
    a3 = unhx("02aabbccdd02")
    seq_ctrl = unhx("7045")             # SeqCtrl: seq=0x457, frag=0
    qos_ctrl = unhx("9655")             # TID 6 (0x06) with EOSP(0x10), A-MSDU present(0x80) and 2nd octet set
    ht_ctrl = unhx("04030201")          # HT Control (4 octets): in the header, NOT in the AAD
    header = fc + duration + a1 + a2 + a3 + seq_ctrl + qos_ctrl + ht_ctrl
    data = unhx("aaaa030000000806000102030405060708090a0b0c0d0e0f1011")
    pn = unhx("0000000012c6")
    tk = unhx("101112131415161718191a1b1c1d1e1f")
    return make_case("qos-data-htc-tid6-3addr-protected", tk, header, data, pn, 30, key_id=0)


def main():
    self_check_m64()

    cases = [case_qos_tid0(), case_qos_htc_tid6()]

    vectors = {
        "source": "independent CCMP golden generator; CCM core = pyca/cryptography AESCCM; "
        "AAD/nonce built per IEEE 802.11 clause 12.5.3.3.3",
        "generator": "tools/gen_ccmp_qos_vectors.py",
        "cases": cases,
    }

    VECTORS_PATH.parent.mkdir(parents=True, exist_ok=True)
    VECTORS_PATH.write_text(json.dumps(vectors, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {VECTORS_PATH}")
    for case in cases:
        print(f"  case '{case['name']}':")
        print(f"    headerLength = {case['headerLength']}")
        print(f"    aad          = {case['aad']}")
        print(f"    nonce        = {case['nonce']}")
        print(f"    ccmpHeader   = {case['ccmpHeader']}")
        print(f"    mpduEncrypted= {case['mpduEncrypted']}")


if __name__ == "__main__":
    main()
