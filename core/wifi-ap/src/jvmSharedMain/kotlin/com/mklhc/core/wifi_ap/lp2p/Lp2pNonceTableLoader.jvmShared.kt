// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/lp2p_nonces.py (BSD-3-Clause). Copyright 2021 Sam Edwards.
package com.mklhc.core.wifi_ap.lp2p

/** Marker used only to reach this module's class loader for the resource lookup. */
private object NonceTableResourceAnchor

private const val NONCE_TABLE_RESOURCE = "/lp2p_nonces.bin"

/**
 * JVM actual of [loadLp2pNonceTable]: reads `lp2p_nonces.bin` from the classpath. The file lives in
 * `src/jvmSharedMain/resources`, so it is packaged for both the desktop jar and the Android AAR.
 */
actual fun loadLp2pNonceTable(): ByteArray {
    val stream = NonceTableResourceAnchor::class.java.getResourceAsStream(NONCE_TABLE_RESOURCE)
        ?: error("Resource $NONCE_TABLE_RESOURCE not found on the classpath")
    return stream.use { it.readBytes() }
}
