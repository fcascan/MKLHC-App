# Decisions

<!--
Architecture decisions specific to this project.
Append entries with: mobiai brain save decision (coming in Phase 2).
Each entry should record: title, status (active|deprecated), platform,
area, date, decision, reason, files.
-->

## Host the kart LP2P network from the app via a userspace USB driver

- id: host-the-kart-lp2p-network-from-the-app-via-a-userspace-usb-20261014-223441
- type: architecture_decision
- status: active
- platform: kmp
- area: radio
- date: 2026-10-14

### Decision
The app hosts the Fuji kart's LP2P access point itself on a stock, non-rooted phone using a commercial USB Wi-Fi adapter driven by the userspace driver devourer (OpenIPC). No extra computer, no root, no flashing.
### Reason
The kart only joins an LP2P network (hidden SSID, vendor IEs OUI 00:22:AA, static CCMP key, no WPA handshake) that phone hotspots and Wi-Fi Direct cannot create. devourer already demonstrates userspace AP mode with hardware beacon and hardware auto-ACK, and PixelPilot shows it runs on Android without root via the UsbManager file descriptor.
### Alternatives considered
- ESP32-S3 dongle with custom firmware: requires flashing hardware.
- Pocket Linux dongle (SBC in USB gadget mode + AR9271 + patched hostapd): extra SBC, power-hungry on OTG.
- External Linux base station running OpenKart: not attached to the phone.
- Rooted Android with kernel driver: root and per-phone kernel modules.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Targets: Android plus Desktop JVM with protocol logic in commonMain

- id: targets-android-plus-desktop-jvm-with-protocol-logic-in-comm-20261014-223441
- type: architecture_decision
- status: active
- platform: kmp
- area: architecture
- date: 2026-10-14

### Decision
Ship Android (product) and Desktop JVM (Windows/Linux, dev rig and power users). All 802.11, LP2P, CCMP, netstack and kart-protocol logic lives in commonMain; platform code only for USB, radiod launch, video decoding and entry points.
### Reason
Desktop iterates faster (no APK install, Wireshark alongside) and uses the same adapter through devourer + libusb. iOS cannot run userspace USB drivers.
### Alternatives considered
- Android only: loses the desktop rig.
- Android + iOS via an external base station: two link implementations, much more scope.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## v1 scope: pairing, driving, live video, telemetry HUD for a single kart

- id: v1-scope-pairing-driving-live-video-telemetry-hud-for-a-sing-20261014-223442
- type: architecture_decision
- status: active
- platform: kmp
- area: architecture
- date: 2026-10-14

### Decision
v1 = QR pairing, driving (throttle, steering, brake light, 30 Hz, deadman), live camera video, telemetry HUD (battery, signal, IMU horizon, G-meter). Single kart. v2 roadmap = video recording, physical gamepad/keyboard, sounds (horn, engine, reverse beeper), kart motion and on-screen animations, AR detection of track elements.
### Reason
Smallest product that delivers the core remote-driving experience; the author explicitly deferred the rest.
### Alternatives considered
- Recording and gamepad in v1: deferred by the author.
- Multi-kart support: not needed by the author.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Reference adapter RTL8812AU (ALFA AWUS036ACH) with VID:PID allow-list

- id: reference-adapter-rtl8812au-alfa-awus036ach-with-vid-pid-all-20261014-223442
- type: architecture_decision
- status: deprecated
- platform: kmp
- area: radio
- date: 2026-10-14

### Decision
The reference adapter is the ALFA AWUS036ACH (RTL8812AU); MT7612U (ALFA AWUS036ACM) is secondary. Supported adapters are identified only by a USB VID:PID allow-list.
### Reason
RTL8812AU is the most validated chip in devourer and PixelPilot. Product names hide chip revisions (look-alike 8812BU/8811CU are unsupported), so VID:PID is authoritative.
### Alternatives considered
- AIC8800D80: FullMAC, no raw frame access, unsupported by devourer.
- MT7922: PCIe card, cannot attach to a phone.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## devourer isolated in a GPL-2.0-only radiod helper process; app stays AGPL-3.0

- id: devourer-isolated-in-a-gpl-2-0-only-radiod-helper-process-ap-20261014-223443
- type: architecture_decision
- status: active
- platform: kmp
- area: radiod
- date: 2026-10-14

### Decision
The app remains AGPL-3.0. devourer (GPL-2.0-only) is linked only into radiod, a separate C++ helper process with its own GPL-2.0 LICENSE, which exchanges raw 802.11 frames and simple commands with the app over a local socket. Android passes the USB fd via LocalSocket.setFileDescriptorsForSend; desktop radiod opens the adapter by VID:PID.
### Reason
GPL-2.0-only is incompatible with AGPL-3.0 and, per the FSF, with the Apache-2.0 libraries every Kotlin/Android app bundles. An arm's-length socket interface between separate programs keeps both licenses clean and adds crash isolation.
### Alternatives considered
- GPL-2.0-only app linking devourer in-process: conflicts with bundled Apache-2.0 libraries.
- Clean-room userspace driver under AGPL: estimated 3-5 months.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md
- LICENSE

## REA analyzes decrypted pcaps exported by our own AP

- id: rea-analyzes-decrypted-pcaps-exported-by-our-own-ap-20261014-223443
- type: architecture_decision
- status: active
- platform: kmp
- area: integration
- date: 2026-10-14

### Decision
REA is used on decrypted .pcap captures that MKLHC-App exports from its own access point, via WSL2 Ubuntu with TShark 4.x, to close protocol unknowns (LSP video control, keyframe requests, time sync, event channel, telemetry type 3). Findings go to .ia-docs/protocol/ and to Brain.
### Reason
Clean interoperability work on the author's own hardware; most of the protocol is already documented by OpenKart, mk-live and a community protocol wiki.
### Alternatives considered
- Kart firmware analysis: needs a flash dump.
- REA only on demand: loses systematic gap closing.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Userspace Kotlin IP stack instead of VpnService/TUN

- id: userspace-kotlin-ip-stack-instead-of-vpnservice-tun-20261014-223444
- type: architecture_decision
- status: active
- platform: kmp
- area: netstack
- date: 2026-10-14

### Decision
core:netstack implements ARP, a single-lease DHCP server, IPv4, UDP and a minimal single-peer TCP in pure Kotlin, on top of the software AP. The API is shaped so an OS-backed implementation (Android VpnService) can replace it if needed.
### Reason
Identical code on Android and Desktop, no VPN consent dialog or VPN-slot conflict, no admin rights or TUN/Wintun drivers on desktop, compatible with Google Play policy, fully unit-testable.
### Alternatives considered
- Android VpnService + Linux TUN + Windows Wintun: real kernel TCP but three platform plumbings, consent dialog, Play VPN policy, admin on desktop. Kept as fallback.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Presentation and libraries: Compose MP, MVVM, Koin, Kermit, DataStore

- id: presentation-and-libraries-compose-mp-mvvm-koin-kermit-datas-20261014-223445
- type: architecture_decision
- status: active
- platform: kmp
- area: architecture
- date: 2026-10-14

### Decision
Compose Multiplatform UI; MVVM with a single StateFlow and onAction per screen; Koin for DI; Kermit for logging using TAG = class name and "function(): message -> key=value"; DataStore for persistence; type-safe Compose Navigation; feature-layered modules with build-logic convention plugins and a version catalog.
### Reason
Matches the author's conventions; the cockpit is continuous state, simpler in MVVM. Kermit is multiplatform.
### Alternatives considered
- MVI: more ceremony for continuous state.
- Timber: Android-only, would need a facade for desktop (the author chose Kermit).

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Never name the kart maker, console or game; MKLHC is never expanded

- id: never-name-the-kart-maker-console-or-game-mklhc-is-never-exp-20261014-223445
- type: architecture_decision
- status: active
- platform: shared
- area: workflow
- date: 2026-10-14

### Decision
No artifact (docs, code, comments, commits, UI, store listing) names the kart's manufacturer, its host console or its host game. Hardware is described as in OpenKart: "802.11 RC toy karts, codename Fuji, manufactured and distributed by a well-known Japanese entertainment and technology company". The project name MKLHC is kept but never expanded or explained.
### Reason
Explicit requirement of the author; same trademark-neutral posture as OpenKart.
### Alternatives considered
- Renaming the project to a neutral name: the author keeps MKLHC.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Always attribute the origin of borrowed code

- id: always-attribute-the-origin-of-borrowed-code-20261014-223447
- type: architecture_decision
- status: active
- platform: shared
- area: workflow
- date: 2026-10-14

### Decision
Every ported or adapted file carries a header "Ported from|Adapted from <project> <URL>, <path> @ <commit> (<SPDX>)"; single ported functions get a function comment; golden vectors cite their generator and source; THIRD_PARTY_NOTICES.md lists all origins; commit messages name origins; reference-only sources are cited with a "no code copied" note.
### Reason
Explicit requirement of the author; license compliance across AGPL-3.0, GPL-2.0, BSD-3 and MIT.
### Alternatives considered
- Notices file only: insufficient traceability for the author.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Phase gate: stop for build and tests, complete commit message, author commits

- id: phase-gate-stop-for-build-and-tests-complete-commit-message-20261014-223448
- type: architecture_decision
- status: active
- platform: shared
- area: workflow
- date: 2026-10-14

### Decision
After every phase or stage: stop; hand over the change list, Android and Desktop build commands, the full test command (all earlier tests green), the hardware evidence plan if any, and a complete commit message (Conventional Commits subject + body with code origins) without Co-Authored-By or any AI/tool mention. The author builds, tests, commits and pushes before the next phase starts. MobiAI git automation only on request.
### Reason
The author verifies every step and keeps a human-authored history.
### Alternatives considered
- MobiAI create-pr automation per phase: the author prefers manual commits.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Reference adapter: Realtek Jaguar2/3 (RTL8812CU primary) with VID:PID allow-list

- id: reference-adapter-realtek-jaguar2-3-rtl8812cu-primary-with-v-20261014-223834
- type: architecture_decision
- status: active
- platform: kmp
- area: radio
- date: 2026-10-14

### Decision
Reference adapters are Realtek Jaguar2/3: primary RTL8812CU (LB-LINK WDN1300H, 0bda:c812); widely sold alternative RTL8822BU (TP-Link Archer T3U, 2357:012d); low-power option RTL8811CU (COMFAST CF-811AC, 0bda:c811). RTL8812AU (0bda:8812) is allowed but not recommended. Supported adapters are identified only by a USB VID:PID allow-list.
### Reason
devourer's AP harnesses are bench-validated on Jaguar2/3 adapters (harness default 0bda:c812). On Jaguar1 (RTL8812AU) devourer marks every injected TX descriptor as broadcast, so unicast frames to the kart get no hardware ACK-wait and no retries; Jaguar2/3 set it from addr1's group bit, so unicast frames are retried by hardware. Supersedes the earlier RTL8812AU-primary entry.
### Alternatives considered
- RTL8812AU primary (ALFA AWUS036ACH): no unicast retries on Jaguar1.
- MT7612U: opt-in build flag and external firmware files.
- AIC8800D80: FullMAC, unsupported. MT7922: PCIe.

### Files
- .ia-docs/designs/2026-10-14-mklhc-app-design.md

## Phase 0 toolchain version pins (KMP, late-2026)

- id: phase-0-toolchain-version-pins-kmp-late-2026-20261014-231119
- type: architecture_decision
- status: active
- platform: kmp
- area: build
- date: 2026-10-14

### Decision
Phase 0 toolchain pins, confirmed available on Maven Central / Google Maven / Gradle services on 2026-10-14: Gradle 8.14.3, Kotlin 2.2.20, AGP 8.13.0, Compose Multiplatform 1.9.3, coroutines 1.10.2, Kermit 2.2.0, ZXing core 3.5.4, JUnit BOM 5.14.4, AssertK 0.28.1, androidx activity-compose 1.11.0, JetBrains lifecycle-viewmodel-compose 2.9.4, foojay-resolver-convention 1.0.0, JDK 21, Android minSdk 26 / compileSdk 35.
### Reason
Deliberately not bleeding-edge: Kotlin 2.2.x + AGP 8.13 + Compose MP 1.9.x is a mutually compatible, released KMP set (avoids Kotlin 2.3/AGP 9 churn). Koin, DataStore, Turbine, navigation-compose versions to be confirmed at first use.
### Alternatives considered
- Kotlin 2.3.x / AGP 9.x: newest but less settled for a from-scratch KMP build.

### Files
- gradle/libs.versions.toml
- .ia-docs/plans/2026-10-14-mklhc-app-implementation-plan.md

## Android target required by default in build-logic (opt out via -Pmklhc.android.enabled=false)

- id: android-target-required-by-default-in-build-logic-opt-out-vi-20261015-005129
- type: architecture_decision
- status: active
- platform: kmp
- area: build
- date: 2026-10-15

### Decision
In build-logic, the Android target of KMP modules is ENABLED by default. isAndroidTargetEnabled() returns true unless -Pmklhc.android.enabled=false is passed (which logs a one-time warning and configures desktop-only). :app applies com.android.application unconditionally.
### Reason
An earlier version auto-skipped the Android target when no SDK was detected, which meant a green `check` did not guarantee Android compiled (a reproducibility trap flagged in the Phase 0 code-quality review). Fail-fast on a missing SDK is safer: "green" now means Android compiled.
### Alternatives considered
- Default-skip-when-no-SDK: silently narrowed the build per machine/CI; rejected.

### Files
- build-logic/src/main/kotlin/com/mklhc/buildlogic/BuildLogicExtensions.kt

## Adapter in hand: TP-Link Archer T3U (2357:012d, 8822B die) verified compatible; RTL8811CU downgraded

- id: adapter-in-hand-tp-link-archer-t3u-2357-012d-8822b-die-verif-20261020-181043
- type: architecture_decision
- status: active
- platform: kmp
- area: radio
- date: 2026-10-20

### Decision
The development adapter is a TP-Link Archer T3U, USB 2357:012D REV 0210, and it is accepted for the radio spike and Phase 2. The Windows Realtek INF installs it as tplink_RTL8812bu; devourer detects it by REG_SYS_CFG2 chip-id 0x0a as the 8822B die (RtlJaguar2Device, variant C8822B), so RTL8812BU and RTL8822BU name the same silicon here. Run the spike with `--vid 2357 --pid 012d` (its default is 0bda:c812). RTL8811CU (C8821C) is downgraded from low-power option until its unicast retry behavior is bench-checked.

### Reason
Verified on 2026-10-20 against OpenIPC/devourer master (README, docs/ap-mode.md, docs/aggregation.md, src/WiFiDriver.cpp, src/jaguar2/RtlJaguar2Device.cpp):
- The README lists RTL8822BU TP-Link Archer T3U (2357:012d) as supported and benchmarked; the 8822B chip-id in WiFiDriver.cpp was read off an Archer T3U.
- The Jaguar1 defect does not apply: the Jaguar2 TX descriptor sets BMC from addr1's group bit, so unicast frames to the kart wait for an ACK; RTY_LMT_EN=1 with cfg.tx.retry_limit, and tx_retry_limit_ok is advertised only for C8822B (not C8821C). radiod passes --retry-limit (default 7) as cfg.tx.retry_limit.
- StartBeacon on Jaguar2 is hardware-validated on 2357:012d (beacon at TBTT). The AP harnesses (auth/assoc at retry=0 via the MACID ACK engine, software CCMP) are bench-validated on J2/J3. The BSSID must be unicast, which SpikePairing already ensures.
- Jaguar2 quirk: any TBTT re-latch (changing the interval or toggling EN_BCN_FUNCTION after StartBeacon) stops the beacon until it is re-downloaded. radiod's UPDATE_BEACON uses UpdateBeaconPayload, which re-downloads, so the game-network switch is safe; never change the interval of a running beacon.
- Operational caveats from devourer: a dense beacon interval (25 TU) helped scanners find the AP (our default is 100; tune with --beacon-interval), and an adapter on an xHCI root-hub port without VBUS reset (as on the dev PC) can get flaky after many runs, so replug between runs.

### Alternatives considered
- Wait for an RTL8812CU (0bda:c812): still the most validated (Jaguar3), but not sold on amazon.com; the T3U shares the HalMAC 88xx TX path family.
- RTL8812AU: rejected (Jaguar1 broadcast TX descriptor, no unicast retries).

### Files
- docs/HARDWARE_SETUP.md
- radiod/src/Radio.cpp
- radiod/src/main.cpp

## RadioAccessPoint is the FrameLink over RadioLink; RadioKartRadioAp is the production KartRadioAp

- id: radioaccesspoint-is-the-framelink-over-radiolink-radiokartra-20261020-213139
- type: architecture_decision
- status: active
- platform: kmp
- area: radio
- date: 2026-10-20

### Decision
The link layer the network stack runs on is `RadioAccessPoint` in :core:wifi-ap: it implements the netstack's `FrameLink` over `RadioLink` (802.11 management through ApStateMachine, CCMP data plane, LLC/SNAP, optional pcap capture), as the design's module rules ask (netstack defines FrameLink, wifi-ap implements it over core:radio). The production `KartRadioAp` is `RadioKartRadioAp` in :core:data, which turns PAIRING / GAME into an ApConfig and serves it on that access point. The spike's former glue class (SpikeRunner) was removed; the spike now runs RadioAccessPoint + NetstackNode.

### Rules that matter
- One Mutex guards the state machine, the capture and the packet-number counter, and is held across encrypt + TX_FRAME: the kart's CCMP replay window drops a lower packet number that arrives after a higher one, so concurrent senders must go on air in PN order.
- Transmit before capture; a failing capture never stops or reorders protocol traffic.
- `inbound` is a fixed-capacity Channel that survives stop/start and node relaunches; overflow drops with a warning (the radio collector never suspends); queued payloads are discarded when the network is dropped.
- `serve(config)` always replaces the state machine (station table reset, spec 8.5). `RadioKartRadioAp.beacon` is a no-op for the network already served, because the orchestrator calls `beacon(GAME)` on every recovery pass and a fresh game nonce or a station-table reset would cut off a kart that is still associated.
- Both networks use the store's BSSID, the same address NetstackNode puts in ARP replies. GAME: identity SSID/PSK, time-based nonce. PAIRING: QR SSID, PSK = SHA-256(seed), random nonce.
- `isStationAssociated` is set in the same step as the station, so `associatedStation` is exact right after stop() or a network switch (a stateIn mapping lagged one dispatch and let the orchestrator see a stale true).
- A stop generation makes a bring-up still waiting for READY fail if stop() ran meanwhile, so nothing is re-armed during shutdown.
- The access point does not touch the DHCP lease; whoever runs NetstackNode resets it.

### Known gaps (follow-ups)
- After radiod is relaunched by the supervisor, RadioKartRadioAp did not redo bring-up/serve (start() and beacon(GAME) short-circuit). Fixed 2026-10-21 for the desktop wiring: with `radioConnection` (the link's connected flow) it redoes bringUp and re-serves the same ApConfig on a reconnect while started; a failed re-bring-up makes beacon() fail visibly until the next reconnect.
- The TCP endpoint keeps one connection per local port and drops a SYN from a new source port while the old connection holds it; the orchestrator does not watch associatedStation during a session, so a kart that resets without FIN/RST cannot reconnect until RTO/keepalive ends the stale connection. The spike aborts its pairing connection when the station leaves for this reason. (Fixed in the follow-up: see the bugfix "A kart that leaves mid-session wedged recovery".)
- The spike serves the RCD pairing handshake on 5201 but never connects to the kart's pairing service or sends SetGroupInfo, so the kart keeps its stored pairing.

### Files
- core/wifi-ap/src/commonMain/kotlin/com/mklhc/core/wifi_ap/ap/RadioAccessPoint.kt
- core/data/src/commonMain/kotlin/com/mklhc/core/data/radio/RadioKartRadioAp.kt
- app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikeSession.kt

## Station inactivity timeout in the access point

- id: station-inactivity-timeout-in-the-access-point-20261021-012437
- type: architecture_decision
- status: active
- platform: kmp
- area: radio
- date: 2026-10-21

### Decision
`RadioAccessPoint` drops an associated station it has not heard from for `RadioOptions.stationInactivityTimeoutMs` (default 20 000 ms, 0 disables): `ApStateMachine.dropStation` forgets it and returns a reason-4 deauthentication (inactivity), which is transmitted best effort, and the station is published as gone exactly like a departure (the network stays served, the inbound queue and the lease are left as for a deauthentication). The orchestrator's departure watch and the spike's lease watcher react to it. And, like hostapd, any to-DS data sent to our BSSID by a station that is not associated is answered with a reason-7 deauthentication (class 3 frame from a non-associated station), at most once per second per sender, so a kart that missed the reason-4 frame, or that still believes it is associated after the app restarted, rejoins instead of being ignored.

### Why these values
radiod reports no TX status, so the access point cannot probe a station (hostapd's null-data poll): only received frames prove the kart is there, and any frame from it to the BSSID counts (management, data, null data, unprotected or CCMP-failing). 20 s: the kart does not use power save (PM = 0 on hardware); in a game it streams telemetry and video and ACKs the 1 s LVNI keepalive, and any open TCP connection forces a frame at least every ~11 s; during pairing the longest expected silence is the ~5 s wait after the RCD handshake. TCP (RTO ~6 s, keepalive ~14 s) usually ends a game session first; the timeout matters before TCP exists (a pairing attempt waiting in accept, a kart that associated and vanished).

### Implementation notes
- Activity marks come from an injected `kotlin.time.TimeSource` (tests pass the scheduler's), recorded only by `noteStationHeard` (after the state machine has seen a management frame, so the association request counts); `publishStation(null)` clears the mark.
- The watchdog is launched by `start(scope)` with the radio collectors (`radioJobs`), idles while nobody is associated, sleeps outside the lock, checks under it, survives a failing check (logged at error) and is cancelled and joined by `stop()`.
- Log lines: `watchStationActivity(): station inactive, dropped -> sta= idleMs=` and `onDataFrame(): data from a station that is not associated, deauthenticating -> sta= reason=7`.
- Residual: a kart that really enters 802.11 power save without sending null data would be dropped after 20 s; not seen on hardware.

### Files
- core/wifi-ap/src/commonMain/kotlin/com/mklhc/core/wifi_ap/ap/RadioAccessPoint.kt
- core/wifi-ap/src/commonMain/kotlin/com/mklhc/core/wifi_ap/ap/ApStateMachine.kt

## Desktop app runs the real session over the radio (DesktopKartRuntime)

- id: desktop-app-runs-the-real-session-over-the-radio-desktopkart-20261021-033904
- type: architecture_decision
- status: active
- platform: kmp
- area: app
- date: 2026-10-21

### Decision
The desktop app runs the real session: `DesktopKartRuntime` (app/desktopMain, package com.mklhc.app.desktop) owns a one-task-at-a-time scope (`Dispatchers.Default.limitedParallelism(1)`), the radiod socket in a short temp directory with a random token, `DesktopRadioLink` + `RadiodSupervisor`, `RadioAccessPoint`, `RadioKartRadioAp` (adapterState READY while radiod is connected, `radioConnection = link.connected`), a `DataStorePairingStore` in the data directory and `KartSessionRepository`; `ConfinedKartSession` moves the suspend commands and `cancelPairing` onto that scope (flows, setDriveCommand and setCaptureEnabled pass through). Main binds it through `initKoin(extra = ...)` (Koin 4.1.1 lets the later module override the demo); Android still binds the scripted demo. `MKLHC_DEMO=1` / `--demo` keeps the demo on desktop.

### Configuration (environment)
`MKLHC_RADIO` (vvvv:pppp, default 0bda:c812; the Archer T3U is 2357:012d), `MKLHC_RADIOD` (default: the radiod build candidates from the repository root, where `:app:run` now runs), `MKLHC_DATA_DIR` (default %APPDATA%\MKLHC, ~/Library/Application Support/MKLHC, $XDG_CONFIG_HOME/MKLHC or ~/.config/MKLHC; pairing file `pairing.preferences_pb`, created owner-only on POSIX), `MKLHC_DEMO`, `MKLHC_VIDEO_DUMP` (a folder; when set, `VideoDatagramDump` writes every video UDP payload as received, before `LspVideoReassembler`, to `video-<yyyyMMdd-HHmmss>.viddump`: magic `MKLHCVD1`, then records of u64 LE epoch micros + u32 LE length + payload; one unbuffered write per record, 256 MiB cap, a failure or the cap stops it with one warning, never touching the session; `*.viddump` is git-ignored because it holds the camera picture; fed through `KartSessionRepository`'s `videoDatagramTap`). Invalid values exit with code 2 naming the variable; a second instance on the same data directory is refused (exclusive lock on `instance.lock`, exit code 3).

### Lifecycle rules
- Exit: one guarded stop (window close, JVM shutdown hook, end of main, Cmd+Q via the close-all-windows quit strategy): session stop (at most 6 s; parks or powers off the kart per the shutdown-on-exit setting, which is on by default, then STOP_BEACON + SHUTDOWN) and then the runtime shutdown (at most 6 s: close the link, wait up to 3 s for radiod to exit, stop the supervisor before its restart backoff, cancel the scopes, delete the socket directory).
- radiod powers the radio down and exits on socket EOF or reset as it does on SHUTDOWN (radiod/src/main.cpp runSession/runRadiod), but only between commands and not when it is killed.
- `RadioKartRadioAp` redoes bringUp and re-serves the same ApConfig after a radiod relaunch while started; `start()` retries its bring-up once on the radiod that replaces one that dropped during it (a crash in INIT, or a start right after a stop).
- `KartSessionRepository` serialises start/startPairing/cancelPairing/stop with one Mutex (`commandLock`), because relaunchOrchestrator suspends before publishing the new job and a command in that gap left an orchestrator running.

### Not verified yet
Cmd+Q, the shutdown hook in a real run and the POSIX permission tests are untested on this Windows host. A completed pairing sends SetGroupInfo, which replaces the kart's stored pairing. The hardware run of 2026-10-21 paired a kart from the desktop app and ran the game session with video and telemetry, and the window close stopped everything cleanly (see testing "Hardware run: full pairing from the desktop app"); driving and the raw video dump are not tried on hardware yet.

### Files
- app/src/desktopMain/kotlin/com/mklhc/app/desktop/DesktopKartRuntime.kt
- app/src/desktopMain/kotlin/com/mklhc/app/desktop/DesktopRadioSettings.kt
- app/src/desktopMain/kotlin/com/mklhc/app/Main.kt
- core/data/src/commonMain/kotlin/com/mklhc/core/data/radio/RadioKartRadioAp.kt
- core/data/src/commonMain/kotlin/com/mklhc/core/data/session/KartSessionRepository.kt

## Desktop video is decoded with FFmpeg (JavaCPP presets, LGPL build)

- id: desktop-video-is-decoded-with-ffmpeg-javacpp-lgpl-20261021
- type: decision
- status: active
- platform: kmp
- area: video
- date: 2026-10-21

### Decision
The desktop `VideoSurface` decodes the kart's H.264 with FFmpeg 8.1.2 through `org.bytedeco:ffmpeg:8.1.2-1.5.14` + `org.bytedeco:javacpp:1.5.14`, the default LGPL build (never the `-gpl` classifiers), with only the building OS's native classifier (20-30 MB). `H264FrameDecoder` (core/video desktopMain): single-threaded with AV_CODEC_FLAG_LOW_DELAY so each picture comes out of the call that fed it, the shared `KeyframeGate` (waits for IDR + SPS/PPS, prepends them on a restart), libswscale to packed BGRA, flush + wait for the next IDR on an error, gives up after 30 failures in a row; FFmpeg's warnings go to Kermit (tag FFmpeg). The surface decodes on its own single-thread loop and draws the newest picture with ContentScale.Fit; "Waiting for video" plus the frame count until the first picture; "Video preview unavailable" (any picture taken down) when FFmpeg cannot load, the decoder gives up or the loop fails; the decoder reuses one BGRA buffer, copied into a Skia image per picture. Android keeps MediaCodec.

### Reason
The stream is H.264 High profile, CABAC, 8x8 transform, 1280x800 at 30 fps: no pure-Java decoder handles it (jcodec is Main profile only), and VLC/GStreamer bindings need a system install. FFmpeg decodes it in about 3.3 ms per picture including the BGRA conversion. LGPL with unmodified dynamically loaded libraries is compatible with the app's AGPL-3.0; THIRD_PARTY_NOTICES lists FFmpeg and JavaCPP.

### Verified
Tests decode synthetic I_PCM streams written by the project (`SyntheticH264`, no recorded video, no encoder). A throwaway replay of the 90 s live capture through the Kotlin reassembler and this decoder gave 2,700 pictures from 2,700 frames with no FFmpeg warning. Seen live on 2026-10-21 (commit 9bae5f1): the paired kart joined on Start and the camera showed in the cockpit; no FFmpeg warning in the surviving log, one orphan end-of-frame datagram dropped (the known quirk), decoder released and beacon off at the window close.

### Files
- core/video/src/desktopMain/kotlin/com/mklhc/core/video/H264FrameDecoder.kt
- core/video/src/desktopMain/kotlin/com/mklhc/core/video/VideoSurface.desktop.kt
- core/video/build.gradle.kts
- THIRD_PARTY_NOTICES.md

## The cockpit drives with the keyboard; a key drives only if pressed while driving and focused

- id: cockpit-keyboard-drive-pressed-while-driving-and-focused-20261023
- type: decision
- status: active
- platform: kmp
- area: cockpit
- date: 2026-10-23

### Decision
The shared cockpit screen (commonMain, so also a phone's hardware keyboard) takes the keyboard through `Modifier.keyboardDrive`: W/Up forward, S/Down reverse, A/Left and D/Right steering, Space the brake light; a held key is the full axis (the session applies max-throttle and expo), opposite keys cancel, a key-up is the axis's release callback (release means neutral, the touch controls' contract), only changes are reported (the ViewModel keeps a held command alive). A key-down with Ctrl/Alt/Meta is not driving; a key-up always counts. Safety rule: a key drives only if it went down while the kart was Driving and the cockpit had the focus. Leaving Driving releases the held keys and ignores them until they come up; losing the window or cockpit focus releases everything and ignores EVERY key until it comes up (after the focus returns, the first press of each key does nothing); leaving the composition releases everything. `focusTarget()` rather than `focusable()`, so the full-screen root adds no accessibility semantics.

### Reason
One mouse cannot steer and throttle at once, so the desktop could not really drive. The OS auto-repeats a held key as key-downs with no repeat flag in Compose's common KeyEvent, so a key held across a link drop or a focus switch would otherwise restart the kart by itself; after a focus loss the key-ups went elsewhere, so which keys are down is unknown and all must be pressed afresh. On the desktop a window switch is a temporary AWT focus loss that keeps Compose's own focus, so the window focus (`LocalWindowInfo.isWindowFocused`) is watched separately.

### Verified
`KeyboardDriveTest` (11, pure state) and `CockpitScreenTest` (keyboard section and a Root end-to-end test through Koin); the compose-ui-test key injection refuses a second key-down of a held key, so the auto-repeat is tested at the state level only. Not yet driven on hardware.

### Files
- feature/cockpit/presentation/src/commonMain/kotlin/com/mklhc/feature/cockpit/presentation/KeyboardDrive.kt
- feature/cockpit/presentation/src/commonMain/kotlin/com/mklhc/feature/cockpit/presentation/CockpitScreen.kt
- docs/HARDWARE_SETUP.md
