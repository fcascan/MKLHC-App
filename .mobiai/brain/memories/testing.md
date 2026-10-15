# Testing Patterns

<!--
Reusable testing patterns discovered for this project.
Append entries with: mobiai brain save testing (coming in Phase 2).
Include the problem, the pattern that solved it and a minimal example.
-->

## Golden-vector tests for LP2P and CCMP (IEEE vector + OpenKart-generated)

- id: golden-vector-tests-for-lp2p-and-ccmp-ieee-vector-openkart-g-20261014-231119
- type: testing_pattern
- status: active
- date: 2026-10-14

### Pattern
Validate the LP2P/CCMP crypto in commonTest against golden vectors computed outside the app, so the tests are known-correct before implementation.
- CCMP: use the IEEE Std 802.11-2012 M.6.4 CCMP test vector (same one hostapd wlantest/test_vectors.c uses). TK c97c1f67ce371185514a8a19f2bdd52f, PN B5039776E70C, 44-byte frame; expected encrypted MPDU reproduced during planning with Python cryptography AESCCM(tag_length=8): AAD=08400fd2e128a57c5030f1844408abaea5b8fcba0000, nonce=005030f1844408b5039776e70c, enc=f3d0a2fe9a3dbf2342a643e43246e80c3c04d019, mic=7845ce0b16f97623.
- LP2P keys/IEs: generate vectors with tools/gen_lp2p_vectors.py, which imports OpenKart openkartd/lp2p.py with os.urandom and random monkeypatched to fixed seeds, and emits psk/ccmp_key/ie_bytes JSON. Same script converts lp2p_nonces.py to a binary resource.
### Why
These are interop crypto paths where a silent byte error means the kart simply never associates, with no useful error. Golden vectors catch it in a unit test instead of on hardware.
