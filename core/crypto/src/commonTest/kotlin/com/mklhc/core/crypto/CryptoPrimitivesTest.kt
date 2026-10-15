package com.mklhc.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Known-answer self-tests for [CryptoPrimitives], so the shared crypto module is covered on its own
 * rather than only through its consumers.
 *
 * Vectors: SHA-256 / SHA-512 of the empty string and "abc" (FIPS 180-4 examples), HMAC-SHA-256 and
 * HMAC-SHA-512 RFC 4231 test case 1, and the AES-128 ECB worked example from FIPS-197 (Appendix B /
 * C.1). AES-GCM is checked by round-trip plus tamper rejection, and [CryptoPrimitives.secureRandomBytes]
 * by length and non-repetition.
 */
class CryptoPrimitivesTest {

    private val crypto = CryptoPrimitives()

    @Test
    fun `sha256 matches the FIPS 180-4 known answers`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            crypto.sha256(ByteArray(0)).toHex(),
            "SHA-256 of the empty string",
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            crypto.sha256("abc".encodeToByteArray()).toHex(),
            "SHA-256 of \"abc\"",
        )
    }

    @Test
    fun `sha512 matches the FIPS 180-4 known answers`() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            crypto.sha512(ByteArray(0)).toHex(),
            "SHA-512 of the empty string",
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            crypto.sha512("abc".encodeToByteArray()).toHex(),
            "SHA-512 of \"abc\"",
        )
    }

    @Test
    fun `hmacSha256 matches RFC 4231 test case 1`() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()

        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            crypto.hmacSha256(key, data).toHex(),
        )
    }

    @Test
    fun `hmacSha512 matches RFC 4231 test case 1`() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()

        assertEquals(
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde" +
                "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            crypto.hmacSha512(key, data).toHex(),
        )
    }

    @Test
    fun `aesEcbEncryptBlock matches the FIPS-197 worked example`() {
        val key = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val plaintext = "00112233445566778899aabbccddeeff".hexToBytes()

        assertEquals(
            "69c4e0d86a7b0430d8cdb78070b4c55a",
            crypto.aesEcbEncryptBlock(key, plaintext).toHex(),
        )
    }

    @Test
    fun `aesEcbEncryptBlock rejects a block that is not 16 bytes`() {
        val key = "000102030405060708090a0b0c0d0e0f".hexToBytes()

        assertFailsWith<IllegalArgumentException> { crypto.aesEcbEncryptBlock(key, ByteArray(15)) }
    }

    @Test
    fun `aesGcm round-trips and detects tampering`() {
        val key = "000102030405060708090a0b0c0d0e0f".hexToBytes()
        val iv = "000102030405060708090a0b".hexToBytes()
        val plaintext = "shared crypto module self-test".encodeToByteArray()
        val aad = "cafebabefacedbad".hexToBytes()

        val ciphertext = crypto.aesGcmEncrypt(key, iv, plaintext, aad)
        assertTrue(ciphertext.size > plaintext.size, "GCM output carries the authentication tag")

        val recovered = crypto.aesGcmDecrypt(key, iv, ciphertext, aad)
        assertEquals(plaintext.toHex(), recovered.toHex())

        val tampered = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFailsWith<Exception> { crypto.aesGcmDecrypt(key, iv, tampered, aad) }
    }

    @Test
    fun `secureRandomBytes returns the requested length`() {
        assertEquals(0, crypto.secureRandomBytes(0).size)
        assertEquals(1, crypto.secureRandomBytes(1).size)
        assertEquals(32, crypto.secureRandomBytes(32).size)
    }

    @Test
    fun `secureRandomBytes does not repeat across calls`() {
        val first = crypto.secureRandomBytes(32)
        val second = crypto.secureRandomBytes(32)

        assertNotEquals(first.toHex(), second.toHex(), "two draws must differ with overwhelming probability")
    }

    @Test
    fun `secureRandomBytes rejects a negative length`() {
        assertFailsWith<IllegalArgumentException> { crypto.secureRandomBytes(-1) }
    }

    private companion object {
        fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0) { "hex string must have an even length" }
            return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
