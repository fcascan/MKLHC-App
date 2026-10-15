package com.mklhc.core.wifi_ap.lp2p

/**
 * Reads a UTF-8 text resource from the test classpath (for example `lp2p_vectors.json`).
 *
 * commonTest has no file or classpath API, so each JVM test source set supplies the `actual`
 * (see jvmSharedTest). The LP2P golden vectors are plain JSON files under `src/commonTest/resources`.
 */
internal expect fun readTestResource(name: String): String
