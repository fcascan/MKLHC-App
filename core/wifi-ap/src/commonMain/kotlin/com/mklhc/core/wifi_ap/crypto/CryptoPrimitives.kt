package com.mklhc.core.wifi_ap.crypto

/**
 * Platform-agnostic surface for the symmetric-crypto building blocks the Wi-Fi AP stack relies on.
 *
 * commonMain code (for example [Ccmp]) depends only on this abstraction so that no platform crypto
 * API leaks into shared logic. The concrete implementation lives in the `jvmShared` source set and
 * is backed by the JVM Cryptography Architecture (JCA), which is available on both Android and the
 * desktop JVM.
 *
 * Note on CCM: stock JCA exposes no "AES/CCM" transformation, so AES-CCM (used by CCMP) is built by
 * hand in [Ccmp] on top of [aesEcbEncryptBlock] (CBC-MAC for the MIC and CTR for encryption). This
 * interface therefore deliberately exposes the single-block ECB primitive rather than a CCM cipher.
 */
expect class CryptoPrimitives() {

    /**
     * Encrypts a single 16-byte block with AES-128 in ECB mode and no padding. This is the raw
     * block-cipher call used as the kernel of CCM's CBC-MAC and CTR modes; it is not a general
     * encryption routine and must never be used to protect multi-block data directly.
     *
     * @param key 16-byte AES-128 key.
     * @param block exactly 16 bytes of input.
     * @return the 16-byte encrypted block.
     */
    fun aesEcbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray

    /**
     * Encrypts [plaintext] with AES-GCM, authenticating [aad], and returns the ciphertext with the
     * 128-bit authentication tag appended. Reserved for the later LP2P transport.
     */
    fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /**
     * Decrypts an AES-GCM [ciphertext] (with the 128-bit tag appended), verifying [aad] and the
     * tag. Throws if authentication fails. Reserved for the later LP2P transport.
     */
    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray

    /** Computes HMAC-SHA-256 of [msg] under [key], returning 32 bytes. */
    fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray

    /** Computes HMAC-SHA-512 of [msg] under [key], returning 64 bytes. */
    fun hmacSha512(key: ByteArray, msg: ByteArray): ByteArray

    /** Computes the SHA-256 digest of [data], returning 32 bytes. */
    fun sha256(data: ByteArray): ByteArray

    /** Computes the SHA-512 digest of [data], returning 64 bytes. */
    fun sha512(data: ByteArray): ByteArray
}
