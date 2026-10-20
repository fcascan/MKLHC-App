# Bugfixes

<!--
Bugfixes and workarounds worth remembering for this project.
Append entries with: mobiai brain save bugfix (coming in Phase 2).
Mark temporary workarounds as status: temporary so the agent does not
treat them as permanent decisions.
-->

## Gradle daemon pinned to JDK 21 (machine JAVA_HOME is JDK 17)

- id: gradle-daemon-pinned-to-jdk-21-machine-java-home-is-jdk-17-20261015-005128
- type: bug_fix
- status: active
- date: 2026-10-15

### Workaround
The dev machine's JAVA_HOME is JDK 17 (corretto-17), but build-logic and all modules target JDK 21. Without intervention the Gradle daemon fails: "Dependency requires at least JVM runtime version 21. This build uses a Java 17 JVM." Fixed by committing gradle/gradle-daemon-jvm.properties (generated via `./gradlew updateDaemonJvm --jvm-version=21`), which makes Gradle select a local JDK 21 (a Zulu 21 in ~/.gradle/jdks) or auto-provision one via foojay for the daemon and toolchains.
### Why it is here, not a root cause
The real fix would be to set JAVA_HOME to a JDK 21, but that is the author's machine config, not the repo's. Pinning the daemon JVM in-repo makes the build reproducible regardless of the launcher JDK. Also note: compileSdk must be 36 (not 35) because kermit 2.2.0 aar-metadata requires minCompileSdk 36; targetSdk stays 35.

## Phase 1 deferred follow-ups (per-TID replay, radiod backpressure, READY flow, LP2P CSPRNG, resource-wiring convention, perf)

- id: phase-1-deferred-follow-ups-per-tid-replay-radiod-backpressu-20261015-033258
- type: platform_workaround
- status: temporary
- date: 2026-10-15
- review_after: 2026-12-15

### Follow-ups deferred from the Phase 1 code-quality review (Opus), to address in the hardware spike (Task 1.8) / integration / later phases. None blocks the non-hardware Phase 1.
1. CCMP PN replay window is per-instance (global), not per-TID. Fine while the link is single-TID best-effort (WMM-less), but multiple ACs/TIDs would get valid frames dropped as replays. Key the window by TID once real TID usage is known. File: core/wifi-ap crypto/Ccmp.kt.
2. radiod RX->socket backpressure: onPacket emits with a blocking socket write from devourer's RX thread; a slow app consumer stalls the radio (missed ACKs/USB stall). Mirror the TX design with a bounded drop-oldest RX queue + dedicated writer thread. File: radiod/src/Radio.cpp.
3. RadioLink.events is a replay=0 SharedFlow; a late collector can miss READY/ERROR. Model readiness as StateFlow<Ready?>/CompletableDeferred or give events a small replay for terminal events, when the orchestrator is written. File: core/radio DesktopRadioLink.kt.
4. LP2P needs a caller-supplied CSPRNG; add CryptoPrimitives.secureRandomBytes(n) (java.security.SecureRandom on jvmShared) + a production factory before LP2P is wired into a real beacon (weak IV randomness breaks GCM). File: core/wifi-ap crypto.
5. Generalize the Android resource wiring (jvmSharedMain/resources + commonTest/resources srcDirs, currently hand-added in core/wifi-ap/build.gradle.kts) into the mklhc.kmp.library convention plugin, guarded by androidEnabled + dir existence - else the next module with a shared resource silently fails to package it on Android. File: build-logic.
6. CryptoPrimitives.aesEcbEncryptBlock does a per-block Cipher.getInstance; expose a whole-buffer CTR + CBC-MAC (single Cipher init) before RF data rates lean on it. File: core/wifi-ap crypto.
7. radiod: per-frame TxFrame.retryLimit is ignored (device-wide only); clean shutdown on Windows needs socket-close/SHUTDOWN, not Process.destroy (hard kill leaves the chip beaconing) - define stop ordering in the orchestrator.
### Also open from implementers (hardware-spike tunables, not defects): exact beacon rate set / interval / capability / DTIM / ERP; DHCP router(3)/DNS(6) options + lease time; AP WMM/EDCA + power-save buffering + class-3 deauth; Radio.cpp devourer API assumptions; devourer submodule add + native build (needs vcpkg libusb + Zadig WinUSB).

## Phase 3 TCP follow-ups (flow control/persist timer, OOO buffering, TIME_WAIT reopen guard, composition layer, hardware gate)

- id: phase-3-tcp-follow-ups-flow-control-persist-timer-ooo-buffer-20261015-162554
- type: platform_workaround
- status: temporary
- date: 2026-10-15
- review_after: 2026-12-15

### TCP (core:netstack) follow-ups deferred from the Phase 3 reviews, for later phases / the hardware spike. None blocks Phase 3 non-hardware. Tests: 101 green.
1. No zero-window persist/probe timer: a pure window-update ACK now re-drives the sender (fixed), but if the peer never sends one, pending data waits. Add a persist timer when real flow control lands.
2. Advertised 16 KiB window is NOT backpressure-enforced and the recv channel is unbounded: fine for the low-rate RCD control channel, not for bulk. A real bounded buffer requires real flow control (can't just cap a trySend channel after rcvNxt advanced = corruption). Implement before any bulk TCP use.
3. Out-of-order inbound segments are DROPPED (go-back-N; sender retransmits). If the real radio link reorders a lot, add receive buffering/selective handling. Confirm at the spike.
4. TIME_WAIT new-SYN reopen does not yet compare the incoming SYN seq vs the old rcvNxt (RFC 9293 old-duplicate guard); reopens on any fresh same-peer SYN with a listener. Fine for single-peer kart; refine later.
5. RST acceptance widened to in-window (fixed). close() in SYN_RECEIVED sends RST not FIN (minor RFC deviation, left). Missing-MSS defaults to 1460 not RFC 536 (deliberate: kart link MTU 1500). No RST on an unacceptable ACK in SYN_SENT (minor interop, left).
6. NEXT NON-HARDWARE PIECE (not a defect): the composition/integration layer is not built yet - a FrameLink-backed adapter that demuxes inbound IPv4 (proto 6 TCP, 17 UDP, 0x0806 ARP) to TcpEndpoint/UdpEndpoint/Arp/DhcpServer, plus UdpEndpoint (design section 9). Unit-testable without hardware; natural step before or alongside Phase 4 kart protocol.
### Hardware gate (deferred like Task 1.8): the kart gets its DHCP lease and completes a TCP handshake to app port 5201 (pairing) / 5202 (session); real RTT/loss/reorder, keepalive need, window adequacy, MSS fit.

## Netstack composition contracts + follow-ups (FrameLink cold-flow, node-scoped callers, single-dispatcher, TcpEndpoint.close, wifi-ap adapter requirements)

- id: netstack-composition-contracts-follow-ups-framelink-cold-flo-20261015-173012
- type: platform_workaround
- status: temporary
- date: 2026-10-15
- review_after: 2026-12-15

### Netstack composition layer (NetstackNode) - contracts and follow-ups for the wifi-ap FrameLink adapter and the session orchestrator. Non-blocking; netstack composition is done (142 tests green).
CONTRACTS the next pieces MUST honour:
1. FrameLink.inbound MUST be a buffered cold flow (Channel.receiveAsFlow, or a SharedFlow with replay/extraBuffer + suspending/buffering overflow). A replay-less hot SharedFlow drops frames emitted before NetstackNode.start() subscribes -> silently breaks association/DHCP. Documented in FrameLink.kt and NetstackNode.start() KDoc.
2. The orchestrator MUST await TcpEndpoint accept()/TcpConnection read()/connect() on a scope that is a CHILD of the node scope (cancelled with the node). On stop(), the TCP actors are cancelled but listeners/recv/connectResult are NOT completed, so a caller on an independent scope HANGS forever.
3. The node is single-thread/dispatcher only: construct it on a single-threaded dispatcher (limitedParallelism(1)) and call bindUdp/start/stop and cancel the injected scope FROM that dispatcher. Off-thread teardown is damage-limited (lock-free copy-on-write UDP map + STOPPED-gated dispatch) but start()'s check-then-set state is still non-atomic.
FOLLOW-UPS (optional, later):
- Add a real TcpEndpoint.close() that closes listeners and aborts connections (waking blocked accept/read/connect with a cause), wired into teardown - lets the orchestrator use independent-scope callers.
- If atomicfu is added to the build, replace the documented single-dispatcher precondition with a real lock/CAS on state + the UDP map (closes the start() check-then-set residual).
- Not implemented (trivial to add if the spike needs them): ICMP (ping of the AP gets no reply), directed-broadcast acceptance (169.254.98.127 - only 255.255.255.255 is accepted today), IP fragmentation (DF always set, MSS 1460).
- FrameLinkTcpLink.inbound is Channel.UNLIMITED (bounded in practice by TCP windows; video rides UDP). Revisit only if bulk data runs over TCP.
WIFI-AP FrameLink adapter must: wrap/unwrap 802.11 + LLC/SNAP (SNAP EtherType), apply pairwise CCMP, address every send to the single associated station's MAC (incl. IP-broadcast packets as unicast 802.11, since there is no GTK), pass up both unicast-to-BSSID and broadcast-DA inbound frames, and provide NetstackConfig.ourMac = the BSSID. Call node.dhcpServer.reset() on disassociation.

## Phase 4 kart-protocol done; deferred items (IMU/video/LVNI spike unknowns, release notices) + next piece: session orchestrator

- id: phase-4-kart-protocol-done-deferred-items-imu-video-lvni-spi-20261015-201235
- type: platform_workaround
- status: temporary
- date: 2026-10-15
- review_after: 2026-12-15

### Phase 4 (kart-protocol + core:crypto) done: 236 kart-protocol + 10 crypto tests green, wifi-ap still 97. Follow-ups / deferred items, none blocking.
HARDWARE-SPIKE / REA confirmation items (documented in code KDocs, NOT bugs - no reference impl exists):
- IMU (telemetry type 2): offset base (payload-after-type vs datagram-start), endianness (assumed LE), NNN vs NNN+1 sample count (ImuDecoder.SAMPLE_COUNT_BIAS=0), flag bit positions IGA??NNN, accel/gyro scales, the +9.81 gravity-on-Z sign, integrator units, quaternion axis order. Telemetry type 3 unknown.
- Video: FRAM offsets 13 vs 43, L2 block, 8-byte trailer, FRAM_SIZE_OVERHEAD=34, 2 MiB cap. LVNI frame-id source and flags semantics are an OPEN UNKNOWN (Lvni.encode lays out bytes; the caller chooses ids). LSP control (PI) and keyframe-request semantics unknown. SPS/PPS caching is the later video-decoder task.
- Fuji event channel (0x103): service id and whether the kart wants non-empty replies is unconfirmed (default empty). connection_info timestamp is caller-supplied (unix seconds). readApplicationData layout matches mk-live's source (verified in-session) but not cross-run.
RELEASE items:
- THIRD_PARTY_NOTICES.md lists OpenKart + mk-live but should add the full BSD-3/MIT license texts (and note the community-wiki specs) before a public release (Phase 8).
NEXT NON-HARDWARE PIECE - the session orchestrator: :core:data KartSessionRepository + DataStore PairingStore that wire radio -> wifi-ap AP -> netstack NetstackNode -> kart-protocol (RCD server on 5201/5202, FujiControlClient on 5103, FujiPairingClient on 5106, FujiEventChannel on 5107, teleop UDP 5102, telemetry/video UDP) and drive the LinkState machine (pairing + session sequences). Must honour: inject a secure challenge (now default via crypto.secureRandomBytes), run on a single-threaded dispatcher, await TCP accept/read/connect on a node-scoped child, supply a buffered-cold-flow FrameLink (wifi-ap adapter), set connection_info ports (telemetry 5116/control 5032/video 5016 per spec), call dhcpServer.reset() on disassociation. The wifi-ap FrameLink adapter (802.11+LLC/SNAP + CCMP + single-station MAC) and feature UIs (cockpit/pairing/link, MVVM against a fake repository) are the other non-hardware tracks. Task 1.8 GO/NO-GO still needs the adapter + kart.

## Orchestration done (KartSessionRepository); backbone tested end-to-end; next: wifi-ap FrameLink adapter + UI features

- id: orchestration-done-kartsessionrepository-backbone-tested-end-20261015-222447
- type: platform_workaround
- status: temporary
- date: 2026-10-15
- review_after: 2026-12-15

### Orchestration layer DONE (KartSessionRepository + :core:data foundations): 94 data + 18 domain tests green. The entire radio-agnostic backbone (RadioLink -> wifi-ap AP -> netstack NetstackNode -> kart-protocol RCD/Fuji/teleop/telemetry/video -> KartSessionRepository) is built and tested end-to-end over the in-memory FrameLink with a FakeKart simulator, reaching Driving. Project total ~674 tests.
DEFERRED (need the real adapter / hardware / UI), none blocking:
- The REAL wifi-ap FrameLink adapter is the key remaining non-UI piece: a KartRadioAp implementation over RadioLink that runs the ApStateMachine + CCMP + LLC/SNAP, exposes frameLink.inbound as a BUFFERED COLD FLOW, drives adapterState/associatedStation truthfully, and beacon(GAME)/beacon(PAIRING, PairingNetwork(ssid,seed,channel)) actually switch the advertised network (AP derives pairing PSK = SHA-256(seed)). Android radio (Phase 2: radiod NDK build + USB fd handoff) is part of this on Android.
- Adapter unplug mid-session does not return to NoAdapter/AwaitingUsbPermission (awaitAdapterReady observes adapterState only at startup); needs the real adapter emitting continuous state + a mid-session observer.
- KartLeased is emitted on RCD accept, not from a real DHCP lease; the FakeKart assumes the lease so the DHCP-lease path to a real kart is unexercised. dhcpServer.reset() is now called on disassociation.
- The 5s post-handshake connect window now uses one shared deadline, but the real timing margin is unverified against hardware.
- LinkState has no idle/stopped rung; cancelPairing() uses Failed("pairing cancelled"). Consider adding an Idle state for the UI.
- I1 deadman uses an injectable TimeSource (production = TimeSource.Monotonic; tests inject the scheduler's) because Monotonic does not advance under runTest virtual time.
- setCaptureEnabled is a stored flag only (no pcap yet); keystore-wrapping the stored master secret; THIRD_PARTY_NOTICES full license texts - all before release (Phase 8).
NEXT NON-HARDWARE PIECE: the UI features (Phase 5-7) - :core:design-system + :feature:link/pairing/cockpit screens (MVVM) built against KartSessionRepository (or a fake of it): link/adapter status, the pairing QR screen, the cockpit (video surface, touch drive controls feeding setDriveCommand, telemetry HUD). The repository's flows/commands are ready. The wifi-ap FrameLink adapter is the other non-UI track (partly testable, finished on hardware).

## UI layer done (Compose MVVM); whole app tested in software; next: :app wiring + wifi-ap FrameLink adapter + hardware

- id: ui-layer-done-compose-mvvm-whole-app-tested-in-software-next-20261016-013257
- type: platform_workaround
- status: temporary
- date: 2026-10-16
- review_after: 2026-12-15

### UI layer DONE (Compose Multiplatform, MVVM). ~370 UI tests green: core:presentation 11 (UiText), core:design-system 41 (dark cockpit AppTheme, Canvas HUD widgets, touch drive controls with release=neutral), core:video 15 (VideoSurface expect/actual: Android MediaCodec device-UNVERIFIED, desktop placeholder), feature:link 27, feature:pairing 42 (QR via ZXing byte-mode ECC M, decode-round-trip tested), feature:cockpit 59 (IMU->HUD conflated ~20Hz, controls->setDriveCommand raw, NEUTRAL on release/pause/dispose/leave-Driving + 100ms keep-alive under the 250ms deadman). core:data extracted a KartSession interface (KartSessionRepository implements it) + FakeKartSession. Project total ~883 tests across the whole software stack.
STATE: the ENTIRE app is built and tested in software end-to-end with fakes (radio seam -> wifi-ap AP -> netstack -> kart-protocol -> KartSessionRepository -> UI). What remains is NOT software logic:
WHAT'S LEFT (deferred):
1. :app WIRING (next non-hardware piece, mostly): Koin startup binding KartSession (to the real KartSessionRepository, which needs a real KartRadioAp) + include linkFeatureModule/pairingFeatureModule/cockpitFeatureModule; the navigation graph using the Root callbacks (LinkRoot onNavigateToPairing/onNavigateToCockpit, PairingRoot onPaired/onCancel, CockpitRoot onNavigateBack); AppTheme at the root; Android USB-permission UI + landscape; desktop needs kotlinx-coroutines-swing for Dispatchers.Main (in the catalog). Demoable on desktop now by binding a FakeKartSession; the real session needs the wifi-ap adapter. Also: KartInfoUi has hardcoded "Kart N"/firmware strings to localize; exclude datastore/ from Android auto-backup.
2. The REAL wifi-ap FrameLink adapter (KartRadioAp impl over RadioLink: ApStateMachine + CCMP + LLC/SNAP + beacon GAME/PairingNetwork switching, frameLink.inbound a buffered cold flow). Partly testable, finished on hardware. Android radio (Phase 2: radiod NDK + USB fd handoff) is part of this.
3. HARDWARE: Task 1.8 GO/NO-GO (needs the Realtek adapter + kart); on-device MediaCodec verification; desktop H.264 decoder (separate spike); IMU->HUD mapping + all the protocol unknowns confirmed via REA captures.

## radiod: a fatal ERROR can be lost to a Windows socket reset (first real-binary spike run)

- id: radiod-a-fatal-error-can-be-lost-to-a-windows-socket-reset-f-20261020-182710
- type: bug_fix
- status: active
- platform: kmp
- area: radio
- date: 2026-10-20

### Problem
First end-to-end spike run against the real radiod on Windows (2026-10-20, adapter not yet bound to WinUSB): OPEN_DEVICE fails fatally, radiod writes ERROR (code 10, "not bound to WinUSB/libusb") and exits with code 3. On the first launch the app read "Connection reset" and never received the ERROR; the supervisor relaunched radiod and only the second attempt delivered it. Likely cause: the app sends OPEN_DEVICE and INIT back to back, radiod exits without reading INIT, and closing a socket with unread bytes is an abortive close (RST) on Windows, which can discard the ERROR already in flight.

### Fix (applied and verified 2026-10-20)
radiod now closes the socket gracefully on every exit path (closeSocketGracefully in radiod/src/main.cpp): shutdown(SD_SEND / SHUT_WR), then discard incoming bytes until EOF or 1 s (kCloseDrainMs), then close. Verified 3 out of 3 runs with an absent VID:PID (0bda:c812): the ERROR arrives on the first launch, the connection ends with a clean EOF instead of a reset, and radiod is not relaunched.
Still open (optional): the supervisor schedules a relaunch after exit code 3 even for configuration errors (device missing or not bound); harmless today because the session stops before the backoff fires.

### Validated in the same run
radiod.exe found at its default path, AF_UNIX socket on Windows, token handshake, devourer version embedded (357f04520ed4), structured ERROR decoding, and a clean shutdown (radiod killed, temp socket dir removed). radiod builds clean with VS Build Tools 2026 and libusb 1.0.30 from a standalone vcpkg (VCPKG_ROOT=C:\vcpkg); devourer submodule pinned at 357f045.

### Files
- radiod/src/main.cpp
- core/radio/src/desktopMain/kotlin/com/mklhc/core/radio/RadiodSupervisor.kt

## DHCP replies must be broadcast while the client has no address (udhcpd rule)

- id: dhcp-replies-must-be-broadcast-while-the-client-has-no-addre-20261020-195342
- type: bug_fix
- status: active
- platform: kmp
- area: radio
- date: 2026-10-20

### Problem
On hardware (2026-10-20) the kart associated and sent its DHCP DISCOVER with the broadcast flag clear (flags 0x0000). Following RFC 2131 section 4.1 the server unicast the OFFER to the offered address (169.254.98.66). The kart never sent a REQUEST: it repeated the DISCOVER about ten times, sometimes sent a DECLINE with zero addresses, then deauthenticated and started over. The OFFER was well formed (IP and UDP checksums correct, options 53/54/51/1) and reached the kart as a unicast 802.11 frame.

### Fix (applied and verified)
Do what BusyBox udhcpd does (OpenKart runs it for the kart): send every reply to 255.255.255.255 while the client has no address yet (the reply's ciaddr is 0.0.0.0), whatever the broadcast flag, plus every NAK; unicast to the leased address only for a renewal (ciaddr set). At the 802.11 level the frame stays unicast to the station, since there is no group key. Applied in SpikeRunner.sendDhcpReply and NetstackNode.dispatchDhcp, documented in DhcpServer, and NetstackNodeTest now pins a flag-less DISCOVER and REQUEST getting a broadcast OFFER and ACK. On hardware the kart then sent its REQUEST at once and took the lease.

### Files
- core/netstack/src/commonMain/kotlin/com/mklhc/core/netstack/NetstackNode.kt
- core/netstack/src/commonMain/kotlin/com/mklhc/core/netstack/DhcpServer.kt
- app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikeRunner.kt

## Spike console QR unreadable under Gradle on Windows (cp1252)

- id: spike-console-qr-unreadable-under-gradle-on-windows-cp1252-20261020-195343
- type: bug_fix
- status: active
- platform: kmp
- area: radio
- date: 2026-10-20

### Problem
Running `./gradlew :app:runSpike` in a Windows console (PowerShell 5.1 in the app's Terminal panel), the console QR code printed as `?` characters and could not be scanned; other non-ASCII text was garbled too (an em dash in a devourer log line showed as `u` with an accent). The spike writes UTF-8, but the output passes through the Gradle client, which prints with the Windows ANSI code page (cp1252), where the half-block characters do not exist.

### Workaround used for the GO run
A local page rendered the same 62-byte payload: qrcode-generator (byte mode, error correction M) drew it on a canvas and jsQR decoded the canvas back to check it byte for byte. It was served with `python -m http.server --bind 127.0.0.1` from a folder holding only that page, because the app's browser pane shows file:// pages as static snapshots without JavaScript. The page lives in the git-ignored .ia-docs/qr/ and holds a one-run pairing seed only.

### Fix (applied)
The spike now writes the same code as `spike-<date>-<time>-qr.svg` next to the pcap (renderQrAsSvg in SpikePairing.kt: dark modules as path rectangles on a white background, 4-module quiet zone, 10 px per module, no script and no network) and prints its path as a file URI (plain ASCII, so it survives the same code page) under the payload in both pairing printouts, whether or not `--no-qr` is set. The console drawing stays for UTF-8 terminals. The image holds the run's seed, so the session deletes it first thing when the run starts stopping (before the radio teardown, which can take seconds) and again in the outer finally for setup failures; a failed delete (a viewer holding the file on Windows) prints a warning with the path. A hard kill leaves it, so `spike-*-qr.svg` is git-ignored too. SpikePairingTest rasterizes the SVG text of a fixed payload and scans it back with ZXing's QRCodeReader to the exact octets; SpikeSessionTest checks the file, the printed path, `--no-qr` and the deletion. Setting `chcp 65001` and a UTF-8 stdout for the Gradle client may also work but depends on the user's console, so it was not pursued.

### Files
- app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikePairing.kt
- app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikeSession.kt
- docs/HARDWARE_SETUP.md
