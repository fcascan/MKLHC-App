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
