// Design reference: devourer ap_responder.cpp; no code copied.
package com.mklhc.core.wifi_ap.ap

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.wifi_ap.crypto.Ccmp
import com.mklhc.core.wifi_ap.crypto.CcmpException
import com.mklhc.core.wifi_ap.mac.BeaconBuilder
import com.mklhc.core.wifi_ap.mac.Ieee80211
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.InformationElements
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.core.wifi_ap.mac.ReasonCodes
import com.mklhc.core.wifi_ap.mac.StatusCodes

/** Where the single-station access point stands: advertising only, or serving one associated station. */
enum class ApState {
    /** Only beaconing (and answering probes / authentication); no station is associated. */
    Beaconing,

    /** One station is associated; any other station is turned away. */
    Associated,
}

/**
 * Single-station 802.11 access point state machine (pure logic, no I/O).
 *
 * Design reference: devourer ap_responder.cpp; no code copied.
 *
 * Business logic: the kart is the only station that ever joins the LP2P network, so the AP admits
 * exactly one. The machine consumes received frames through [onFrame] and returns the frames the
 * caller should transmit; the radio's hardware ACK engine acknowledges everything, so nothing is
 * ever sent merely to acknowledge. The behaviour:
 *
 * - Probe request: answered with a probe response carrying the real SSID only when the request
 *   names exactly our SSID and is addressed to our BSSID or broadcast. A wildcard (empty SSID)
 *   probe is ignored because the AP is hidden (hostapd `ignore_broadcast_ssid=2`).
 * - Authentication: Open System (algorithm 0, transaction 1) is answered with algorithm 0,
 *   transaction 2, status 0 and the station is remembered as authenticated. Other algorithms or
 *   transactions are refused with status 13 / 14. A different station is refused with status 17
 *   (AP full) while one is associated.
 * - (Re)association request: from the authenticated station it is answered with status 0 and AID 1
 *   and the state becomes [ApState.Associated]. From a different station while one is associated
 *   it is answered with status 17 and the associated station is NOT replaced. From a station that
 *   never authenticated it is answered with a deauthentication (reason 6).
 * - Deauthentication / disassociation from the associated (or authenticated) station drops it and
 *   returns the machine to [ApState.Beaconing]; nothing is transmitted. The same frames from any
 *   other station are ignored.
 * - Frames not addressed to our BSSID, malformed frames, control frames, and data frames are
 *   ignored by [onFrame]; the data plane uses [decryptData] and [encryptData].
 *
 * Data plane: a single static 16-byte key (the config's CCMP key) protects every data frame, as in
 * OpenKart's hostapd patch. [decryptData] accepts any key id 0-3 (the one static key serves every
 * value of the 2-bit Key ID field) and enforces the 48-bit packet-number replay window (reset
 * whenever a station (re)associates or is dropped, because a station restarts its packet numbers);
 * [encryptData] uses key id 0 and a monotonically increasing packet number starting at [initialTxPn].
 *
 * Sequence numbers for transmitted frames come from one 12-bit counter. The beacon is static
 * (sequence 0) because the radio repeats it verbatim.
 *
 * Not thread-safe: drive it from a single coroutine / thread.
 *
 * @param config the BSS to serve.
 * @param primitives the crypto primitives backing CCMP.
 * @param initialTxPn the first CCMP packet number used by [encryptData] (1 by default).
 */
class ApStateMachine(
    private val config: ApConfig,
    primitives: CryptoPrimitives = CryptoPrimitives(),
    initialTxPn: Long = 1L,
) {

    private val bssid: MacAddress = config.bssid
    private val ssidBytes: ByteArray = config.ssid.encodeToByteArray()
    private val builder = BeaconBuilder(
        bssid = config.bssid,
        ssid = config.ssid,
        channel = config.channel,
        beaconIntervalTu = config.beaconIntervalTu,
        vendorElements = config.network.vendorElements,
    )
    private val ccmp = Ccmp(primitives)

    /** The station that completed Open System authentication and has not associated yet (or has). */
    private var authenticatedStation: MacAddress? = null

    /** The associated station, or null while beaconing. */
    private var associatedStation: MacAddress? = null

    private var txSequence = 0
    private var txPn = initialTxPn

    /** [ApState.Associated] while a station is associated, otherwise [ApState.Beaconing]. */
    val state: ApState get() = if (associatedStation != null) ApState.Associated else ApState.Beaconing

    /** The associated station, or null while [state] is [ApState.Beaconing]. */
    val station: MacAddress? get() = associatedStation

    /** The associated station's AID (always [STATION_AID]), or null while beaconing. */
    val aid: Int? get() = if (associatedStation != null) STATION_AID else null

    /** The current beacon MPDU (hidden SSID, Privacy bit, LP2P vendor elements); the radio repeats it. */
    fun beacon(): ByteArray = builder.beacon()

    /**
     * Processes one received frame (FCS stripped) and returns the frames to transmit in response,
     * in order. Returns an empty list when nothing should be sent.
     */
    fun onFrame(frame: ByteArray): List<ByteArray> {
        val parsed = Ieee80211Frame.parse(frame) ?: return emptyList()
        if (!parsed.isManagement || parsed.addr2.isMulticast) return emptyList()
        return when {
            parsed.isProbeRequest -> onProbeRequest(parsed)
            parsed.isAuth -> onAuthentication(parsed)
            parsed.isAssocRequest || parsed.isReassocRequest -> onAssociationRequest(parsed)
            parsed.isDeauth || parsed.isDisassoc -> onDeparture(parsed)
            else -> emptyList()
        }
    }

    /**
     * Decrypts a protected data MPDU received from the station with the config's CCMP key and
     * returns its plaintext body (normally LLC/SNAP + payload).
     *
     * @throws CcmpException if the frame is not a protected data frame, the MIC fails, or the packet
     * number is a replay.
     */
    fun decryptData(mpdu: ByteArray): ByteArray {
        val frame = Ieee80211Frame.parse(mpdu)
        if (frame == null || !frame.isDataFrame) throw CcmpException("not an 802.11 data frame")
        if (!frame.isProtected) throw CcmpException("data frame is not protected")
        val ccmpHeaderEnd = frame.headerLength + CCMP_HEADER_BYTES
        if (mpdu.size < ccmpHeaderEnd) throw CcmpException("data frame too short for a CCMP header")
        // The static key serves every key id, so accept whichever id the station used.
        val keyId = (mpdu[frame.headerLength + CCMP_KEY_ID_OCTET].toInt() and 0xFF) ushr KEY_ID_SHIFT
        return ccmp.decrypt(config.network.ccmpKey, mpdu, keyId)
    }

    /**
     * Builds a CCMP-protected from-DS data MPDU carrying [body] (normally LLC/SNAP + payload) from
     * [source] to the station [destination]: addr1 = destination, addr2 = BSSID, addr3 = source,
     * Protected bit set, key id 0 and the next packet number.
     *
     * @throws IllegalStateException if the 48-bit packet-number space is exhausted.
     */
    fun encryptData(destination: MacAddress, source: MacAddress, body: ByteArray): ByteArray {
        check(txPn in 0..MAX_PN) { "CCMP packet number space exhausted" }
        val pn = ByteArray(PN_BYTES) { index -> (txPn shr ((PN_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte() }
        txPn++
        val header = Ieee80211.fromDsDataHeader(
            station = destination,
            bssid = bssid,
            source = source,
            protectedFrame = true,
            sequenceNumber = nextSequenceNumber(),
        )
        return ccmp.encrypt(config.network.ccmpKey, header, body, pn, keyId = 0)
    }

    private fun onProbeRequest(frame: Ieee80211Frame): List<ByteArray> {
        val toUsOrBroadcast = (frame.addr1 == bssid || frame.addr1.isBroadcast) &&
            (frame.addr3 == bssid || frame.addr3.isBroadcast)
        if (!toUsOrBroadcast) return emptyList()
        val requested = InformationElements.find(frame.probeRequestElements(), InformationElements.SSID)
            ?: return emptyList()
        // Hidden AP: an empty (wildcard) SSID gets no answer; only the exact SSID does.
        if (requested.value.isEmpty() || !requested.value.contentEquals(ssidBytes)) return emptyList()
        return listOf(builder.probeResponse(frame.addr2, sequenceNumber = nextSequenceNumber()))
    }

    private fun onAuthentication(frame: Ieee80211Frame): List<ByteArray> {
        if (!isAddressedToUs(frame)) return emptyList()
        val fields = frame.authentication() ?: return emptyList()
        val sender = frame.addr2

        val status = when {
            associatedStation != null && sender != associatedStation -> StatusCodes.AP_FULL
            fields.algorithm != Ieee80211.AUTH_ALGORITHM_OPEN_SYSTEM -> StatusCodes.UNSUPPORTED_AUTH_ALGORITHM
            fields.transactionSequence != OPEN_AUTH_REQUEST_TRANSACTION -> StatusCodes.UNKNOWN_AUTH_TRANSACTION
            else -> StatusCodes.SUCCESS
        }
        if (status == StatusCodes.SUCCESS) {
            // A station that authenticates again starts over: it is no longer associated.
            dropAssociation()
            authenticatedStation = sender
        }
        return listOf(
            Ieee80211.authenticationResponse(
                station = sender,
                bssid = bssid,
                algorithm = fields.algorithm,
                transactionSequence = (fields.transactionSequence + 1) and 0xFFFF,
                statusCode = status,
                sequenceNumber = nextSequenceNumber(),
            ),
        )
    }

    private fun onAssociationRequest(frame: Ieee80211Frame): List<ByteArray> {
        if (!isAddressedToUs(frame)) return emptyList()
        if (frame.associationRequest() == null) return emptyList()
        val sender = frame.addr2
        val reassociation = frame.isReassocRequest

        if (associatedStation != null && sender != associatedStation) {
            return listOf(associationResponse(sender, reassociation, StatusCodes.AP_FULL, aid = 0))
        }
        if (sender != associatedStation && sender != authenticatedStation) {
            return listOf(
                Ieee80211.deauthentication(
                    station = sender,
                    bssid = bssid,
                    reasonCode = ReasonCodes.CLASS2_FRAME_FROM_NONAUTH_STA,
                    sequenceNumber = nextSequenceNumber(),
                ),
            )
        }
        associatedStation = sender
        authenticatedStation = sender
        // The station restarts its CCMP packet numbers on (re)association.
        ccmp.resetReplay()
        return listOf(associationResponse(sender, reassociation, StatusCodes.SUCCESS, STATION_AID))
    }

    private fun onDeparture(frame: Ieee80211Frame): List<ByteArray> {
        if (!isAddressedToUs(frame)) return emptyList()
        val sender = frame.addr2
        if (sender == associatedStation || sender == authenticatedStation) {
            dropAssociation()
            authenticatedStation = null
        }
        return emptyList()
    }

    private fun associationResponse(station: MacAddress, reassociation: Boolean, status: Int, aid: Int): ByteArray =
        Ieee80211.associationResponse(
            station = station,
            bssid = bssid,
            capabilityInfo = builder.capabilityInfo,
            statusCode = status,
            aid = aid,
            elements = builder.rateElements(),
            reassociation = reassociation,
            sequenceNumber = nextSequenceNumber(),
        )

    private fun dropAssociation() {
        associatedStation = null
        ccmp.resetReplay()
    }

    /** Authentication, association and (de)authentication frames must be addressed to, and name, our BSSID. */
    private fun isAddressedToUs(frame: Ieee80211Frame): Boolean = frame.addr1 == bssid && frame.addr3 == bssid

    private fun nextSequenceNumber(): Int {
        val current = txSequence
        txSequence = (txSequence + 1) and SEQUENCE_MASK
        return current
    }

    private companion object {
        const val STATION_AID = 1
        const val OPEN_AUTH_REQUEST_TRANSACTION = 1
        const val SEQUENCE_MASK = 0xFFF
        const val PN_BYTES = 6
        const val MAX_PN = 0xFFFFFFFFFFFFL
        const val CCMP_HEADER_BYTES = 8
        const val CCMP_KEY_ID_OCTET = 3
        const val KEY_ID_SHIFT = 6
    }
}
