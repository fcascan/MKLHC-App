// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

/**
 * A device that has completed (or is completing) the RCD handshake.
 *
 * Business logic: produced by [RcdHandshakeService] once a handshake succeeds. It carries the
 * device's identity and the credentials derived for it ([pairingId], [secretKey]) plus the negotiated
 * handshake [version]. [version] is null until the version-negotiation step has run.
 *
 * @property name the device's display name, decoded from the handshake.
 * @property ident the device's 16-octet identity.
 * @property pairingId the 32-octet pairing id derived for this device.
 * @property secretKey the 64-octet secret key derived for this device.
 * @property address the transport address the device connected from.
 * @property version the negotiated handshake-protocol version, or null before negotiation.
 */
data class RcdDevice(
    val name: String,
    val ident: ByteArray,
    val pairingId: ByteArray,
    val secretKey: ByteArray,
    val address: String,
    val version: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RcdDevice) return false
        return name == other.name &&
            ident.contentEquals(other.ident) &&
            pairingId.contentEquals(other.pairingId) &&
            secretKey.contentEquals(other.secretKey) &&
            address == other.address &&
            version == other.version
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + ident.contentHashCode()
        result = 31 * result + pairingId.contentHashCode()
        result = 31 * result + secretKey.contentHashCode()
        result = 31 * result + address.hashCode()
        result = 31 * result + (version ?: 0)
        return result
    }
}

/**
 * Callback for device connect/disconnect events raised by [RcdHandshakeService].
 *
 * [deviceConnected] fires when a handshake completes; [deviceDisconnected] fires when a service that
 * had completed its handshake is closed.
 */
interface RcdDeviceHandler {
    fun deviceConnected(device: RcdDevice)
    fun deviceDisconnected(device: RcdDevice)
}
