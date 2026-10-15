package com.mklhc.core.netstack

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the application UDP endpoints of [NetstackNode] ([UdpEndpoint], [NetstackNode.bindUdp]):
 * routing of inbound datagrams to the endpoint bound to the destination port, the receive queue
 * policy, and the UDP/IPv4 wrapping of what an endpoint sends.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent drives the node's dispatch loop under virtual time
class NetstackUdpTest {

    private val apMac = "021122334455".hexToByteArray()
    private val ap = AddressPlan.AP
    private val kart = AddressPlan.KART_LEASE
    private val config = NetstackConfig(ourMac = apMac)

    private fun TestScope.startedNode(link: FakeFrameLink): NetstackNode =
        NetstackNode(link, backgroundScope, config).also { it.start() }

    private fun FakeFrameLink.pushUdp(
        sourcePort: Int,
        destinationPort: Int,
        payload: String,
        sourceIp: Ipv4Address = kart,
        destinationIp: Ipv4Address = ap,
    ) = push(EtherType.IPV4, udpOverIpv4(sourceIp, sourcePort, destinationIp, destinationPort, payload.encodeToByteArray()))

    // ---- receiving ----------------------------------------------------------------------------

    @Test
    fun aDatagramForABoundPortIsDeliveredWithItsSourceAddressPortAndPayload() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)

        link.pushUdp(sourcePort = 5000, destinationPort = 6000, payload = "hello")

        val received = withTimeout(1.seconds) { udp.receive() }
        assertEquals(kart, received.sourceIp)
        assertEquals(5000, received.sourcePort)
        assertContentEquals("hello".encodeToByteArray(), received.payload)
    }

    @Test
    fun datagramsAreRoutedByDestinationPortToTheirOwnEndpoint() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)
        val teleop = node.bindUdp(6000)
        val telemetry = node.bindUdp(6001)

        link.pushUdp(5000, 6001, "telemetry")
        link.pushUdp(5000, 6000, "teleop")

        assertContentEquals("teleop".encodeToByteArray(), withTimeout(1.seconds) { teleop.receive() }.payload)
        assertContentEquals("telemetry".encodeToByteArray(), withTimeout(1.seconds) { telemetry.receive() }.payload)
    }

    @Test
    fun theInboundFlowYieldsTheSameDatagramsInArrivalOrder() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)

        link.pushUdp(5000, 6000, "one")
        link.pushUdp(5001, 6000, "two")

        val received = withTimeout(1.seconds) { udp.inbound.take(2).toList() }
        assertEquals(listOf("one", "two"), received.map { it.payload.decodeToString() })
        assertEquals(listOf(5000, 5001), received.map { it.sourcePort })
    }

    @Test
    fun aBroadcastDatagramIsDeliveredToTheBoundPort() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)

        link.pushUdp(5000, 6000, "anyone", destinationIp = Ipv4Address.BROADCAST)

        assertContentEquals("anyone".encodeToByteArray(), withTimeout(1.seconds) { udp.receive() }.payload)
    }

    @Test
    fun aDatagramForAnUnboundPortIsDroppedWithoutDisturbingBoundEndpoints() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)

        link.pushUdp(5000, 6999, "nobody listens")
        link.pushUdp(5000, 6000, "mine")

        assertContentEquals("mine".encodeToByteArray(), withTimeout(1.seconds) { udp.receive() }.payload)
        assertTrue(link.sent.isEmpty(), "this stack has no ICMP, so an unbound port is silently dropped")
    }

    @Test
    fun aDatagramWithABadChecksumIsDropped() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)
        val corrupt = udpOverIpv4(kart, 5000, ap, 6000, "corrupt".encodeToByteArray())
        corrupt[corrupt.size - 1] = (corrupt[corrupt.size - 1] + 1).toByte() // flip the payload, keep both checksums

        link.push(EtherType.IPV4, corrupt)
        link.pushUdp(5000, 6000, "good")

        assertContentEquals("good".encodeToByteArray(), withTimeout(1.seconds) { udp.receive() }.payload)
    }

    @Test
    fun aDatagramAddressedToAnotherHostIsDropped() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)

        link.pushUdp(5000, 6000, "not for us", destinationIp = Ipv4Address.parse("169.254.98.99"))
        link.pushUdp(5000, 6000, "for us")

        assertContentEquals("for us".encodeToByteArray(), withTimeout(1.seconds) { udp.receive() }.payload)
    }

    @Test
    fun aTruncatedUdpHeaderIsDroppedWithoutThrowing() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)
        link.push(EtherType.IPV4, Ipv4Packet(kart, ap, Ipv4Packet.PROTOCOL_UDP, ByteArray(5)).encode())

        link.pushUdp(5000, 6000, "after")

        assertContentEquals("after".encodeToByteArray(), withTimeout(1.seconds) { udp.receive() }.payload)
    }

    @Test
    fun theReceiveQueueKeepsTheNewestDatagramsWhenTheConsumerFallsBehind() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000, queueCapacity = 2)

        link.pushUdp(5000, 6000, "one")
        link.pushUdp(5000, 6000, "two")
        link.pushUdp(5000, 6000, "three")
        runCurrent()

        assertContentEquals("two".encodeToByteArray(), udp.receive().payload)
        assertContentEquals("three".encodeToByteArray(), udp.receive().payload)
    }

    // ---- sending ------------------------------------------------------------------------------

    @Test
    fun sendWrapsThePayloadInUdpAndIpv4AndTransmitsItOnTheLink() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)

        udp.send(kart, 5000, "ping".encodeToByteArray())

        val frame = link.sent.single()
        assertEquals(EtherType.IPV4, frame.etherType)
        val bytes = frame.payload

        // Raw IPv4 header: version 4 / IHL 5, total length 20 + 8 + 4, DF, TTL 64, UDP, our address to the kart.
        assertEquals(0x45, bytes[0].toInt() and 0xFF)
        assertEquals(32, ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))
        assertEquals(0x40, bytes[6].toInt() and 0xE0, "the don't-fragment flag is set")
        assertEquals(64, bytes[8].toInt() and 0xFF)
        assertEquals(17, bytes[9].toInt() and 0xFF)
        assertEquals("a9fe6241", bytes.copyOfRange(12, 16).toHexString())
        assertEquals("a9fe6242", bytes.copyOfRange(16, 20).toHexString())
        assertTrue(Ipv4Packet.hasValidHeaderChecksum(bytes), "the header checksum must verify")

        // Raw UDP header: 6000 -> 5000, length 12, then the payload, with a pseudo-header checksum that verifies.
        val segment = bytes.copyOfRange(20, bytes.size)
        assertEquals("1770" + "1388" + "000c", segment.copyOfRange(0, 6).toHexString())
        assertEquals("ping", segment.copyOfRange(8, 12).decodeToString())
        assertTrue(UdpDatagram.hasValidChecksum(segment, ap, kart), "the UDP checksum must verify against the pseudo-header")
    }

    @Test
    fun consecutiveSendsFromOneNodeAreAllTransmittedInOrder() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)

        udp.send(kart, 5000, "a".encodeToByteArray())
        udp.send(kart, 5000, "b".encodeToByteArray())

        val payloads = link.sent.map { it.udpDatagram().second.payload.decodeToString() }
        assertEquals(listOf("a", "b"), payloads)
    }

    @Test
    fun aSendFailureOnTheLinkIsReportedToTheUdpCaller() = runTest {
        val link = FakeFrameLink()
        val udp = startedNode(link).bindUdp(6000)
        link.sendFailure = IllegalStateException("link is down")

        assertFailsWith<IllegalStateException> { udp.send(kart, 5000, ByteArray(1)) }
    }

    // ---- binding and closing ------------------------------------------------------------------

    @Test
    fun bindingAPortTwiceOrAnInvalidPortIsRejected() = runTest {
        val node = startedNode(FakeFrameLink())
        node.bindUdp(6000)

        assertFailsWith<IllegalArgumentException> { node.bindUdp(6000) }
        assertFailsWith<IllegalArgumentException> { node.bindUdp(0) }
        assertFailsWith<IllegalArgumentException> { node.bindUdp(65536) }
        assertFailsWith<IllegalArgumentException> { node.bindUdp(6001, queueCapacity = 0) }
    }

    @Test
    fun closingAnEndpointFreesItsPortAndEndsItsReceiveSide() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)
        val first = node.bindUdp(6000)

        first.close()

        assertFailsWith<ClosedReceiveChannelException> { first.receive() }
        assertFailsWith<IllegalStateException> { first.send(kart, 5000, ByteArray(1)) }
        val second = node.bindUdp(6000)
        link.pushUdp(5000, 6000, "reborn")
        assertContentEquals("reborn".encodeToByteArray(), withTimeout(1.seconds) { second.receive() }.payload)
        first.close() // idempotent, and it must not unbind the new owner of the port
        link.pushUdp(5000, 6000, "still bound")
        assertContentEquals("still bound".encodeToByteArray(), withTimeout(1.seconds) { second.receive() }.payload)
    }
}
