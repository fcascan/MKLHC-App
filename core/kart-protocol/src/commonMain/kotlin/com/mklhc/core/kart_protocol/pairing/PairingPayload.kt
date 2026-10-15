// Layout per a community protocol wiki (Fuji kart protocol); format spec, no code copied.
package com.mklhc.core.kart_protocol.pairing

/** Octets in the pairing QR payload (0x3E). */
const val PAIRING_PAYLOAD_SIZE = 0x3E

/** Octets of the seed field at the start of the payload. */
const val PAIRING_SEED_SIZE = 0x10

/** Octets of the zero-padded SSID field. */
const val PAIRING_SSID_FIELD_SIZE = 0x20

private const val SSID_OFFSET = 0x10
private const val CHANNEL_OFFSET = 0x30

/**
 * Builds the 62-octet payload the app encodes into the QR code it shows to pair a kart.
 *
 * Business logic: to pair, the app displays a QR code that the kart scans. The code carries a 16-octet
 * pairing seed, the SSID of the pairing network and the channel to use. This function produces only the
 * payload octets; drawing the QR image is a UI task. Layout:
 *
 * ```
 * 0x00 (0x10)  seed
 * 0x10 (0x20)  pairing SSID, UTF-8, zero-padded to 0x20 octets
 * 0x30 (0x02)  channel, u16 little-endian (0 = the current channel)
 * 0x32 (0x0C)  zero padding
 * ```
 *
 * The seed is secret key material: it is validated for size but never logged or echoed in an error.
 *
 * @param seed the pairing seed, exactly [PAIRING_SEED_SIZE] octets.
 * @param pairingSsid the pairing network name; at most [PAIRING_SSID_FIELD_SIZE] octets once UTF-8
 *   encoded (so a non-ASCII name holds fewer characters).
 * @param channel the Wi-Fi channel to join on, 0..65535; 0 means the kart uses the current channel.
 * @return a new [PAIRING_PAYLOAD_SIZE]-octet array.
 * @throws IllegalArgumentException if [seed] is not 16 octets, [pairingSsid] needs more than 32 octets,
 *   or [channel] does not fit in 16 bits.
 */
fun encodePairingPayload(seed: ByteArray, pairingSsid: String, channel: Int = 0): ByteArray {
    require(seed.size == PAIRING_SEED_SIZE) { "pairing seed must be $PAIRING_SEED_SIZE octets, got ${seed.size}" }
    val ssid = pairingSsid.encodeToByteArray()
    require(ssid.size <= PAIRING_SSID_FIELD_SIZE) {
        "pairing SSID needs ${ssid.size} octets in UTF-8, the field holds $PAIRING_SSID_FIELD_SIZE"
    }
    require(channel in 0..0xFFFF) { "channel $channel does not fit in 16 bits" }

    val payload = ByteArray(PAIRING_PAYLOAD_SIZE)
    seed.copyInto(payload, destinationOffset = 0)
    ssid.copyInto(payload, destinationOffset = SSID_OFFSET)
    payload[CHANNEL_OFFSET] = channel.toByte()
    payload[CHANNEL_OFFSET + 1] = (channel ushr 8).toByte()
    return payload
}
