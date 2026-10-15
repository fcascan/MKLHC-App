// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/fuji.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.fuji

import co.touchlab.kermit.Logger
import com.mklhc.core.kart_protocol.rcd.RcdClient

/**
 * The client of the kart's Fuji Pairing service (RCD service 0x102, TCP port [FujiPorts.PAIRING]).
 *
 * Business logic: a kart in pairing mode exposes this service so the app can hand it the game network
 * to join. [setGroupInfo] sends the network's SSID and pre-shared key; on success the kart saves them,
 * resets and rejoins on that game network, so the pairing connection is expected to drop right after.
 * The request is built exactly as the reference does and sent through the supplied [RcdClient]; a
 * non-zero kart status surfaces as an [com.mklhc.core.kart_protocol.rcd.RcdError].
 *
 * @param rcd the RCD client connected to the kart's pairing port.
 */
class FujiPairingClient(private val rcd: RcdClient) {
    private val log = Logger.withTag(TAG)

    /**
     * Tells the kart which game network to join (command 1).
     *
     * The request is the SSID zero-padded to 0x20 octets followed by the 0x20-octet PSK. The PSK is a
     * secret and is never logged.
     *
     * @param ssid the game network name; its UTF-8 encoding must be shorter than 0x20 octets (the
     * reference group-info type rejects 0x20 or more, leaving room for the terminator).
     * @param psk the pre-shared key, exactly 0x20 octets.
     * @throws IllegalArgumentException if [psk] is not 0x20 octets or [ssid] is too long.
     */
    suspend fun setGroupInfo(ssid: String, psk: ByteArray) {
        require(psk.size == PSK_SIZE) { "psk must be $PSK_SIZE octets, was ${psk.size}" }
        val ssidBytes = ssid.encodeToByteArray()
        require(ssidBytes.size < SSID_FIELD_SIZE) { "ssid of ${ssidBytes.size} octets must be shorter than $SSID_FIELD_SIZE" }

        log.i { "setGroupInfo(): sending -> ssid=$ssid" }
        rcd.invoke(SERVICE_ID, CMD_SET_GROUP_INFO, padTo(ssidBytes, SSID_FIELD_SIZE) + psk)
    }

    companion object {
        /** The RCD service id of Fuji Pairing. */
        const val SERVICE_ID = 0x102

        private const val TAG = "FujiPairingClient"
        private const val CMD_SET_GROUP_INFO = 1
        private const val SSID_FIELD_SIZE = 0x20
        private const val PSK_SIZE = 0x20
    }
}
