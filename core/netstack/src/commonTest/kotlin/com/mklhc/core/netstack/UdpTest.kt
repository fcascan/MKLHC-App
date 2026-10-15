package com.mklhc.core.netstack

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the UDP codec ([UdpDatagram]) against RFC 768, including the IPv4 pseudo-header
 * checksum.
 *
 * The expected wire forms and checksums (0x7889, 0x3aef, the zero-folds-to-0xffff case) were
 * computed independently of this code with a separate script, then written out here by hand.
 */
class UdpTest {

    private val ap = AddressPlan.AP
    private val kart = AddressPlan.KART_LEASE

    /** "hello" from port 5000 to 6000, length 13, checksum 0x7889 over the AP -> kart pseudo-header. */
    private val helloHex = "1388" + "1770" + "000d" + "7889" + "68656c6c6f"

    private fun hello() = UdpDatagram(sourcePort = 5000, destinationPort = 6000, payload = "hello".encodeToByteArray())

    @Test
    fun encodeWritesPortsLengthAndPseudoHeaderChecksum() {
        assertEquals(helloHex, hello().encode(ap, kart).toHexString())
    }

    @Test
    fun parseReadsThePortsAndPayloadAndValidatesTheChecksum() {
        val datagram = assertNotNull(UdpDatagram.parse(helloHex.hexToByteArray(), ap, kart))

        assertEquals(5000, datagram.sourcePort)
        assertEquals(6000, datagram.destinationPort)
        assertContentEquals("hello".encodeToByteArray(), datagram.payload)
        assertEquals(0x7889, datagram.checksum)
    }

    @Test
    fun theChecksumOverThePseudoHeaderAndTheDatagramValidates() {
        assertTrue(UdpDatagram.hasValidChecksum(helloHex.hexToByteArray(), ap, kart))
    }

    @Test
    fun aChecksumComputedForOtherAddressesFailsValidation() {
        val wrongDestination = Ipv4Address.parse("169.254.98.67")

        assertFalse(UdpDatagram.hasValidChecksum(helloHex.hexToByteArray(), ap, wrongDestination))
        assertNull(UdpDatagram.parse(helloHex.hexToByteArray(), ap, wrongDestination))
    }

    @Test
    fun aCorruptedPayloadFailsValidation() {
        val wire = helloHex.hexToByteArray()
        wire[wire.size - 1] = 'x'.code.toByte()

        assertNull(UdpDatagram.parse(wire, ap, kart))
    }

    @Test
    fun parseWithoutAddressesSkipsTheOptionalPseudoHeaderCheck() {
        val wire = helloHex.hexToByteArray()
        wire[wire.size - 1] = 'x'.code.toByte()

        assertNotNull(UdpDatagram.parse(wire))
    }

    @Test
    fun aZeroChecksumMeansDisabledAndIsAcceptedEvenWhenAddressesAreGiven() {
        val noChecksum = "1388" + "1770" + "000d" + "0000" + "68656c6c6f"

        val datagram = assertNotNull(UdpDatagram.parse(noChecksum.hexToByteArray(), ap, kart))

        assertEquals(0, datagram.checksum)
        assertContentEquals("hello".encodeToByteArray(), datagram.payload)
    }

    @Test
    fun encodeCanOmitTheChecksum() {
        val wire = hello().encode(ap, kart, computeChecksum = false)

        assertEquals("1388" + "1770" + "000d" + "0000" + "68656c6c6f", wire.toHexString())
    }

    @Test
    fun aComputedChecksumOfZeroIsTransmittedAsAllOnes() {
        // For this payload the one's complement checksum is 0, which RFC 768 sends as 0xffff.
        val datagram = UdpDatagram(5000, 6000, "bc61".hexToByteArray())

        val wire = datagram.encode(ap, kart)

        assertEquals("1388" + "1770" + "000a" + "ffff" + "bc61", wire.toHexString())
        assertNotNull(UdpDatagram.parse(wire, ap, kart))
    }

    @Test
    fun theDhcpStyleBroadcastPseudoHeaderChecksumMatches() {
        val datagram = UdpDatagram(sourcePort = 68, destinationPort = 67, payload = "abc".encodeToByteArray())

        val wire = datagram.encode(Ipv4Address.ANY, Ipv4Address.BROADCAST)

        assertEquals("0044" + "0043" + "000b" + "3aef" + "616263", wire.toHexString())
    }

    @Test
    fun parseTrimsPaddingBeyondTheUdpLength() {
        val padded = helloHex.hexToByteArray() + ByteArray(7)

        val datagram = assertNotNull(UdpDatagram.parse(padded, ap, kart))

        assertContentEquals("hello".encodeToByteArray(), datagram.payload)
    }

    @Test
    fun parseRejectsTruncatedDatagrams() {
        val wire = helloHex.hexToByteArray()

        assertNull(UdpDatagram.parse(wire.copyOf(7)), "shorter than the 8-byte header")
        assertNull(UdpDatagram.parse(wire.copyOf(wire.size - 1)), "length field larger than the bytes available")
        assertNull(UdpDatagram.parse(("1388" + "1770" + "0007" + "0000").hexToByteArray()), "length below the header size")
    }

    @Test
    fun aUdpDatagramSurvivesAWholeTripThroughIpv4() {
        val udp = hello().encode(ap, kart)
        val ipWire = Ipv4Packet(
            source = ap,
            destination = kart,
            protocol = Ipv4Packet.PROTOCOL_UDP,
            payload = udp,
            identification = 0x1234,
        ).encode()

        val ip = assertNotNull(Ipv4Packet.parse(ipWire))
        val datagram = assertNotNull(UdpDatagram.parse(ip.payload, ip.source, ip.destination))

        assertEquals(Ipv4Packet.PROTOCOL_UDP, ip.protocol)
        assertEquals(6000, datagram.destinationPort)
        assertContentEquals("hello".encodeToByteArray(), datagram.payload)
    }
}
