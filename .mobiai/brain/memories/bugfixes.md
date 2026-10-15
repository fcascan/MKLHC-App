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
