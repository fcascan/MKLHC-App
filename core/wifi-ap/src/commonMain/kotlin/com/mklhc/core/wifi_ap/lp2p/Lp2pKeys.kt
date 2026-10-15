// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/lp2p.py (BSD-3-Clause). Copyright 2021 Sam Edwards.
package com.mklhc.core.wifi_ap.lp2p

import com.mklhc.core.wifi_ap.crypto.CryptoPrimitives

/**
 * LP2P key derivation: turns a 16-byte nonce and a secret into two 128-bit keys.
 *
 * Business logic: the LP2P access point and its clients never use the pre-shared key (PSK)
 * directly. Both sides compute `two_keys = HMAC-SHA256(HMAC-SHA256(nonce, psk), 02 01)` and split
 * the 32-byte result into two 16-byte halves. The first half (`ka`) is the CCMP temporal key for
 * the network; the second (`kc`) protects the application TLV in the beacon. The same function is
 * also run with the local-communication id as the secret to obtain the key that protects the
 * system TLV (see [Lp2pBeacon]).
 *
 * Ported from OpenKart `LP2PAP.derive_keys`; HMAC is delegated to [CryptoPrimitives], so no crypto
 * is reimplemented here.
 */
class Lp2pKeys(private val primitives: CryptoPrimitives) {

    /**
     * Derives the two 16-byte keys for [nonce] and [psk].
     *
     * HMAC argument order matches OpenKart: the inner call keys the MAC with the nonce and
     * authenticates the psk; the outer call keys it with that digest and authenticates `02 01`.
     *
     * @return `(ka, kc)`: the first and second 16 bytes of the outer HMAC output.
     */
    fun deriveKeys(nonce: ByteArray, psk: ByteArray): Pair<ByteArray, ByteArray> {
        val inner = primitives.hmacSha256(nonce, psk)
        val twoKeys = primitives.hmacSha256(inner, KDF_INFO)
        return twoKeys.copyOfRange(0, KEY_BYTES) to twoKeys.copyOfRange(KEY_BYTES, 2 * KEY_BYTES)
    }

    /** Derives the network's CCMP temporal key: the first key returned by [deriveKeys]. */
    fun deriveCcmpKey(nonce: ByteArray, psk: ByteArray): ByteArray = deriveKeys(nonce, psk).first

    private companion object {
        /** Constant HMAC message that domain-separates the two derived keys (`b"\x02\x01"`). */
        val KDF_INFO = byteArrayOf(0x02, 0x01)
        const val KEY_BYTES = 16
    }
}
