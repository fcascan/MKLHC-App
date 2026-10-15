package com.mklhc.core.netstack

import com.mklhc.core.netstack.tcp.TcpConfig
import com.mklhc.core.netstack.tcp.TcpConnection
import com.mklhc.core.netstack.tcp.TcpSegment
import com.mklhc.core.netstack.tcp.TcpState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for TCP carried over the node's [FrameLink] ([NetstackNode.tcp]): IPv4 wrapping of outbound
 * segments, delivery of inbound ones to the endpoint, and a full connection between two nodes.
 *
 * One group drives the AP node with a hand-built peer that speaks raw TCP/IPv4 through
 * [FakeFrameLink], so the bytes on the link are checked independently of a second node. The other
 * joins two nodes back to back with [FakeFrameLink.pair], the kart side being a node of its own.
 * Everything runs under virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy / runCurrent drive the timers and loops under virtual time
class NetstackTcpTest {

    private val apMac = "021122334455".hexToByteArray()
    private val kartMac = "02aabbccdd01".hexToByteArray()
    private val ap = AddressPlan.AP
    private val kart = AddressPlan.KART_LEASE
    private val rcdPort = 5201
    private val kartPort = 40000
    private val kartIss = 1000L
    private val shortTimeWait = TcpConfig(timeWait = 1.seconds)

    private fun TestScope.startedNode(link: FakeFrameLink, tcpConfig: TcpConfig = shortTimeWait): NetstackNode =
        NetstackNode(link, backgroundScope, NetstackConfig(ourMac = apMac), tcpConfig, Random(100)).also { it.start() }

    /** A TCP segment from the kart's raw peer to the AP, wrapped in IPv4 as the station would transmit it. */
    private fun kartSegment(
        destinationPort: Int,
        sequence: Long,
        acknowledgment: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        destinationIp: Ipv4Address = ap,
    ): ByteArray {
        val segment = TcpSegment(
            sourcePort = kartPort,
            destinationPort = destinationPort,
            sequenceNumber = sequence,
            acknowledgmentNumber = acknowledgment,
            flags = flags,
            window = 8192,
            payload = payload,
            maxSegmentSize = if (flags and TcpSegment.SYN != 0) 1460 else null,
        )
        // The checksum always covers the AP address, so a packet whose IP destination differs is only
        // rejected by the node's own address filtering, not by an accidental checksum failure.
        return Ipv4Packet(kart, destinationIp, Ipv4Packet.PROTOCOL_TCP, segment.encode(kart, ap)).encode()
    }

    /** The TCP segment the node put on the link, after checking it came from the AP to the kart inside valid IPv4. */
    private fun LinkPayload.tcpSegment(): TcpSegment {
        val packet = ipv4Packet()
        assertEquals(Ipv4Packet.PROTOCOL_TCP, packet.protocol, "expected a TCP packet")
        assertEquals(ap, packet.source)
        assertEquals(kart, packet.destination)
        return assertNotNull(
            TcpSegment.parse(packet.payload, packet.source, packet.destination),
            "the TCP segment must parse and its pseudo-header checksum must verify",
        )
    }

    // ---- a raw peer against the AP node -------------------------------------------------------

    @Test
    fun aRawPeerCompletesTheHandshakeAndExchangesBytesThroughTheNode() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)
        val listener = node.tcp.listen(rcdPort)

        // SYN -> SYN+ACK.
        link.push(EtherType.IPV4, kartSegment(rcdPort, kartIss, 0, TcpSegment.SYN))
        runCurrent()
        val synAck = link.sent.single().tcpSegment()
        assertTrue(synAck.isSyn && synAck.isAck, "the node must answer a SYN with SYN+ACK")
        assertEquals(kartIss + 1, synAck.acknowledgmentNumber)
        assertEquals(rcdPort, synAck.sourcePort)
        assertEquals(kartPort, synAck.destinationPort)
        val apNext = synAck.sequenceNumber + 1

        // ACK completes the handshake and the listener hands the connection out.
        link.push(EtherType.IPV4, kartSegment(rcdPort, kartIss + 1, apNext, TcpSegment.ACK))
        val connection = withTimeout(1.seconds) { listener.accept() }
        assertEquals(TcpState.ESTABLISHED, connection.state.value)
        assertEquals(kart, connection.peer)
        assertEquals(kartPort, connection.peerPort)

        // Bytes from the kart reach the application and are acknowledged on the link.
        link.clearSent()
        val hello = "hello ap".encodeToByteArray()
        link.push(EtherType.IPV4, kartSegment(rcdPort, kartIss + 1, apNext, TcpSegment.ACK or TcpSegment.PSH, hello))
        assertContentEquals(hello, withTimeout(1.seconds) { connection.read(64) })
        runCurrent()
        val ack = link.sent.last().tcpSegment()
        assertTrue(ack.isAck && ack.payload.isEmpty())
        assertEquals(kartIss + 1 + hello.size, ack.acknowledgmentNumber)

        // Bytes from the application leave as an IPv4/TCP packet addressed to the kart.
        link.clearSent()
        connection.write("hello kart".encodeToByteArray())
        runCurrent()
        val data = link.sent.single().tcpSegment()
        assertEquals("hello kart", data.payload.decodeToString())
        assertEquals(apNext, data.sequenceNumber)
        assertEquals(kartIss + 1 + hello.size, data.acknowledgmentNumber)
    }

    @Test
    fun aSynToAPortWithNoListenerIsAnsweredWithAResetInsideIpv4() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.push(EtherType.IPV4, kartSegment(destinationPort = 5999, sequence = kartIss, acknowledgment = 0, flags = TcpSegment.SYN))
        runCurrent()

        val reset = link.sent.single().tcpSegment()
        assertTrue(reset.isRst, "a SYN to a closed port must be refused")
        assertEquals(kartIss + 1, reset.acknowledgmentNumber)
    }

    @Test
    fun aTcpSegmentSentToTheBroadcastAddressIsIgnored() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)
        node.tcp.listen(rcdPort)

        link.push(
            EtherType.IPV4,
            kartSegment(rcdPort, kartIss, 0, TcpSegment.SYN, destinationIp = Ipv4Address.BROADCAST),
        )
        runCurrent()

        assertTrue(link.sent.isEmpty(), "TCP is only ever unicast to us")
    }

    @Test
    fun aFailedLinkSendIsTreatedAsLossAndTheHandshakeRecoversByRetransmission() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)
        val listener = node.tcp.listen(rcdPort)
        link.sendFailure = IllegalStateException("link is down")

        link.push(EtherType.IPV4, kartSegment(rcdPort, kartIss, 0, TcpSegment.SYN))
        runCurrent()
        assertTrue(link.sent.isEmpty(), "the SYN+ACK could not be transmitted")

        link.sendFailure = null
        advanceTimeBy(250.milliseconds) // past the 200 ms initial retransmission timeout
        runCurrent()

        val synAck = link.sent.first().tcpSegment()
        assertTrue(synAck.isSyn && synAck.isAck, "the connection must survive the failed send and retransmit the SYN+ACK")
        link.push(EtherType.IPV4, kartSegment(rcdPort, kartIss + 1, synAck.sequenceNumber + 1, TcpSegment.ACK))
        assertEquals(TcpState.ESTABLISHED, withTimeout(1.seconds) { listener.accept() }.state.value)
    }

    @Test
    fun stoppingTheNodeWithALiveConnectionLeaksNoLoopOrTimerOntoTheScope() = runTest {
        val link = FakeFrameLink()
        // The node runs directly on the test scope, not on backgroundScope: runTest only returns once
        // every coroutine the node launched has finished, so a leaked receive loop would hang this
        // test, and a leaked connection actor or timer would keep transmitting, which is asserted below.
        val node = NetstackNode(link, this, NetstackConfig(ourMac = apMac), shortTimeWait, Random(100))
        node.start()
        val listener = node.tcp.listen(rcdPort)

        link.push(EtherType.IPV4, kartSegment(rcdPort, kartIss, 0, TcpSegment.SYN))
        runCurrent()
        val apNext = link.sent.single().tcpSegment().sequenceNumber + 1
        link.push(EtherType.IPV4, kartSegment(rcdPort, kartIss + 1, apNext, TcpSegment.ACK))
        val connection = withTimeout(1.seconds) { listener.accept() }
        assertEquals(TcpState.ESTABLISHED, connection.state.value)

        // Data the raw peer never acknowledges arms the retransmission timer; the idle keepalive
        // timer has been running since the connection was established.
        link.clearSent()
        connection.write("never acknowledged".encodeToByteArray())
        runCurrent()
        assertEquals(1, link.sent.size, "the data segment went out once")
        advanceTimeBy(250.milliseconds) // past the 200 ms initial retransmission timeout
        runCurrent()
        assertTrue(link.sent.size > 1, "the live connection retransmits, so its timers are running")
        val sentWhileRunning = link.sent.size

        node.stop()
        advanceTimeBy(5.minutes) // far beyond every retransmission and keepalive deadline
        runCurrent()

        assertEquals(sentWhileRunning, link.sent.size, "a stopped node's connection timers must not fire")
    }

    // ---- two nodes back to back ---------------------------------------------------------------

    private suspend fun TcpConnection.readFully(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val chunk = read(count - offset)
            if (chunk.isEmpty()) break
            chunk.copyInto(buffer, offset)
            offset += chunk.size
        }
        return buffer.copyOf(offset)
    }

    @Test
    fun theKartNodeConnectsToTheApNodeAndBytesFlowBothWaysThroughTheFrameLinks() = runTest {
        val (apLink, kartLink) = FakeFrameLink.pair()
        val apNode = NetstackNode(apLink, backgroundScope, NetstackConfig(ourMac = apMac), shortTimeWait, Random(100))
        val kartNode = NetstackNode(
            kartLink,
            backgroundScope,
            NetstackConfig(ourIp = kart, ourMac = kartMac),
            shortTimeWait,
            Random(200),
        )
        apNode.start()
        kartNode.start()
        val listener = apNode.tcp.listen(rcdPort)

        val client = kartNode.tcp.open(kartPort, ap, rcdPort)
        val server = coroutineScope {
            val accepted = async { listener.accept() }
            withTimeout(5.seconds) { client.connect() }
            accepted.await()
        }

        assertEquals(TcpState.ESTABLISHED, client.state.value)
        assertEquals(TcpState.ESTABLISHED, server.state.value)
        assertEquals(kart, server.peer)
        assertEquals(kartPort, server.peerPort)
        assertEquals(rcdPort, client.peerPort)

        val toAp = Random(7).nextBytes(3000) // more than one MSS, so several segments cross the link
        val apReceives = async { server.readFully(toAp.size) }
        client.write(toAp)
        assertContentEquals(toAp, withTimeout(5.seconds) { apReceives.await() })

        val toKart = Random(8).nextBytes(100)
        val kartReceives = async { client.readFully(toKart.size) }
        server.write(toKart)
        assertContentEquals(toKart, withTimeout(5.seconds) { kartReceives.await() })

        // Everything either node transmitted was IPv4 carrying TCP.
        val frames = apLink.sent + kartLink.sent
        assertTrue(frames.size >= 8, "handshake, data and acknowledgments crossed the links: ${frames.size}")
        for (frame in frames) {
            assertEquals(Ipv4Packet.PROTOCOL_TCP, frame.ipv4Packet().protocol)
        }

        // A graceful close crosses the same path: the kart closes, the AP sees the end of the stream and closes too.
        client.close()
        assertContentEquals(ByteArray(0), withTimeout(5.seconds) { server.read(16) }, "the FIN ends the AP's read stream")
        server.close()
        withTimeout(10.seconds) { server.state.first { it == TcpState.CLOSED } }
        withTimeout(10.seconds) { client.state.first { it == TcpState.CLOSED } }
    }
}
