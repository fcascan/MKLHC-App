package com.mklhc.core.wifi_ap.lp2p

import com.mklhc.core.crypto.CryptoPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden tests for [Lp2pKeys].
 *
 * The expected keys come from `tools/gen_lp2p_vectors.py`, which calls OpenKart's
 * `LP2PAP.derive_keys` (openkartd/lp2p.py) directly, so any drift from the reference derivation
 * `HMAC(HMAC(nonce, psk), 02 01)` fails here.
 */
class Lp2pKeysTest {

    private val keys = Lp2pKeys(CryptoPrimitives())
    private val vectors = Lp2pVectors.load()

    @Test
    fun `deriveKeys returns both OpenKart keys for every vector`() {
        assertEquals(3, vectors.deriveKeys.size, "the generator emits three derive_keys vectors")

        for (vector in vectors.deriveKeys) {
            val (ka, kc) = keys.deriveKeys(vector.nonce, vector.psk)

            assertEquals(vector.ka.toHexString(), ka.toHexString(), "ka for ${vector.name}")
            assertEquals(vector.kc.toHexString(), kc.toHexString(), "kc for ${vector.name}")
        }
    }

    @Test
    fun `deriveCcmpKey is the first derived key`() {
        for (vector in vectors.deriveKeys) {
            val ccmpKey = keys.deriveCcmpKey(vector.nonce, vector.psk)

            assertEquals(vector.ka.toHexString(), ccmpKey.toHexString(), "ccmp key for ${vector.name}")
            assertEquals(16, ccmpKey.size)
        }
    }
}
