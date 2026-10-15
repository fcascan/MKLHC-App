// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/lp2p_nonces.py (BSD-3-Clause). Copyright 2021 Sam Edwards.
package com.mklhc.core.wifi_ap.lp2p

/**
 * Loads the bundled table of 65536 pre-encrypted LP2P nonces (`lp2p_nonces.bin`, 1048576 bytes)
 * for [NonceTable].
 *
 * The table is OpenKart's `NONCES` base64 blob decoded to raw bytes (see `tools/gen_lp2p_vectors.py`).
 * commonMain has no resource API, so the platform source sets provide the `actual`; the JVM
 * implementation (desktop and Android) reads it from the classpath.
 *
 * @throws IllegalStateException if the resource is missing from the build.
 */
expect fun loadLp2pNonceTable(): ByteArray
