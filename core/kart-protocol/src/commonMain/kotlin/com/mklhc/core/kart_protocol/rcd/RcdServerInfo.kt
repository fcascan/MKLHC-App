// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

import com.mklhc.core.crypto.CryptoPrimitives

/**
 * The server's own identity plus the master key it derives every device's pairing credentials from.
 *
 * Business logic: a device never learns the [masterKey]. Instead the server derives, per device, a
 * stable [pairing id][getPairingKeys] and a long-lived secret key from the master key and the
 * device's identity, so the master key can mint and recognize any number of device credentials
 * without storing per-device state. The derivation binds both the master key (as a prefix and a
 * suffix) and the device identity, so neither the device id nor a leaked pairing id lets anyone
 * recover the master key or forge another device's secret.
 *
 * @property name the server's display name; must encode to fewer than 16 octets (it is sent padded to
 *   16 in the handshake).
 * @property ident the server's 16-octet identity, sent verbatim in the handshake.
 * @property masterKey the secret root key (64 octets by convention) all pairing credentials derive
 *   from.
 * @property versions the handshake-protocol versions the server supports, most preferred first.
 */
class RcdServerInfo(
    val name: String,
    val ident: ByteArray,
    val masterKey: ByteArray,
    val versions: List<Int> = listOf(2, 1),
    private val crypto: CryptoPrimitives = CryptoPrimitives(),
) {
    init {
        require(ident.size == IDENT_SIZE) { "ident must have length 0x$IDENT_SIZE_HEX" }
        require(name.encodeToByteArray().size < IDENT_SIZE) { "name too long" }
    }

    /**
     * Derives the stable (pairing id, secret key) pair for the device identified by [deviceIdent]
     * (16 octets) and [deviceName].
     *
     * pairingId = SHA-256(masterKey || deviceIdent || deviceName || masterKey); secretKey =
     * SHA-512(masterKey || pairingId || masterKey). The device identity fed into the pairing id is
     * the raw [deviceIdent] followed by the UTF-8 bytes of [deviceName].
     *
     * @return the 32-octet pairing id and the 64-octet secret key.
     */
    fun getPairingKeys(deviceIdent: ByteArray, deviceName: String): Pair<ByteArray, ByteArray> {
        val deviceId = deviceIdent + deviceName.encodeToByteArray()
        val pairingId = crypto.sha256(masterKey + deviceId + masterKey)
        val secretKey = crypto.sha512(masterKey + pairingId + masterKey)
        return pairingId to secretKey
    }

    private companion object {
        const val IDENT_SIZE = 0x10
        const val IDENT_SIZE_HEX = "10"
    }
}
