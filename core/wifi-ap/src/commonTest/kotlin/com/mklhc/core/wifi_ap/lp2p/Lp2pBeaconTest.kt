package com.mklhc.core.wifi_ap.lp2p

import com.mklhc.core.crypto.CryptoPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden tests for [Lp2pBeacon].
 *
 * `tools/gen_lp2p_vectors.py` runs OpenKart's `LP2PAP.encrypt_network` with a recorded
 * `os.urandom`, a fixed clock and scripted `random`, then stores the psk, the nonce pair, every
 * urandom draw (in order) and the resulting `ies` and CCMP key. Feeding the same draws back in must
 * reproduce `ies` byte for byte; this pins the GCM tag placement (`ad || tag || ciphertext`), the
 * TLV encoding, the draw order and the vendor-IE framing.
 */
class Lp2pBeaconTest {

    private val primitives = CryptoPrimitives()
    private val beacon = Lp2pBeacon(primitives)
    private val vectors = Lp2pVectors.load()

    @Test
    fun `encryptNetwork reproduces OpenKart vendor elements and ccmp key`() {
        assertEquals(2, vectors.encryptNetwork.size, "the generator emits a sequential and a pure-random case")

        for (case in vectors.encryptNetwork) {
            val random = ReplayedRandomness(case.urandomDraws)

            val network = beacon.encryptNetwork(case.psk, case.plainNonce, case.encryptedNonce, random::next)

            assertEquals(case.ies.toHexString(), network.vendorElements.toHexString(), "ies for ${case.name}")
            assertEquals(case.ccmpKey.toHexString(), network.ccmpKey.toHexString(), "ccmp key for ${case.name}")
            random.assertFullyConsumed(case.name)
        }
    }

    @Test
    fun `encryptNetwork with a nonce table draws the nonce pair then reproduces OpenKart output`() {
        val tableBytes = loadLp2pNonceTable()

        for (case in vectors.encryptNetwork) {
            val scriptedTable = NonceTable(
                table = tableBytes,
                nowEpochSeconds = { case.timeSeconds },
                randomInt = ReplayedRandomInt(case.randrangeDraws)::next,
            )
            val random = ReplayedRandomness(case.urandomDraws)

            val network = beacon.encryptNetwork(case.psk, scriptedTable, random::next, temporary = case.temporary)

            assertEquals(case.ies.toHexString(), network.vendorElements.toHexString(), "ies for ${case.name}")
            assertEquals(case.ccmpKey.toHexString(), network.ccmpKey.toHexString(), "ccmp key for ${case.name}")
            random.assertFullyConsumed(case.name)
        }
    }

    @Test
    fun `vendor elements are two 0x00 0x22 0xAA 0x06 vendor-specific IEs`() {
        val case = vectors.encryptNetwork.first()
        val random = ReplayedRandomness(case.urandomDraws)

        val ies = beacon.encryptNetwork(case.psk, case.plainNonce, case.encryptedNonce, random::next).vendorElements

        // First element: dd len 00 22 aa 06 00 ...; second: dd len 00 22 aa 06 01 ...
        assertEquals(0xDD, ies[0].toInt() and 0xFF)
        val firstLength = ies[1].toInt() and 0xFF
        assertEquals("0022aa0600", ies.copyOfRange(2, 7).toHexString())
        val secondStart = 2 + firstLength
        assertEquals(0xDD, ies[secondStart].toInt() and 0xFF)
        val secondLength = ies[secondStart + 1].toInt() and 0xFF
        assertEquals("0022aa0601", ies.copyOfRange(secondStart + 2, secondStart + 7).toHexString())
        assertEquals(ies.size, secondStart + 2 + secondLength)
    }

    @Test
    fun `encryptNetwork rejects an encrypted nonce that is not 16 bytes`() {
        val case = vectors.encryptNetwork.first()
        val random = ReplayedRandomness(case.urandomDraws)

        assertFailsWith<IllegalArgumentException> {
            beacon.encryptNetwork(case.psk, case.plainNonce, ByteArray(15), random::next)
        }
    }

    @Test
    fun `encryptNetwork rejects a random source that returns the wrong length`() {
        val case = vectors.encryptNetwork.first()

        assertFailsWith<IllegalArgumentException> {
            beacon.encryptNetwork(case.psk, case.plainNonce, case.encryptedNonce) { ByteArray(it + 1) }
        }
    }

    /** Feeds back recorded `os.urandom` draws in order and checks each requested size. */
    private class ReplayedRandomness(private val draws: List<ByteArray>) {
        private var cursor = 0

        fun next(count: Int): ByteArray {
            assertTrue(cursor < draws.size, "more random draws requested than recorded")
            val draw = draws[cursor++]
            assertEquals(draw.size, count, "requested size of random draw #$cursor")
            return draw.copyOf()
        }

        fun assertFullyConsumed(label: String) {
            assertEquals(draws.size, cursor, "all recorded random draws consumed for $label")
        }
    }

    /** Feeds back recorded `random.randrange(bound)` results in order and checks each bound. */
    private class ReplayedRandomInt(private val draws: List<Lp2pVectors.RandrangeDraw>) {
        private var cursor = 0

        fun next(bound: Int): Int {
            assertTrue(cursor < draws.size, "more randrange draws requested than recorded")
            val draw = draws[cursor++]
            assertEquals(draw.bound, bound, "bound of randrange draw #$cursor")
            return draw.value
        }
    }
}
