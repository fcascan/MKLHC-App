package com.mklhc.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JCA-backed implementation of [CryptoPrimitives], shared by the Android and desktop JVM targets
 * through the `jvmShared` source set.
 *
 * A single instance is safe to reuse and to share across threads: the hash, HMAC and cipher routines
 * each create their own [Cipher]/[Mac]/[MessageDigest] because those JCA types are not thread-safe,
 * and the one piece of retained state -- the [SecureRandom] behind [secureRandomBytes] -- is itself
 * thread-safe (kept rather than re-created so it is not reseeded on every call).
 */
actual class CryptoPrimitives actual constructor() {

    private val secureRandom = SecureRandom()

    actual fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == AES_BLOCK_BYTES) { "AES ECB block must be $AES_BLOCK_BYTES bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    actual fun aesGcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    actual fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    actual fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray = hmac("HmacSHA256", key, msg)

    actual fun hmacSha512(key: ByteArray, msg: ByteArray): ByteArray = hmac("HmacSHA512", key, msg)

    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    actual fun secureRandomBytes(n: Int): ByteArray {
        require(n >= 0) { "n must be non-negative: n=$n" }
        return ByteArray(n).also { secureRandom.nextBytes(it) }
    }

    private fun hmac(algorithm: String, key: ByteArray, msg: ByteArray): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(msg)
    }

    private companion object {
        const val AES_BLOCK_BYTES = 16
        const val GCM_TAG_BITS = 128
    }
}
