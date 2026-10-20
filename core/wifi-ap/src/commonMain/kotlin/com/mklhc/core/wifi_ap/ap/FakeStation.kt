package com.mklhc.core.wifi_ap.ap

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.DhcpMessage
import com.mklhc.core.netstack.DhcpMessageType
import com.mklhc.core.netstack.DhcpOption
import com.mklhc.core.netstack.DhcpServer
import com.mklhc.core.netstack.EtherType
import com.mklhc.core.netstack.Ipv4Address
import com.mklhc.core.netstack.Ipv4Packet
import com.mklhc.core.netstack.LinkPayload
import com.mklhc.core.netstack.UdpDatagram
import com.mklhc.core.wifi_ap.crypto.Ccmp
import com.mklhc.core.wifi_ap.crypto.CcmpException
import com.mklhc.core.wifi_ap.mac.FrameControl
import com.mklhc.core.wifi_ap.mac.Ieee80211
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.InformationElements
import com.mklhc.core.wifi_ap.mac.LlcSnap
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.core.wifi_ap.mac.ReasonCodes
import com.mklhc.core.wifi_ap.mac.SequenceControl

/*
 * A station (the kart's side of the link) for tests.
 *
 * Original code, moved from the radio hardware spike's test support
 * (app/src/desktopTest/kotlin/com/mklhc/app/spike/SpikeTestSupport.kt) into main sources. It only drives codecs that
 * already exist: the 802.11 frame builders, CCMP, LLC/SNAP and the netstack's IPv4 / UDP / DHCP codecs.
 */

/**
 * The kart's side of the conversation, as a test double: builds the 802.11 frames a station sends to the access point
 * (authentication, association, deauthentication, CCMP-protected data) and reads what the access point sends back.
 *
 * Business logic: tests of the access point, and of anything built on it, need a station that speaks just enough
 * 802.11, CCMP and DHCP. It encrypts with the network's static CCMP key like the real kart does, numbering frames with
 * the packet number the caller picks, so a test controls replay behaviour exactly. It is shipped in main sources, like
 * [FakeRadioLink][com.mklhc.core.radio.FakeRadioLink] in `:core:radio`, so other modules' tests (the data layer, the
 * spike) can play the station too; it uses no test library and reports what it cannot decode as null.
 *
 * Not thread-safe: drive it from one coroutine / thread.
 *
 * @param ccmpKey the network's 16-byte static CCMP key (a copy is kept).
 * @param bssid the access point's BSSID.
 * @property mac the station's own address.
 * @param primitives the crypto provider for CCMP.
 */
class FakeStation(
    ccmpKey: ByteArray,
    private val bssid: MacAddress,
    val mac: MacAddress = DEFAULT_MAC,
    private val primitives: CryptoPrimitives = CryptoPrimitives(),
) {
    private val key: ByteArray = ccmpKey.copyOf()
    private val ccmp = Ccmp(primitives)
    private var sequence = 0

    /** An Open System authentication request (algorithm 0, transaction 1), with the Retry bit when [retry] is set. */
    fun authRequest(retry: Boolean = false): ByteArray = management(
        Ieee80211.SUBTYPE_AUTH,
        le16(Ieee80211.AUTH_ALGORITHM_OPEN_SYSTEM) + le16(AUTH_REQUEST_TRANSACTION) + le16(STATUS_SUCCESS),
        retry,
    )

    /** An association request for [ssid] advertising the 802.11b basic rates, with the Retry bit when [retry] is set. */
    fun assocRequest(ssid: String, retry: Boolean = false): ByteArray = management(
        Ieee80211.SUBTYPE_ASSOC_REQUEST,
        le16(CAPABILITY_ESS_PRIVACY) + le16(LISTEN_INTERVAL) + InformationElements.ssid(ssid.encodeToByteArray()) +
            InformationElements.supportedRates(BASIC_RATES),
        retry,
    )

    /** A deauthentication with [reasonCode] (by default "leaving"). */
    fun deauth(reasonCode: Int = ReasonCodes.DEAUTH_LEAVING): ByteArray =
        management(Ieee80211.SUBTYPE_DEAUTH, le16(reasonCode))

    /**
     * A CCMP-protected to-DS data frame carrying [body] (LLC/SNAP and above): addr1 = the BSSID, addr2 = this
     * station, addr3 = [destination], key id 0 and the 48-bit packet number [pn].
     */
    fun encryptedData(body: ByteArray, pn: Long, destination: MacAddress = MacAddress.BROADCAST): ByteArray {
        val header = Ieee80211Frame(
            frameControl = FrameControl.of(Ieee80211.TYPE_DATA, Ieee80211.SUBTYPE_DATA, toDs = true, protectedFrame = true),
            addr1 = bssid,
            addr2 = mac,
            addr3 = destination,
            sequenceControl = SequenceControl.of(sequence++),
        ).toBytes()
        val packetNumber = ByteArray(PN_BYTES) { index -> (pn shr ((PN_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte() }
        return ccmp.encrypt(key, header, body, packetNumber)
    }

    /** A protected data frame carrying [payload] (an ARP or IPv4 packet) LLC/SNAP-wrapped with [etherType]. */
    fun linkFrame(etherType: Int, payload: ByteArray, pn: Long, destination: MacAddress = MacAddress.BROADCAST): ByteArray =
        encryptedData(LlcSnap.wrap(etherType, payload), pn, destination)

    /** A protected data frame carrying [dhcpPayload] as UDP 68 to 67 from 0.0.0.0 to 255.255.255.255. */
    fun dhcpFrame(dhcpPayload: ByteArray, pn: Long): ByteArray {
        val udp = UdpDatagram(DhcpServer.CLIENT_PORT, DhcpServer.SERVER_PORT, dhcpPayload)
            .encode(Ipv4Address.ANY, Ipv4Address.BROADCAST)
        val ip = Ipv4Packet(Ipv4Address.ANY, Ipv4Address.BROADCAST, Ipv4Packet.PROTOCOL_UDP, udp).encode()
        return linkFrame(EtherType.IPV4, ip, pn)
    }

    /** A DHCP DISCOVER (transaction [DISCOVER_XID]) with the broadcast flag set. */
    fun discover(): ByteArray = clientMessage(DhcpMessageType.DISCOVER, emptyMap())

    /** A DHCP REQUEST (transaction [DISCOVER_XID]) for the one lease, naming the access point as the server. */
    fun request(): ByteArray = clientMessage(
        DhcpMessageType.REQUEST,
        mapOf(
            DhcpOption.REQUESTED_IP to AddressPlan.KART_LEASE.toByteArray(),
            DhcpOption.SERVER_IDENTIFIER to AddressPlan.AP.toByteArray(),
        ),
    )

    /**
     * Decrypts a frame the access point transmitted to this station (as the kart would) and returns the payload
     * inside its LLC/SNAP header, or null when it is not a protected from-DS data frame from the BSSID to this
     * station, fails CCMP, or does not carry LLC/SNAP. Each call decrypts independently (no replay window).
     */
    fun fromAp(mpdu: ByteArray): LinkPayload? {
        val frame = Ieee80211Frame.parse(mpdu) ?: return null
        val toUs = frame.isDataFrame && frame.fromDs && frame.isProtected && frame.addr1 == mac && frame.addr2 == bssid
        if (!toUs) return null
        val body = try {
            Ccmp(primitives).decrypt(key, mpdu)
        } catch (rejected: CcmpException) {
            return null
        }
        val snap = LlcSnap.unwrap(body) ?: return null
        return LinkPayload(snap.etherType, snap.payload)
    }

    /** Decrypts a frame the access point transmitted ([fromAp]) and parses the DHCP message inside it, or null. */
    fun dhcpFromAp(mpdu: ByteArray): DhcpMessage? {
        val payload = fromAp(mpdu) ?: return null
        return if (payload.etherType == EtherType.IPV4) dhcpFromIpv4(payload.payload) else null
    }

    private fun management(subtype: Int, body: ByteArray, retry: Boolean = false): ByteArray = Ieee80211Frame(
        frameControl = FrameControl.of(Ieee80211.TYPE_MANAGEMENT, subtype, retry = retry),
        addr1 = bssid,
        addr2 = mac,
        addr3 = bssid,
        body = body,
    ).toBytes()

    private fun clientMessage(type: Int, extraOptions: Map<Int, ByteArray>): ByteArray = DhcpMessage(
        op = DhcpMessage.OP_REQUEST,
        xid = DISCOVER_XID,
        clientMac = mac.toByteArray(),
        flags = DhcpMessage.FLAG_BROADCAST,
        options = mapOf(DhcpOption.MESSAGE_TYPE to byteArrayOf(type.toByte())) + extraOptions,
    ).encode()

    private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr Byte.SIZE_BITS).toByte())

    companion object {
        /** The station address used when none is given. */
        val DEFAULT_MAC: MacAddress = MacAddress.parse("02:aa:bb:cc:dd:01")

        /** The DHCP transaction id every DISCOVER and REQUEST of a [FakeStation] uses. */
        const val DISCOVER_XID = 0x3903F326

        /**
         * Parses LLC/SNAP, IPv4, UDP and DHCP out of a plaintext data frame body (for example a frame read back from
         * a capture), or returns null when any layer is missing or malformed.
         */
        fun dhcpFromBody(body: ByteArray): DhcpMessage? {
            val snap = LlcSnap.unwrap(body) ?: return null
            return if (snap.etherType == EtherType.IPV4) dhcpFromIpv4(snap.payload) else null
        }

        private const val PN_BYTES = 6
        private const val AUTH_REQUEST_TRANSACTION = 1
        private const val STATUS_SUCCESS = 0
        private const val LISTEN_INTERVAL = 10

        /** Capability Information: ESS (bit 0) and Privacy (bit 4). */
        private const val CAPABILITY_ESS_PRIVACY = 0x0011

        /** 1, 2, 5.5 and 11 Mbit/s, each flagged as a basic rate (bit 7), in units of 500 kbit/s. */
        private val BASIC_RATES = byteArrayOf(0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte())

        private fun dhcpFromIpv4(bytes: ByteArray): DhcpMessage? {
            val ip = Ipv4Packet.parse(bytes) ?: return null
            if (ip.protocol != Ipv4Packet.PROTOCOL_UDP) return null
            val udp = UdpDatagram.parse(ip.payload, ip.source, ip.destination) ?: return null
            return DhcpMessage.parse(udp.payload)
        }
    }
}
