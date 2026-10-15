// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/lp2p.py (BSD-3-Clause). Copyright 2021 Sam Edwards.
package com.mklhc.core.wifi_ap.lp2p

import com.mklhc.core.wifi_ap.crypto.CryptoPrimitives

/**
 * The result of [Lp2pBeacon.encryptNetwork]: what the access point needs to bring the LP2P
 * network up.
 *
 * @property ccmpKey 16-byte static CCMP temporal key (`ka`) used to encrypt data frames.
 * @property vendorElements the two concatenated vendor-specific information elements to append to
 * the beacon / probe response (the `ies` of OpenKart, hostapd `vendor_elements`).
 */
class Lp2pNetwork(val ccmpKey: ByteArray, val vendorElements: ByteArray)

/**
 * Builds the encrypted LP2P beacon information elements and the network's CCMP key.
 *
 * Business logic: a console recognises an LP2P network by two vendor IEs (OUI `00:22:AA`, type
 * `06`, subtype `00` and `01`) carried in the AP's beacon. Each IE is an AES-128-GCM sealed TLV:
 *
 * - IE 0 (subtype 0x00): a 32-byte additional-data block `ad0` (header `20 02 02 00`, the 8-byte
 *   big-endian local-communication id, the 16-byte encrypted nonce, 4 random bytes) followed by the
 *   GCM tag and the ciphertext of the system TLV. Sealed with key `kb`, the 12-byte IV being the 4
 *   random bytes followed by 8 zero bytes.
 * - IE 1 (subtype 0x01): 4 random bytes `ad1` followed by the GCM tag and the ciphertext of the
 *   application TLV. Sealed with key `kc` (derived from the psk), IV `ad1 || 00*8`.
 *
 * Wire layout of each IE body is `ad || tag(16) || ciphertext`, which is NOT the JCA output order
 * (`ciphertext || tag`); this class reorders it exactly as OpenKart does. Each IE is then framed
 * `dd <len> 00 22 aa 06 <subtype> <body>`.
 *
 * The 4-byte values are drawn from an injectable [randomness] source (first `ad0`'s tail, then
 * `ad1`) so tests can replay OpenKart's recorded draws; production callers must pass a real
 * cryptographic random source.
 *
 * Ported from OpenKart `LP2PAP.encrypt_network`; all crypto is delegated to [CryptoPrimitives].
 */
class Lp2pBeacon(private val primitives: CryptoPrimitives) {

    private val keys = Lp2pKeys(primitives)

    /**
     * Builds the CCMP key and vendor elements for the network protected by [psk], using the given
     * nonce pair.
     *
     * Draws exactly two 4-byte random values, in this order: the tail of `ad0`, then `ad1`.
     *
     * @param psk the 32-byte pre-shared key of the group.
     * @param plainNonce the 16-byte plaintext nonce used for key derivation.
     * @param encryptedNonce the 16-byte encrypted form of the same nonce, placed in `ad0`.
     * @param randomness returns the requested number of random bytes; must return exactly that many.
     */
    fun encryptNetwork(
        psk: ByteArray,
        plainNonce: ByteArray,
        encryptedNonce: ByteArray,
        randomness: (Int) -> ByteArray,
    ): Lp2pNetwork {
        require(encryptedNonce.size == NONCE_BYTES) { "encrypted nonce must be $NONCE_BYTES bytes" }

        // IE 0: system TLV, additional data = header + local-communication id + nonce + random.
        val ivRandom0 = drawRandom(randomness)
        val ad0 = ByteArray(AD0_BYTES)
        ad0[0] = 0x20
        ad0[1] = 0x02
        ad0[2] = 0x02
        LOCAL_COMMUNICATION_ID.toBigEndianBytes().copyInto(ad0, destinationOffset = 0x04)
        encryptedNonce.copyInto(ad0, destinationOffset = 0x0C)
        ivRandom0.copyInto(ad0, destinationOffset = 0x1C)
        val iv0 = ivRandom0 + ByteArray(IV_ZERO_PAD_BYTES)
        val tlv0 = packTlv(TLV)

        // IE 1: application TLV, additional data = just the random bytes.
        val ad1 = drawRandom(randomness)
        val iv1 = ad1 + ByteArray(IV_ZERO_PAD_BYTES)
        val tlv1 = packTlv(APP_TLV)

        val (ka, kc) = keys.deriveKeys(plainNonce, psk)
        val (kb, _) = keys.deriveKeys(plainNonce, LOCAL_COMMUNICATION_ID.toBigEndianBytes().reversedArray())

        val ie0 = sealInformationElement(kb, iv0, tlv0, ad0)
        val ie1 = sealInformationElement(kc, iv1, tlv1, ad1)

        return Lp2pNetwork(ccmpKey = ka, vendorElements = frameVendorElement(0, ie0) + frameVendorElement(1, ie1))
    }

    /**
     * Convenience form of [encryptNetwork] that first picks the nonce pair from [nonceTable],
     * mirroring OpenKart's `encrypt_network(psk, temporary=...)`.
     *
     * @param temporary true for a one-off network (the nonce index is purely random); false for the
     * persistent network (a time-based, sequentially increasing index, which minimises nonce reuse).
     */
    fun encryptNetwork(
        psk: ByteArray,
        nonceTable: NonceTable,
        randomness: (Int) -> ByteArray,
        temporary: Boolean = false,
    ): Lp2pNetwork {
        val pair = nonceTable.getNoncePair(pureRandom = temporary)
        return encryptNetwork(psk, pair.plainNonce, pair.encryptedNonce, randomness)
    }

    /** Seals [tlv] with AES-GCM and lays the IE body out as `ad || tag || ciphertext`. */
    private fun sealInformationElement(key: ByteArray, iv: ByteArray, tlv: ByteArray, ad: ByteArray): ByteArray {
        // CryptoPrimitives returns ciphertext || tag (JCA order); OpenKart emits tag before ciphertext.
        val sealed = primitives.aesGcmEncrypt(key, iv, tlv, ad)
        val tagStart = sealed.size - GCM_TAG_BYTES
        return ad + sealed.copyOfRange(tagStart, sealed.size) + sealed.copyOfRange(0, tagStart)
    }

    /** Wraps an IE body as `dd <len> 00 22 aa 06 <subtype> <body>`. */
    private fun frameVendorElement(subtype: Int, body: ByteArray): ByteArray {
        val payload = byteArrayOf(OUI_0, OUI_1, OUI_2, OUI_TYPE, subtype.toByte()) + body
        require(payload.size <= MAX_ELEMENT_PAYLOAD) { "vendor element payload too long: ${payload.size}" }
        return byteArrayOf(ELEMENT_ID_VENDOR_SPECIFIC, payload.size.toByte()) + payload
    }

    private fun drawRandom(randomness: (Int) -> ByteArray): ByteArray {
        val drawn = randomness(RANDOM_BYTES)
        require(drawn.size == RANDOM_BYTES) { "random source returned ${drawn.size} bytes, expected $RANDOM_BYTES" }
        return drawn.copyOf()
    }

    /** Packs `(tag, value)` pairs as `tag, length, value` records, like OpenKart's `pack_tlv`. */
    private fun packTlv(tags: List<Pair<Int, ByteArray>>): ByteArray {
        var packed = ByteArray(0)
        for ((tag, value) in tags) {
            packed += byteArrayOf(tag.toByte(), value.size.toByte()) + value
        }
        return packed
    }

    companion object {
        /** The console-side local-communication id baked into every LP2P beacon. */
        const val LOCAL_COMMUNICATION_ID: Long = 0x0100ED100BA3A000L

        private const val NONCE_BYTES = 16
        private const val AD0_BYTES = 0x20
        private const val RANDOM_BYTES = 4
        private const val IV_ZERO_PAD_BYTES = 8
        private const val GCM_TAG_BYTES = 16
        private const val MAX_ELEMENT_PAYLOAD = 255

        private const val ELEMENT_ID_VENDOR_SPECIFIC: Byte = 0xDD.toByte()
        private const val OUI_0: Byte = 0x00
        private const val OUI_1: Byte = 0x22
        private const val OUI_2: Byte = 0xAA.toByte()
        private const val OUI_TYPE: Byte = 0x06

        /** System TLV: tag 1 = `20 13`, tag 2 = 8-byte big-endian value 4. */
        private val TLV: List<Pair<Int, ByteArray>> = listOf(
            1 to byteArrayOf(0x20, 0x13),
            2 to byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x04),
        )

        /** Application TLV: tag 0x21 with an empty value. */
        private val APP_TLV: List<Pair<Int, ByteArray>> = listOf(
            0x21 to ByteArray(0),
        )

        private fun Long.toBigEndianBytes(): ByteArray = ByteArray(Long.SIZE_BYTES) { index ->
            (this ushr ((Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
        }
    }
}
