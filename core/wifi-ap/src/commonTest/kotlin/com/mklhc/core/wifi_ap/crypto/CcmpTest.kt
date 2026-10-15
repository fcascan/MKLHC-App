package com.mklhc.core.wifi_ap.crypto

import com.mklhc.core.crypto.CryptoPrimitives
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden tests for the IEEE 802.11 CCMP implementation.
 *
 * The CCMP test vector is taken from IEEE Std 802.11-2012, Annex M.6.4 (CCMP test vector). The
 * AES-128 ECB sanity vector is taken from FIPS-197 (Appendix B / C.1 worked example). Each
 * intermediate value (AAD, nonce, CCMP header, ciphertext, MIC) is asserted so a regression pins
 * the exact step that broke.
 */
class CcmpTest {

    private val primitives = CryptoPrimitives()

    // IEEE Std 802.11-2012 M.6.4 inputs.
    private val tk = hex("c97c1f67ce371185514a8a19f2bdd52f")
    private val fullPlaintextMpdu =
        hex("0848c32c0fd2e128a57c5030f1844408abaea5b8fcba8033f8ba1a55d02f85ae967bb62fb6cda8eb7e78a050")
    private val header = fullPlaintextMpdu.copyOfRange(0, 24)
    private val data = fullPlaintextMpdu.copyOfRange(24, fullPlaintextMpdu.size)

    // PN in big-endian order (PN5 first ... PN0 last).
    private val pn = hex("b5039776e70c")

    private val expectedAad = hex("08400fd2e128a57c5030f1844408abaea5b8fcba0000")
    private val expectedNonce = hex("005030f1844408b5039776e70c")
    private val expectedCcmpHeader = hex("0ce70020769703b5")
    private val expectedCiphertext = hex("f3d0a2fe9a3dbf2342a643e43246e80c3c04d019")
    private val expectedMic = hex("7845ce0b16f97623")
    private val expectedMpdu =
        hex(
            "0848c32c0fd2e128a57c5030f1844408abaea5b8fcba8033" +
                "0ce70020769703b5" +
                "f3d0a2fe9a3dbf2342a643e43246e80c3c04d019" +
                "7845ce0b16f97623",
        )

    @Test
    fun `encrypt matches IEEE 802_11-2012 M_6_4 vector`() {
        val ccmp = Ccmp(primitives)

        // Pin each intermediate CCMP construction step against the standard's vector.
        assertEquals(expectedAad.toHex(), ccmp.aad(header).toHex(), "AAD")
        assertEquals(expectedNonce.toHex(), ccmp.nonce(header, pn).toHex(), "nonce")
        assertEquals(expectedCcmpHeader.toHex(), ccmp.ccmpHeader(pn, keyId = 0).toHex(), "CCMP header")

        val mpdu = ccmp.encrypt(tk, header, data, pn, keyId = 0)

        val ciphertext = mpdu.copyOfRange(32, 52)
        val mic = mpdu.copyOfRange(52, 60)
        assertEquals(expectedCiphertext.toHex(), ciphertext.toHex(), "ciphertext")
        assertEquals(expectedMic.toHex(), mic.toHex(), "MIC")
        assertEquals(expectedMpdu.toHex(), mpdu.toHex(), "full encrypted MPDU")
    }

    @Test
    fun `decrypt reverses encrypt and verifies MIC`() {
        val ccmp = Ccmp(primitives)

        val recovered = ccmp.decrypt(tk, expectedMpdu, keyId = 0)

        assertEquals(data.toHex(), recovered.toHex())
    }

    @Test
    fun `decrypt rejects a tampered MIC`() {
        val ccmp = Ccmp(primitives)
        val tampered = expectedMpdu.copyOf()
        // Flip one bit of the last MIC octet.
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        assertFailsWith<CcmpException> { ccmp.decrypt(tk, tampered, keyId = 0) }
    }

    @Test
    fun `decrypt rejects a replayed PN`() {
        val ccmp = Ccmp(primitives)

        // First reception with this PN succeeds.
        ccmp.decrypt(tk, expectedMpdu, keyId = 0)
        // A second reception with the same PN must be rejected as a replay.
        assertFailsWith<CcmpException> { ccmp.decrypt(tk, expectedMpdu, keyId = 0) }
    }

    @Test
    fun `decrypt rejects a one-byte buffer with CcmpException`() {
        val ccmp = Ccmp(primitives)

        assertFailsWith<CcmpException> { ccmp.decrypt(tk, ByteArray(1), keyId = 0) }
    }

    @Test
    fun `decrypt rejects a buffer too short for header, CCMP header and MIC`() {
        val ccmp = Ccmp(primitives)
        // 39 octets: one short of the smallest possible CCMP MPDU (24-octet header + 8 + 8 = 40).
        val tooShort = ByteArray(39)

        assertFailsWith<CcmpException> { ccmp.decrypt(tk, tooShort, keyId = 0) }
    }

    @Test
    fun `aesEcbEncryptBlock matches FIPS-197 AES-128 known answer`() {
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = hex("00112233445566778899aabbccddeeff")
        val expected = hex("69c4e0d86a7b0430d8cdb78070b4c55a")

        val ciphertext = primitives.aesEcbEncryptBlock(key, plaintext)

        assertEquals(expected.toHex(), ciphertext.toHex())
    }

    @Test
    fun `aesGcm round trip recovers the plaintext`() {
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val iv = hex("000102030405060708090a0b")
        val plaintext = "Multiplatform CCM building block".encodeToByteArray()
        val aad = hex("cafebabefacedbad")

        val ciphertext = primitives.aesGcmEncrypt(key, iv, plaintext, aad)
        val recovered = primitives.aesGcmDecrypt(key, iv, ciphertext, aad)

        assertEquals(plaintext.toHex(), recovered.toHex())
        assertTrue(ciphertext.size > plaintext.size, "GCM output must carry the authentication tag")
    }

    @Test
    fun `hash and hmac helpers produce standard-length digests`() {
        val key = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val msg = "abc".encodeToByteArray()

        assertEquals(32, primitives.sha256(msg).size)
        assertEquals(64, primitives.sha512(msg).size)
        assertEquals(32, primitives.hmacSha256(key, msg).size)
        assertEquals(64, primitives.hmacSha512(key, msg).size)
    }

    private companion object {
        fun hex(value: String): ByteArray {
            require(value.length % 2 == 0) { "Hex string must have an even length" }
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        fun ByteArray.toHex(): String = joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            value.toString(16).padStart(2, '0')
        }
    }
}
