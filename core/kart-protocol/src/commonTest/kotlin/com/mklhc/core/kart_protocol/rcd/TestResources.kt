package com.mklhc.core.kart_protocol.rcd

/**
 * Reads a UTF-8 text resource from the test classpath (for example `rcd_vectors.json`).
 *
 * commonTest has no file or classpath API, so each JVM test source set supplies the `actual`
 * (see jvmSharedTest). The RCD golden vectors are a plain JSON file under `src/commonTest/resources`.
 */
internal expect fun readTestResource(name: String): String
