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
