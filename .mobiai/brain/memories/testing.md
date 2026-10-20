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

## Building radiod on the Windows dev PC (VS Build Tools 2026) and the no-kart smoke test

- id: building-radiod-on-the-windows-dev-pc-vs-build-tools-2026-an-20261020-181140
- type: testing_pattern
- status: active
- platform: kmp
- area: radio
- date: 2026-10-20

### Environment (verified 2026-10-20 on the dev PC)
- Compiler: Visual Studio Build Tools 2026 (MSVC 14.50.35717, Windows SDK 10.0.26100.0). Not on PATH: use the "x64 Native Tools Command Prompt" or call VC\Auxiliary\Build\vcvars64.bat.
- CMake: use the Build Tools' own CMake (4.1.2-msvc8, default generator "Visual Studio 18 2026"), which is on PATH inside the developer prompt. The Android SDK CMake 4.1.2 only knows generators up to Visual Studio 17 2022; with it, use `-G Ninja` (its bin has ninja.exe) inside vcvars64.
- vcpkg: the Build Tools' bundled vcpkg is manifest-only ("This vcpkg distribution does not have a classic mode instance"), so radiod's VCPKG_ROOT path needs a standalone vcpkg: clone microsoft/vcpkg, run bootstrap-vcpkg.bat, `vcpkg install libusb pkgconf --triplet x64-windows`, set VCPKG_ROOT. libusb-1.0.dll must sit next to radiod.exe.
- Missing on that date: standalone vcpkg + libusb + pkgconf, the radiod/devourer submodule, and the WinUSB binding.
- Resolved the same day: standalone vcpkg at C:\vcpkg (libusb 1.0.30, pkgconf 3.0.3, VCPKG_ROOT set for the user), devourer submodule pinned at 357f045, radiod built at radiod/build/Release/radiod.exe with libusb-1.0.dll deployed next to it. Only the WinUSB binding remained.

### No-kart smoke test (before the GO/NO-GO)
With the T3U bound to WinUSB (Zadig may show it as "802.11ac NIC" or "TP-Link Wireless USB Adapter"; the USB ID 2357 012D is what identifies it, and the PC has other Wi-Fi adapters), run `./gradlew :app:runSpike --args="--vid 2357 --pid 012d --duration 60 --verbose"`. Pass: radiod connects, logs `radio up: chip=...` (READY) and the beacon starts without an error. If bring-up fails, replug the adapter (8822B cold-boot chip-id transient / firmware download retry) and run again.

### Jaguar2 operational facts (from devourer's notes at 357f045)
- The chip keeps airing the beacon after the session ends unless StopBeacon is sent (Jaguar2 has no teardown power-down): stop the spike cleanly, and unplug the adapter after a hard kill.
- SetAckResponder before StartBeacon is safe: StartBeacon is refused only while a station identity (SetStationIdentity) owns port 0, and SetAckResponder does not arm one.
- The T3U has internal antennas: devourer measured delivery collapsing at MCS6-7 on it while low rates stayed near 100%. Keep the kart close in early tests and prefer low rates; consider an external-antenna adapter for range.
- An error path can be exercised without touching the adapter by running the spike with an absent VID:PID (e.g. `--vid 0bda --pid c812`).

### Files
- radiod/CMakeLists.txt
- radiod/README.md
- docs/HARDWARE_SETUP.md
