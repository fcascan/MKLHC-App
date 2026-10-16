package com.mklhc.app.spike

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.DhcpMessage
import com.mklhc.core.netstack.DhcpMessageType
import com.mklhc.core.netstack.DhcpOption
import com.mklhc.core.netstack.DhcpServer
import com.mklhc.core.netstack.Ipv4Address
import com.mklhc.core.netstack.Ipv4Packet
import com.mklhc.core.netstack.UdpDatagram
import com.mklhc.core.wifi_ap.crypto.Ccmp
import com.mklhc.core.wifi_ap.mac.FrameControl
import com.mklhc.core.wifi_ap.mac.Ieee80211
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.InformationElements
import com.mklhc.core.wifi_ap.mac.LlcSnap
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.core.wifi_ap.mac.SequenceControl
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The DHCP transaction id every fake DISCOVER and REQUEST uses. */
internal const val DISCOVER_XID = 0x3903F326

/**
 * The kart's side of the conversation, for tests: builds the 802.11 frames a station sends to the access point
 * (authentication, association, CCMP-protected data) and reads what the access point sends back.
 *
 * Business logic: the spike tests need a station that speaks just enough 802.11 and DHCP. It encrypts with the
 * network's static CCMP key like the real kart does, numbering frames with the packet number the caller picks.
 *
 * @param ccmpKey the network's 16-byte static CCMP key.
 * @param bssid the access point's BSSID.
 * @param mac the station's own address.
 */
internal class FakeStation(
    private val ccmpKey: ByteArray,
    private val bssid: MacAddress,
    val mac: MacAddress = DEFAULT_MAC,
    private val primitives: CryptoPrimitives = CryptoPrimitives(),
) {
    private val ccmp = Ccmp(primitives)
    private var sequence = 0

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

    private fun management(subtype: Int, body: ByteArray, retry: Boolean = false): ByteArray = Ieee80211Frame(
        frameControl = FrameControl.of(Ieee80211.TYPE_MANAGEMENT, subtype, retry = retry),
        addr1 = bssid,
        addr2 = mac,
        addr3 = bssid,
        body = body,
    ).toBytes()

    fun authRequest(retry: Boolean = false): ByteArray =
        management(Ieee80211.SUBTYPE_AUTH, le16(0) + le16(1) + le16(0), retry)

    fun assocRequest(ssid: String, retry: Boolean = false): ByteArray = management(
        Ieee80211.SUBTYPE_ASSOC_REQUEST,
        le16(0x0011) + le16(10) + InformationElements.ssid(ssid.encodeToByteArray()) +
            InformationElements.supportedRates("82848b96".hexToByteArray()),
        retry,
    )

    fun deauth(): ByteArray = management(Ieee80211.SUBTYPE_DEAUTH, le16(3))

    /** A CCMP-protected to-DS data frame carrying [body] (LLC/SNAP and above) to the broadcast address. */
    fun encryptedData(body: ByteArray, pn: Long): ByteArray {
        val header = Ieee80211Frame(
            frameControl = FrameControl.of(Ieee80211.TYPE_DATA, Ieee80211.SUBTYPE_DATA, toDs = true, protectedFrame = true),
            addr1 = bssid,
            addr2 = mac,
            addr3 = MacAddress.BROADCAST,
            sequenceControl = SequenceControl.of(sequence++),
        ).toBytes()
        val packetNumber = ByteArray(6) { index -> (pn shr ((5 - index) * 8)).toByte() }
        return ccmp.encrypt(ccmpKey, header, body, packetNumber)
    }

    /** A protected data frame carrying [dhcpPayload] as UDP 68 to 67 from 0.0.0.0 to 255.255.255.255. */
    fun dhcpFrame(dhcpPayload: ByteArray, pn: Long): ByteArray {
        val udp = UdpDatagram(DhcpServer.CLIENT_PORT, DhcpServer.SERVER_PORT, dhcpPayload)
            .encode(Ipv4Address.ANY, Ipv4Address.BROADCAST)
        val ip = Ipv4Packet(Ipv4Address.ANY, Ipv4Address.BROADCAST, Ipv4Packet.PROTOCOL_UDP, udp).encode()
        return encryptedData(LlcSnap.wrap(0x0800, ip), pn)
    }

    /** A DHCP DISCOVER with the broadcast flag set. */
    fun discover(): ByteArray = clientMessage(DhcpMessageType.DISCOVER, emptyMap())

    /** A DHCP REQUEST for the one lease, naming the access point as the server. */
    fun request(): ByteArray = clientMessage(
        DhcpMessageType.REQUEST,
        mapOf(
            DhcpOption.REQUESTED_IP to AddressPlan.KART_LEASE.toByteArray(),
            DhcpOption.SERVER_IDENTIFIER to AddressPlan.AP.toByteArray(),
        ),
    )

    private fun clientMessage(type: Int, extraOptions: Map<Int, ByteArray>): ByteArray = DhcpMessage(
        op = DhcpMessage.OP_REQUEST,
        xid = DISCOVER_XID,
        clientMac = mac.toByteArray(),
        flags = DhcpMessage.FLAG_BROADCAST,
        options = mapOf(DhcpOption.MESSAGE_TYPE to byteArrayOf(type.toByte())) + extraOptions,
    ).encode()

    /** Decrypts a frame the access point transmitted (as the kart would) and parses the DHCP message inside it. */
    fun dhcpFromAp(mpdu: ByteArray): DhcpMessage =
        dhcpFromBody(Ccmp(primitives).decrypt(ccmpKey, mpdu))

    companion object {
        val DEFAULT_MAC: MacAddress = MacAddress.parse("02:aa:bb:cc:dd:01")
    }
}

/** Parses LLC/SNAP, IPv4, UDP and DHCP out of a plaintext data frame body. */
internal fun dhcpFromBody(body: ByteArray): DhcpMessage {
    val snap = assertNotNull(LlcSnap.unwrap(body))
    val ip = assertNotNull(Ipv4Packet.parse(snap.payload))
    val udp = assertNotNull(UdpDatagram.parse(ip.payload, ip.source, ip.destination))
    return assertNotNull(DhcpMessage.parse(udp.payload))
}

/** One decoded pcap record: its timestamp and the 802.11 frame (the radiotap prefix is stripped). */
internal class PcapFrame(val tsSec: Long, val tsUsec: Long, val frame: ByteArray)

/** A decoded pcap stream: the link type of its global header and its records. */
internal class PcapCapture(val linkType: Int, val frames: List<PcapFrame>)

/**
 * Decodes a classic little-endian pcap stream, asserting the framing on the way: the magic number, the version, one
 * radiotap prefix per record, `orig_len == incl_len` (nothing is truncated at the default snap length) and records
 * that tile the stream exactly.
 */
internal fun parsePcap(bytes: ByteArray): PcapCapture {
    fun u32(offset: Int): Long = (0 until 4).sumOf { index -> (bytes[offset + index].toLong() and 0xFF) shl (index * 8) }

    assertTrue(bytes.size >= 24, "the global header is always written")
    assertEquals(0xA1B2C3D4L, u32(0), "magic")
    assertEquals(0x00040002L, u32(4), "version 2.4")
    val linkType = u32(20).toInt()
    val frames = ArrayList<PcapFrame>()
    var offset = 24
    while (offset < bytes.size) {
        val includedLength = u32(offset + 8).toInt()
        assertEquals(includedLength.toLong(), u32(offset + 12), "orig_len equals incl_len when nothing is truncated")
        val packet = bytes.copyOfRange(offset + 16, offset + 16 + includedLength)
        assertEquals("0000080000000000", packet.copyOfRange(0, 8).toHexString(), "radiotap prefix")
        frames += PcapFrame(u32(offset), u32(offset + 4), packet.copyOfRange(8, packet.size))
        offset += 16 + includedLength
    }
    assertEquals(bytes.size, offset, "the records must tile the stream exactly")
    return PcapCapture(linkType, frames)
}
