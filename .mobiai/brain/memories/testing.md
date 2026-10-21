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

## T3U no-kart smoke test passed: READY in ~46 s on Windows, beacon seen on air

- id: t3u-no-kart-smoke-test-passed-ready-in-46-s-on-windows-beaco-20261020-191026
- type: testing_pattern
- status: active
- platform: kmp
- area: radio
- date: 2026-10-20

### Result (2026-10-20, Windows dev PC, T3U 2357:012d bound to WinUSB)
PASS. Command: `./gradlew :app:runSpike --args="--vid 2357 --pid 012d --duration 150 --ready-timeout 120 --no-qr --verbose"`.
Timeline from process start (about 6 s of Gradle start-up included): radiod up, USB open, power-on, firmware download and boot at 6 s; BB phy_reg + agc_tab (24,352 writes) from 7 to 38 s; RF radioa + radiob from 38 to 51 s; LCK, IQK (every fail count 0) and TXAGC to 53 s; READY at 53 s (chip RTL8822B, C8822B, cut 3, 2T2R, rfe_type 3); the hardware beacon armed at once (channel 6, 100 TU, 191 bytes).
The PC's internal MT7922 saw the beacon on air in the first forced scan (WlanScan + `netsh wlan show networks mode=bssid`): the spike's BSSID at 100% signal, 2.4 GHz, channel 6, 802.11g. RX counted about 15 ambient frames per second, txErrors 0. Clean stop: beacon stopped, clean EOF, radiod exit 0, no leftover process.

### Findings
- INIT takes about 46 s on Windows because every PHY table write is a USB control transfer (~1.3 ms over WinUSB). The spike's default READY timeout went from 20 s to 120 s and `--ready-timeout` was added. The real KartRadioAp bring-up must budget for this on desktop; Android (usbfs) should be faster, to be measured in Phase 2.
- `Jaguar2: LCK TIMEOUT (LO not locked!)` is logged on the 8822B but is a known false alarm (devourer HalJaguar2.cpp: the direct RF read returns transient status bits, and the LO does lock). The radio worked.
- The T3U enumerates at 480 Mbit/s (USB 2 mode, the 88x2bu default).
- Next: the kart GO/NO-GO (pairing, association with retry=0, decrypted DHCP DISCOVER).

### Files
- app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikeRunner.kt
- docs/HARDWARE_SETUP.md
- radiod/src/Radio.cpp

## Phase 1 GO/NO-GO passed with the kart: association retry=0, decrypted DHCP, full lease

- id: phase-1-go-no-go-passed-with-the-kart-association-retry-0-de-20261020-195341
- type: testing_pattern
- status: active
- platform: kmp
- area: radio
- date: 2026-10-20

### Result: GO (2026-10-20, Windows dev PC, TP-Link Archer T3U on WinUSB, channel 6)
- Criterion A met: the kart (70:2c:09:b1:32:67) authenticated and associated with `retry=0` on every attempt (more than 40 association cycles over two runs). AID 1, status 0.
- Criterion B met: its CCMP data frames decrypt with the key derived from the QR seed; the DHCP DISCOVER parses in plaintext in the pcap.
- Beyond the gate: once the DHCP replies were broadcast (see the bugfix entry of the same date), the kart completed DISCOVER, OFFER, REQUEST, ACK and took 169.254.98.66.
- What the kart does next: about 20 ms after the ACK it sends ARP who-has 169.254.98.65 (the AP/app address), retries after 1 s, then sends DHCP RELEASE and deauthenticates (reason 3) about 2 s after associating, and starts over. The spike answers neither ARP nor TCP, so the next step is the real wifi-ap FrameLink / KartRadioAp adapter over RadioLink, so that NetstackNode answers ARP and the RCD pairing handshake on TCP 5201 can run.

### Observations for the adapter and the netstack
- Discovery: the kart sends probe requests to the broadcast address carrying the pairing SSID; answering them (hidden AP, `ignore_broadcast_ssid=2` style) is required.
- Its association request carries SSID, rates 1/2/5.5/11 basic + 6..54, and an RSN IE (CCMP group and pairwise, AKM PSK), listen interval 3, capability 0x0011; no WMM and no HT, so plain (non-QoS) data frames are right.
- No power save was seen (PM bit 0 on every frame). A few retransmitted uplink frames hit the replay check (`PN replay detected`, retry=1), which is correct behaviour.
- radiod's `rssiDbm` values were 74..97, positive, so the field is most likely a percentage, not dBm (open item noted in radiod/README.md).
- The pairing QR has to be shown by a working renderer: under Gradle on Windows the console QR comes out as `?` (see the bugfix entry of the same date).

### Files
- app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikeRunner.kt
- core/netstack/src/commonMain/kotlin/com/mklhc/core/netstack/NetstackNode.kt
- docs/HARDWARE_SETUP.md

## Hardware run: the kart answers ARP and completes the RCD pairing handshake on TCP 5201

- id: hardware-run-the-kart-answers-arp-and-completes-the-rcd-pair-20261021-014819
- type: testing_pattern
- status: active
- platform: kmp
- area: radio
- date: 2026-10-21

### Result (hardware, 2026-10-20, TP-Link Archer T3U on WinUSB, channel 6, kart in pairing mode)
GO for the pairing TCP on 5201 with the real link layer (RadioAccessPoint + NetstackNode, commit 46612ba). Over about 8 minutes and some 55 cycles (13 decrypted frames each), every cycle: `auth` and `assoc` with `retry=0` (rssiDbm 69-85 as reported by radiod), DHCP OFFER/ACK broadcast and lease taken, TCP SYN to 169.254.98.65:5201 accepted (`pairing TCP accepted`), and the RCD pairing handshake completed (version 2; the kart presents its device name). The ARP who-has for the app (`answering who-has`) appeared only in the first cycle: the kart caches the app's MAC across its resets.

### Kart behaviour observed
- After the handshake the app does not connect to the kart's pairing service (the spike never sends SetGroupInfo), and the kart closes the RCD connection (FIN), releases the lease and deauthenticates with reason 3 within a few seconds, then starts over. The spike frees the lease and resets the stale connection each time.
- Each cycle uses a new TCP source port (56429, 56430, ...); after the kart restarted mid-run the port series jumped (to 42558), a quick way to spot a kart reboot in the log.
- `CCMP rejected a frame ... retry=true reason=PN replay detected` shows up a few times: the kart retransmitting frames already received; harmless.
- The QR was shown from the spike's SVG file in the browser pane; the console QR printed as `?` under Gradle, as expected.

### Not verified / issues
- Station inactivity drop: not observed. The kart was switched off but apparently restarted within 20 s (cycles kept coming), so no `station inactive, dropped` line; repeat with the kart left off for at least 30 s.
- Clean stop: an Enter typed in the app's Terminal panel never reached the spike under Gradle (the run kept cycling), so it was stopped with Ctrl+C, which under Gradle on Windows ends the JVM without its shutdown hook; the T3U was then unplugged and three WLAN scans confirmed the beacon off air. Prefer `--duration` until the spike gets a stop path that does not depend on Gradle forwarding stdin.
- The kart's stored pairing after these handshakes was not checked.

### Files
- app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikePairingProbe.kt
- core/wifi-ap/src/commonMain/kotlin/com/mklhc/core/wifi_ap/ap/RadioAccessPoint.kt
- docs/HARDWARE_SETUP.md
