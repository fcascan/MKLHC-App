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
