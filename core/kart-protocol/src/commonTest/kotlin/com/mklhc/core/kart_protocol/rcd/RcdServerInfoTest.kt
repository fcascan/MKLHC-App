package com.mklhc.core.kart_protocol.rcd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Golden tests for [RcdServerInfo.getPairingKeys].
 *
 * The expected pairing id and secret key come from `tools/gen_rcd_vectors.py`, which calls OpenKart's
 * `RcdServerInfo.get_pairing_keys` (openkartd/rcd.py) directly, so any drift from the
 * SHA-256/SHA-512 derivation fails here.
 */
class RcdServerInfoTest {

    private val vectors = RcdVectors.load()

    @Test
    fun `getPairingKeys matches the OpenKart golden for every device`() {
        assertEquals(3, vectors.pairingKeys.size, "the generator emits three pairing vectors")
        for (v in vectors.pairingKeys) {
            val info = RcdServerInfo(name = "KartHub", ident = ByteArray(0x10), masterKey = v.masterKey)
            val (pairingId, secretKey) = info.getPairingKeys(v.deviceIdent, v.deviceName)
            assertEquals(v.pairingId.toHexString(), pairingId.toHexString(), "pairing id for ${v.name}")
            assertEquals(v.secretKey.toHexString(), secretKey.toHexString(), "secret key for ${v.name}")
        }
    }

    @Test
    fun `constructor rejects an ident that is not 16 octets`() {
        assertFailsWith<IllegalArgumentException> {
            RcdServerInfo(name = "KartHub", ident = ByteArray(0x0F), masterKey = ByteArray(0x40))
        }
    }

    @Test
    fun `constructor rejects a name of 16 octets or more`() {
        assertFailsWith<IllegalArgumentException> {
            RcdServerInfo(name = "0123456789abcdef", ident = ByteArray(0x10), masterKey = ByteArray(0x40))
        }
    }
}
