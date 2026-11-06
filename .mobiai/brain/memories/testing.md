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
- Clean stop: an Enter typed in the app's Terminal panel never reached the spike under Gradle (the run kept cycling), so it was stopped with Ctrl+C, which under Gradle on Windows ends the JVM without its shutdown hook; the T3U was then unplugged and three WLAN scans confirmed the beacon off air. Follow-up (2026-10-21): a piped Enter does reach the spike under Gradle, but a lone CR never ended `readlnOrNull()`, so the Enter reader now ends on CR too; and the spike can be stopped cleanly by creating the stop file `<out>\spike.stop` (the `New-Item` command with the absolute path is printed in the `Spike running` line).
- The kart's stored pairing after these handshakes was not checked.

### Files
- app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikePairingProbe.kt
- core/wifi-ap/src/commonMain/kotlin/com/mklhc/core/wifi_ap/ap/RadioAccessPoint.kt
- docs/HARDWARE_SETUP.md

## Hardware run: full pairing from the desktop app, game session with video and telemetry

- id: hardware-run-full-pairing-from-the-desktop-app-20261021
- type: testing_pattern
- status: active
- platform: kmp
- area: session
- date: 2026-10-21

### Result (hardware, 2026-10-21, TP-Link Archer T3U on WinUSB, channel 6, `MKLHC_RADIO=2357:012d ./gradlew :app:run`)
GO for the complete pairing from the desktop app. First attempt (commit 7c04f58): handshake complete, then the kart refused the connect to its pairing service 5106 (RST) and the app failed fatally without sending SetGroupInfo (see bugfix "Pairing from the desktop app failed: the kart refused the first connect"). Second attempt, with the connect retry in the working tree: Pair a kart, the kart scanned the QR in the window, SetGroupInfo went through, the kart reset onto the game network and the game session came up: about 86,700 decrypted frames in roughly 5 minutes, mostly 1452-octet video datagrams (UDP payload 1416) plus telemetry. Being decrypted with the game key, which the kart only gets through SetGroupInfo, is itself proof the pairing was stored. Closing the window ran the exit path: `shutdown(): requesting kart power-off`, every connection reset, `beacon stopped`, radiod down, data directory released; three WLAN scans confirmed the BSSID off air.

### Kart behaviour observed
- Telemetry type 3 (87 octets) arrives about every 100 ms and is not decoded yet (`type 3 telemetry is not specified yet`).
- Video: the reassembler trims surplus octets at the end of nearly every frame (surplus 33-1376, frames 8,000-12,000 octets) and now and then drops a continuation with no frame in progress, so the frame length or the datagram layout it assumes is off. The desktop cockpit has no H.264 decoder by design (it only counts access units), so no picture was expected there. Cause found with a raw dump: every continuation starts with a 13-octet header the reassembler keeps (see "Video datagram layout measured from a raw dump").

### Not verified / issues
- The pairing log lines (connectToKart refusals, group info sent, paired kart remembered) scrolled out of the Terminal panel's 1,000-line buffer before they were read: the debug log of every decrypted frame floods it. Keep a log file, or lower that per-frame line, for the next run.
- Driving was not tried.

### Files
- core/data/src/commonMain/kotlin/com/mklhc/core/data/session/KartSessionRepository.kt
- core/kart-protocol/src/commonMain/kotlin/com/mklhc/core/kart_protocol/video/LspVideoReassembler.kt
- core/video/src/desktopMain/kotlin/com/mklhc/core/video/VideoSurface.desktop.kt

## Video datagram layout measured from a raw dump: every datagram has a 13-octet header

- id: video-datagram-layout-measured-from-a-raw-dump-20261021
- type: testing_pattern
- status: active
- platform: kmp
- area: video
- date: 2026-10-21

### Capture
`MKLHC_VIDEO_DUMP=spike-captures\video` (commit b4ad353), game session on the paired kart, 72.8 s: 20,345 datagrams, all 1416 octets (the kart pads the last one of a frame with stale buffer content), 2,185 frames (2,112 with FRAM at octet 13, 73 keyframes with the L2 block and FRAM at 43), no loss. The analysis scripts were throwaway Python over the dump format.

### Layout (confirmed on every datagram)
- Octets 0-1 `ec c1`; 2-4 a big-endian datagram sequence number, +1 per datagram; 5 a group number, +1 per group; 6 the datagrams in the group (up to 14), 7 the index in it (0-based); 10 repeats octet 4. A keyframe spans several groups, so a group is not a video frame.
- Octets 8-12 of a plain datagram are a 5-octet chunk header ending in a 2-octet field at 11-12: `80 1f` before a FRAM record (0x1f = 31, the length octet the reassembler already uses for rawStart), `85 72` on a full continuation, `ba 00`/`ba 01`/`b6 00`/`be 00` on the last datagram of a group. A keyframe start has the L2 block at 8 and the same 5-octet pattern right before FRAM at 43.
- H.264 in a continuation starts at octet 13. With octet 11 = 0x85 it is `(octets 11-12 & 0x7FF) + 1` octets (1395 normally; 1391-1394 in 10 datagrams); the last datagram of a frame carries the rest from octet 13, cut by the FRAM length (superseded by B3 below: from octet 17, after a 4-octet field the FRAM size counts). The 8-octet trailer and the padding are not H.264.
- Check: under that model 2,171 of 2,185 frames end exactly in their last datagram, and the octet there is always an rbsp stop byte (80, c0, 40, 60, e0, 20, a0) followed by 00 00, the end of a slice. The other 14 are off by 0 or 1 octet and are exactly the frames holding an odd-length (0x56e-0x571) continuation (resolved by B3 below).

### What it means for LspVideoReassembler
It appended continuations from octet 0, so every continuation added 13 header octets to the frame: the frame filled early (the surplus seen in the logs), came out corrupt, and a trailing continuation was sometimes dropped as having no frame in progress.

### Fix (applied 2026-10-21, backlog B1, then corrected by B3 below; not yet re-run on hardware)
`LspVideoReassembler` now starts every continuation at octet 13; a chunk of type 0x80 (octet 11 & 0xF8, the kart's `85 72`) holds `(field & 0x7FF) + 1` octets and the stale octets after it are ignored; other chunks (`ba 00`...) run to the trailer and the FRAM length trims the padding. A non-successor sequence number (24-bit, wrapping) with a frame in progress drops that frame with a warning; datagrams under 21 octets are dropped; `reset()` forgets the sequence. Constants in `LspVideo` (DATAGRAM_HEADER, SEQUENCE_*, CHUNK_*). Replaying the real dump through the Kotlin code (throwaway test): 2,185 of 2,185 frames emitted, none dropped, all starting with `00 00 00 01` and ending on an rbsp stop byte; 14 trailing datagrams dropped: the frames holding an odd-length chunk complete one datagram early, so their last datagram has no frame to join and their final octet may be wrong (B2). Test vectors in `LspTestVectors` build the measured layout; never commit real dump bytes.

### B2: the 14 frames that close one datagram early (investigated 2026-10-21, no code change)
They are the kart's quirk at the end of an access unit, around the NEXT access unit's start code:
- In 12 of them the frame's last 0x80 chunk ends with an Annex-B start (`00 00 01 09`, or `00 00 00 01`), which can never be inside a slice; 10 of those chunks have a short length (0x56e-0x571), 2 the normal 0x572. The frame's last 3 octets then come at the start of the group's last datagram (`01 7d 40`, `65 4a 20`, `28 2e 80`..., each ending on an rbsp stop byte), followed by pieces of the next AUD (`00 30 00`, `01 09 30`).
- Leaving a trailing `00 00 01 09` out of a chunk and taking the rest from the last datagram closes 10 more frames exactly (2,181 of 2,185); the other 4 still disagree by one octet with the FRAM length (they end on `00`, or on a `01` after the stop byte), so no single rule matches all of them, and the FRAM length may count one octet of the next start code.
- Today those 14 frames come out at their FRAM length ending in a dangling `00 00 01` (or with the stop byte missing) and the last datagram is dropped as an orphan: at worst the last macroblocks of 0.6% of pictures, resynchronised by the next frame.
- Decision: no heuristic until a real decoder can say which bytes are right (B3/B4). Scripts: `.ia-docs/video-analysis/viddump_odd.py`, `viddump_aud_tail.py`, `viddump_rest.py`.
- Resolved by B3 (below): the 4-octet frame-end field explains it (the "3 octets" above were that field, not H.264). With the frame length at FRAM size - 38 these frames close exactly before the stray `00 00 01 09`, and the frame's last datagram arrives as an orphan.

### B3: FFmpeg as the judge (2026-10-21)
FFmpeg 8.1.2 (`org.bytedeco:ffmpeg:8.1.2-1.5.14`, LGPL build, through a throwaway desktop test) decoded the frames rebuilt from the capture. With the B1 model about 700 frames had errors in their bottom macroblock rows (44-49 of 50), so the end of each frame was wrong. Decoding variants settled it: the frame's last datagram (chunk type not 0x80: `ba`/`b6`/`be 00`; a keyframe spanning two groups ends its first group on a sized chunk) carries a 4-octet field at octets 13-16 (it steps by about 4168 per frame) and its H.264 starts at 17; the FRAM size counts that field, so the H.264 length is FRAM size - 34 - 4. Every frame of the capture ends with exactly one such datagram. Under that model the Kotlin `LspVideoReassembler` output was byte-identical to the validated export and FFmpeg decoded 2,184 frames (1280x800, about 2.6 ms each on this laptop) with 0 errors and 0 corrupt frames; the only messages were for the very first frame, which arrives before the first keyframe's SPS/PPS. Implemented as `LspVideo.FRAME_END_FIELD`, subtracted inside `findFramStart` so `expectedH264Len` is the true H.264 length (FRAM_SIZE_OVERHEAD stays 34 for the FRAM record). Stream format: H.264 High profile (100), level 3.2, CABAC, 8x8 transform on, 1 reference frame, 30 fps, IDR every 30 frames, about 3.2 Mbit/s (`viddump_sps.py`).

### Live re-run with the fixed reassembler (2026-10-21, commit aea2196)
Game session on the paired kart (Start, not Pair), `MKLHC_VIDEO_DUMP=spike-captures\video`, 90 s: 25,151 datagrams, 2,700 frames (90 keyframes), same stream format. In the surviving log, no datagram dropped and no frame abandoned by the reassembler (before B1: orphan continuations and surplus on every frame); the "surplus trimmed" lines left are the last datagram's padding, one per frame. The dump fits the model: 2,699 frames with exactly one frame-end datagram, every frame ending on a non-zero octet; one datagram was lost over the radio (one sequence step of 2), which merged two frames, the case the sequence check drops. Clean stop again (kart power-off, beacon off by three WLAN scans). Not decoded live yet: that is B4.

## Hardware run: B5, the kart driven from the desktop app (keyboard and mouse), every safety cut in the log

- id: hardware-run-b5-drive-from-the-desktop-app-20261023
- type: testing_pattern
- status: active
- platform: kmp
- area: cockpit
- date: 2026-10-23

### Result (hardware, 2026-10-23 21:50-21:54, Archer T3U picked on the link screen, devourer 0bf1957, log file `mklhc-20261023-215042.log`)
The author ran the checklist `.ia-docs/plans/2026-10-23-b5-drive-checklist.md`, part A (wheels off the ground) and part B (on the floor), and reported every step as planned. This run went through Pair (the kart rejoined the game network and Driving was reached 54 s after the click, 46 s of it the radio bring-up: BB/AGC tables 31 s, RF tables 12 s, as on 2026-10-20). The log file kept the whole run (15,582 lines). From its 337 `teleopLoop(): drive command changed` lines, all `cause=input`:
- Full keyboard axes on the wire at the default 50 % max throttle: throttle 63 / -64, steering 127 / -127 (the cockpit maps -1 to -127, not -128); W+D gave 63 / 127; the brake light toggled 5 times.
- The mouse pedal produced the partial values (2..28).
- Focus loss (S1) three times while W or S was held: the next sent command was neutral 15-44 ms after `window lost the focus, releasing the held keys`.
- Closing the window: `shutdown(): requesting kart power-off`, the radio and radiod down, beacon off by three WLAN scans.
- No deadman cut and no link loss in this run: the adapter unplug (S5) and the full-forward-to-reverse switch (B3, for F6) do not show in this log.

### Second run (2026-10-23 22:25-22:35, log `mklhc-20261023-222557.log`): S5, B3 and two kart power cycles
- B3: about 18 switches from full forward (63) straight to full reverse (-64) within 0.3-1.5 s, and mouse sweeps through
  zero. The app sends exactly what is asked; how the kart answered (brake first or reverse at once) is the author's
  observation, recorded with F6.
- S5, adapter unplugged at 22:27:45.998 while W was held: libusb `ReadPipe/WritePipe failed`, radiod `code=40 RX loop
  ended unexpectedly`, exit code 3, relaunched 0.5 s later; the kart closed its RCD control channel 6.6 s after the
  unplug (22:27:52.6) and the session went to Recovering, cockpit at neutral. **Replug not recovered:** radiod's
  relaunch found no adapter (`SELECTED_DEVICE_ABSENT`, "left for the next reconnect"); the adapter was back and
  OPENABLE at 22:28:00, but nothing brought the radio up again, since radiod stayed connected and only a radiod
  reconnect retries. radiod refused the 30 Hz teleop datagrams (`TX_FRAME before INIT`, 163 error pairs in the 6 s
  until the session was lost) and then the recovery's beacon (`UPDATE_BEACON without a running beacon`); the session
  then sat in `Beaconing(GAME)` with no radio for 2 min 17 s, until the author got out with Pair, cancel and Pair again
  (a fresh bring-up, 46 s). Backlog B14, fixed the same night (the re-bring-up waits for the adapter and comes back by
  itself; not yet re-run on hardware).
- Brake and release, as the author felt them: full forward to full reverse brakes first, then turns the wheels the
  other way; releasing W or S also brakes instead of rolling, which the author found wrong (that is the brake button's
  job). The adapter unplug stopped the kart at once. Answer (same night): the brake button drops the throttle to zero
  at once (F6). A 1.5 s release ramp was tried and rejected: too noticeable, hard to drive; a release is plain zero.

### Third run (2026-10-23 23:22-23:29, log `mklhc-20261023-232209.log`, 29,492 lines): the replug with the B14 fix
- The brake felt right. The release ramp did not (removed afterwards).
- Unplug at 23:27:57.9 while driving: radiod exit 3, relaunched; at 23:27:58.5 `radioLost()` dropped the kart, the
  session went Recovering then (shown) Beaconing(GAME), `no usable adapter, waiting for one`. Plugged back: the
  repository saw it at 23:28:04.6 (attempt 2), the chip init took until 23:28:51.4 (46 s), and the game network was
  served again with the same SSID and keys. The author, seeing "Beaconing" with the kart's LED blinking, had pressed
  Pair at 23:28:45, so 5 ms later the pairing network replaced it and the kart needed the new QR. Answer: while the
  radio comes back the link now shows Recovering with "plug it back in" / "the radio is starting again, which takes up
  to a minute". The kart rejoining by itself after a replug is still to be seen on hardware.
- Kart power cycles: the kart left at 22:33:37 and was Driving again at 22:33:53 (16 s, by itself); at 22:34:18 the
  RCD channel closed and the kart left at 22:34:23, Driving again at 22:34:28. The recovery from a kart power cycle works.
- Close: power-off requested, beacon off by three WLAN scans (BSSID 16:0b:b4:ec:82:0c).

### Two identities on this PC: the Claude desktop app's file virtualization
Programs started from the Claude desktop app (its Terminal panel, the agent's shell) run inside its MSIX package, so
Windows redirects their writes under `%APPDATA%` to `%LOCALAPPDATA%\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\`.
There the pairing file holds identity A (BSSID 32:8d:c9:b5:ef:3b, channel 6); the real `%APPDATA%\MKLHC`, used by
Android Studio and any normal terminal, holds identity B (BSSID 16:0b:b4:ec:82:0c, channel 11). The kart is paired with
B since 2026-10-23 21:51. A run started from the Claude Terminal panel therefore needs Pair, which moves the kart to A.
Run the app from Android Studio or a normal terminal; inside the package, reads of `%APPDATA%\MKLHC` show the merged view
(the copy wins), which is why the agent's shell saw A.

### Noise still in the log (per datagram, about 30/s each)
`TelemetryDecoder decode(): type 3 telemetry is not specified yet` (4,735 lines), `LspVideoReassembler append(): surplus octets trimmed` (the last datagram's padding, 4,613), and `CCMP rejected a frame ... retry=true reason=PN replay detected` at warn (1,632, 3 % of the 54,327 decrypted frames: the kart retransmitting frames already received; one with retry=false).

## Out-of-range drops on hardware: how the kart's control connection comes back

- id: out-of-range-drops-on-hardware-how-the-kart-s-control-connec-20261024-050211
- type: testing_pattern
- status: active
- platform: kmp
- area: session
- date: 2026-10-24

Hardware run 2026-10-24 ~02:00, after the SYN_SENT RST fix (bugfix syn-sent-drops-a-challenge-ack-so-a-kart-holding-a-stale-con-20261024-044659): three out-of-range drops, each back to Driving by itself once the signal returned. Pattern per recovery: the kart deauths, reassociates, leases, handshakes RCD on 5202, then refuses the first SYN to 5103 once (handleRst connection refused) and accepts the retry 500 ms later from the same local port 41003. The stale-connection case of 01:19 did not recur: once the kart sent a segment on its old 41003 connection and TcpEndpoint.sendRstTo cleared it, the other two times our teardown RSTs reached the kart. So the new 'onSegmentSynSent(): unacceptable ACK while connecting' path is still unconfirmed on hardware; look for that line followed by 'connection established -> localPort=41003' in a future out-of-range log.

## Raw telemetry dump (MKLHC_TELEMETRY_DUMP) for decoding type 3

- id: raw-telemetry-dump-mklhc-telemetry-dump-for-decoding-type-3-20261106-031817
- type: testing_pattern
- status: active
- platform: kmp
- area: kart-protocol
- date: 2026-11-06

Capture: set MKLHC_TELEMETRY_DUMP to a folder (may be the video dump's folder) and run the desktop app; every datagram reaching the telemetry port (5116), type octet included and before decoding, goes to telemetry-<date>-<time>.teldump. The video dump became DatagramDump with a Stream enum (VIDEO keeps magic MKLHCVD1 and .viddump unchanged; TELEMETRY uses MKLHCTD1 and .teldump), same record layout: u64 LE receive time in microseconds, u32 LE length, payload; short last record dropped by a reader. KartSessionRepository has telemetryDatagramTap mirroring videoDatagramTap (a failing tap is logged once per loop at error and never stops the telemetry). Measured from the 2026-10-24 01:49 run log: type 3 payload is always 87 octets after the type octet, about 30 per second while streaming (14,767 in 593 s including out-of-range gaps). TelemetryDecoder no longer logs each type 3 datagram (that line was 14,767 debug lines in that run); the session logs 'telemetryLoop(): type 3 telemetry is arriving, not decoded' once per telemetry loop. Offline helper (git-ignored): .ia-docs/telemetry-analysis/teldump_summary.py (rates per type, per-octet table, candidate LE u16/s16/u32/f32 fields, CSV export); capture sequence in .ia-docs/plans/2026-11-06-b8-telemetry-capture.md. Status: unit tests and gate green; no dump captured on hardware yet, so the type 3 layout is still unknown. Dumps stay local (*.teldump git-ignored).

### Files
- app/src/desktopMain/kotlin/com/mklhc/app/desktop/DatagramDump.kt
- app/src/desktopMain/kotlin/com/mklhc/app/desktop/DesktopKartRuntime.kt
- core/data/src/commonMain/kotlin/com/mklhc/core/data/session/KartSessionRepository.kt
- core/kart-protocol/src/commonMain/kotlin/com/mklhc/core/kart_protocol/telemetry/TelemetryDecoder.kt
