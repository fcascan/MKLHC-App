package com.mklhc.core.wifi_ap.lp2p

import com.mklhc.core.crypto.CryptoPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden tests for [NonceTable] and the platform resource loader [loadLp2pNonceTable].
 *
 * The samples and the `get_nonce_pair` scenario come from `tools/gen_lp2p_vectors.py`, which calls
 * OpenKart's `get_plaintext_nonce`, `get_encrypted_nonce` and `get_nonce_pair`
 * (openkartd/lp2p_nonces.py). Running them exercises the bundled `lp2p_nonces.bin` resource, so a
 * wrong or truncated table fails here too.
 */
class NonceTableTest {

    private val vectors = Lp2pVectors.load()
    private val tableBytes = loadLp2pNonceTable()
    private val table = NonceTable(tableBytes)

    @Test
    fun `bundled nonce table has the expected size and digest`() {
        assertEquals(vectors.nonceTableSize, tableBytes.size)
        assertEquals(0x10000 * 16, tableBytes.size)

        val digest = CryptoPrimitives().sha256(tableBytes).toHexString()
        assertEquals(vectors.nonceTableSha256, digest)
    }

    @Test
    fun `plaintext and encrypted nonces match OpenKart samples`() {
        assertTrue(vectors.nonceSamples.size >= 5, "expected a handful of sample indices")

        for (sample in vectors.nonceSamples) {
            assertEquals(
                sample.plaintextNonce.toHexString(),
                table.getPlaintextNonce(sample.index).toHexString(),
                "plaintext nonce #${sample.index}",
            )
            assertEquals(
                sample.encryptedNonce.toHexString(),
                table.getEncryptedNonce(sample.index).toHexString(),
                "encrypted nonce #${sample.index}",
            )
        }
    }

    @Test
    fun `plaintext nonce layout is WellKnownStr revision then big-endian index`() {
        val nonce = table.getPlaintextNonce(0x1234)

        assertEquals(16, nonce.size)
        assertEquals("WellKnownStr", nonce.copyOfRange(0, 12).decodeToString())
        assertEquals("0001", nonce.copyOfRange(12, 14).toHexString())
        assertEquals("1234", nonce.copyOfRange(14, 16).toHexString())
    }

    @Test
    fun `getNoncePair reproduces the OpenKart sequence with a fixed clock and scripted random`() {
        val scenario = vectors.noncePair
        val draws = scenario.randrangeDraws.toMutableList()
        val scripted = NonceTable(
            table = tableBytes,
            nowEpochSeconds = { scenario.timeSeconds },
            randomInt = { bound ->
                val draw = draws.removeAt(0)
                assertEquals(draw.bound, bound, "randrange bound")
                draw.value
            },
        )

        for ((position, call) in scenario.calls.withIndex()) {
            val pair = scripted.getNoncePair(pureRandom = call.pureRandom)

            assertEquals(call.index, pair.index, "index of call #$position")
            assertEquals(call.plainNonce.toHexString(), pair.plainNonce.toHexString(), "plain nonce of call #$position")
            assertEquals(
                call.encryptedNonce.toHexString(),
                pair.encryptedNonce.toHexString(),
                "encrypted nonce of call #$position",
            )
        }
        assertTrue(draws.isEmpty(), "every scripted random draw is consumed")
    }

    @Test
    fun `sequence counter wraps after sixteen draws within one period`() {
        val wrapping = NonceTable(tableBytes, nowEpochSeconds = { 0L }, randomInt = { 15 })

        // The first call draws 15, increments to 0 (wrap), then 1, 2 ... within period 0.
        val indices = List(3) { wrapping.getNoncePair(pureRandom = false).index }

        assertEquals(listOf(0, 1, 2), indices)
    }

    @Test
    fun `out of range indices are rejected`() {
        assertFailsWith<IllegalArgumentException> { table.getEncryptedNonce(-1) }
        assertFailsWith<IllegalArgumentException> { table.getEncryptedNonce(0x10000) }
        assertFailsWith<IllegalArgumentException> { table.getPlaintextNonce(-1) }
        assertFailsWith<IllegalArgumentException> { table.getPlaintextNonce(0x10000) }
    }

    @Test
    fun `a table of the wrong size is rejected`() {
        assertFailsWith<IllegalArgumentException> { NonceTable(ByteArray(16)) }
    }
}
