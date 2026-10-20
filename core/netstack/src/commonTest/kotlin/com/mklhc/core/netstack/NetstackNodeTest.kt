package com.mklhc.core.netstack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the link-facing half of [NetstackNode]: the receive loop's dispatch on EtherType and IP
 * protocol, the built-in ARP responder and DHCP server, robustness against junk, and the lifecycle.
 *
 * Everything runs against [FakeFrameLink] under virtual time: a test pushes the bytes the station
 * would send, lets the node's loop run with `runCurrent()`, and inspects what the node transmitted.
 * Inbound frames are assembled from the protocol codecs of this module, but the assertions on what
 * the node sends also check raw fields and checksums so a symmetric codec mistake cannot hide.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent drives the node's dispatch loop under virtual time
class NetstackNodeTest {

    private val apMac = "021122334455".hexToByteArray()
    private val kartMac = "02aabbccdd01".hexToByteArray()
    private val ap = AddressPlan.AP
    private val kart = AddressPlan.KART_LEASE
    private val config = NetstackConfig(ourMac = apMac)
    private val dhcpXid = 0x3903f326

    /** who-has 169.254.98.65 tell 169.254.98.66, as the kart would send it. */
    private fun whoHas(target: Ipv4Address = ap): ByteArray =
        ArpPacket(Arp.OPERATION_REQUEST, kartMac, kart, ByteArray(6), target).encode()

    private fun TestScope.startedNode(link: FakeFrameLink): NetstackNode =
        NetstackNode(link, backgroundScope, config).also { it.start() }

    // ---- ARP ----------------------------------------------------------------------------------

    @Test
    fun aWhoHasForOurAddressIsAnsweredWithAnArpReplyCarryingOurMac() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.push(EtherType.ARP, whoHas(ap))
        runCurrent()

        val frame = link.sent.single()
        assertEquals(EtherType.ARP, frame.etherType)
        val reply = assertNotNull(ArpPacket.decode(frame.payload))
        assertEquals(Arp.OPERATION_REPLY, reply.operation)
        assertContentEquals(apMac, reply.senderMac, "the reply must carry the AP hardware address")
        assertEquals(ap, reply.senderIp)
        assertContentEquals(kartMac, reply.targetMac)
        assertEquals(kart, reply.targetIp)
    }

    @Test
    fun aWhoHasForAnotherAddressIsNotAnswered() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.push(EtherType.ARP, whoHas(Ipv4Address.parse("169.254.98.99")))
        runCurrent()

        assertTrue(link.sent.isEmpty(), "ARP for a host that is not us must be ignored")
    }

    @Test
    fun aTruncatedArpPacketIsDroppedAndTheLoopKeepsRunning() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.push(EtherType.ARP, whoHas().copyOf(20))
        link.push(EtherType.ARP, ByteArray(0))
        link.push(EtherType.ARP, whoHas())
        runCurrent()

        assertEquals(1, link.sent.size, "only the well-formed request earns a reply")
    }

    @Test
    fun theArpResponderAnswersForAConfiguredAddressInsteadOfTheDefault() = runTest {
        val link = FakeFrameLink()
        val other = Ipv4Address.parse("169.254.98.70")
        NetstackNode(link, backgroundScope, NetstackConfig(ourIp = other, ourMac = apMac)).start()

        link.push(EtherType.ARP, whoHas(ap))
        link.push(EtherType.ARP, whoHas(other))
        runCurrent()

        val reply = assertNotNull(ArpPacket.decode(link.sent.single().payload))
        assertEquals(other, reply.senderIp)
    }

    // ---- DHCP ---------------------------------------------------------------------------------

    private fun dhcpOptions(type: Int, vararg extra: Pair<Int, ByteArray>): Map<Int, ByteArray> {
        val options = LinkedHashMap<Int, ByteArray>()
        options[DhcpOption.MESSAGE_TYPE] = byteArrayOf(type.toByte())
        for ((code, data) in extra) options[code] = data
        return options
    }

    /** A realistic client DISCOVER: client id, the address it hopes for and a parameter request list. */
    private fun discover(flags: Int = DhcpMessage.FLAG_BROADCAST): ByteArray = DhcpMessage(
        op = DhcpMessage.OP_REQUEST,
        xid = dhcpXid,
        clientMac = kartMac,
        flags = flags,
        options = dhcpOptions(
            DhcpMessageType.DISCOVER,
            DhcpOption.CLIENT_IDENTIFIER to (byteArrayOf(1) + kartMac),
            DhcpOption.REQUESTED_IP to kart.toByteArray(),
            DhcpOption.PARAMETER_REQUEST_LIST to byteArrayOf(1, 3, 6),
        ),
    ).encode()

    /** A SELECTING-state REQUEST for [requested], naming the AP as the chosen server. */
    private fun selectingRequest(requested: Ipv4Address = kart, flags: Int = DhcpMessage.FLAG_BROADCAST): ByteArray = DhcpMessage(
        op = DhcpMessage.OP_REQUEST,
        xid = dhcpXid,
        clientMac = kartMac,
        flags = flags,
        options = dhcpOptions(
            DhcpMessageType.REQUEST,
            DhcpOption.CLIENT_IDENTIFIER to (byteArrayOf(1) + kartMac),
            DhcpOption.REQUESTED_IP to requested.toByteArray(),
            DhcpOption.SERVER_IDENTIFIER to ap.toByteArray(),
        ),
    ).encode()

    /** A RENEWING-state REQUEST: the client already holds [kart] (ciaddr) and unicasts to the server. */
    private fun renewalRequest(): ByteArray = DhcpMessage(
        op = DhcpMessage.OP_REQUEST,
        xid = dhcpXid,
        clientMac = kartMac,
        clientIp = kart,
        options = dhcpOptions(DhcpMessageType.REQUEST),
    ).encode()

    /** The DHCP message the node put on the link, with the IPv4 and UDP layers that carried it. */
    private class DhcpFrame(val ip: Ipv4Packet, val udp: UdpDatagram, val dhcp: DhcpMessage)

    private fun LinkPayload.dhcpFrame(): DhcpFrame {
        val (ip, udp) = udpDatagram()
        val dhcp = assertNotNull(DhcpMessage.parse(udp.payload), "the UDP payload must be a DHCP message")
        return DhcpFrame(ip, udp, dhcp)
    }

    private fun FakeFrameLink.pushDhcpToServer(dhcp: ByteArray, sourceIp: Ipv4Address, destinationIp: Ipv4Address) {
        push(EtherType.IPV4, udpOverIpv4(sourceIp, DhcpServer.CLIENT_PORT, destinationIp, DhcpServer.SERVER_PORT, dhcp))
    }

    @Test
    fun aDiscoverFromTheStationIsAnsweredWithAnOfferOfTheKartLease() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.pushDhcpToServer(discover(), Ipv4Address.ANY, Ipv4Address.BROADCAST)
        runCurrent()

        val offer = link.sent.single().dhcpFrame()
        assertEquals(ap, offer.ip.source, "the OFFER comes from the AP address")
        assertEquals(Ipv4Address.BROADCAST, offer.ip.destination, "the client set the broadcast flag")
        assertEquals(DhcpServer.SERVER_PORT, offer.udp.sourcePort)
        assertEquals(DhcpServer.CLIENT_PORT, offer.udp.destinationPort)
        assertEquals(DhcpMessage.OP_REPLY, offer.dhcp.op)
        assertEquals(DhcpMessageType.OFFER, offer.dhcp.messageType)
        assertEquals(kart, offer.dhcp.yourIp)
        assertEquals(dhcpXid, offer.dhcp.xid)
        assertContentEquals(kartMac, offer.dhcp.clientMac)
        assertEquals(ap, offer.dhcp.serverIdentifier)
    }

    @Test
    fun aRequestAfterTheOfferIsAnsweredWithAnAckAndBindsTheLease() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)

        link.pushDhcpToServer(discover(), Ipv4Address.ANY, Ipv4Address.BROADCAST)
        link.pushDhcpToServer(selectingRequest(), Ipv4Address.ANY, Ipv4Address.BROADCAST)
        runCurrent()

        assertEquals(2, link.sent.size)
        assertEquals(DhcpMessageType.OFFER, link.sent[0].dhcpFrame().dhcp.messageType)
        val ack = link.sent[1].dhcpFrame()
        assertEquals(DhcpMessageType.ACK, ack.dhcp.messageType)
        assertEquals(kart, ack.dhcp.yourIp)
        assertEquals(ap, ack.ip.source)
        assertEquals(DhcpServer.CLIENT_PORT, ack.udp.destinationPort)
        assertNotNull(ack.dhcp.leaseSeconds, "an ACK carries the lease time")
        assertContentEquals(kartMac, node.dhcpServer.boundClientMac, "the ACK binds the lease to the requester")
    }

    @Test
    fun repliesAreBroadcastWhileTheClientHasNoAddressEvenWithoutTheBroadcastFlag() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        // The kart clears the broadcast flag but ignores an OFFER unicast to the offered address (seen on hardware),
        // so like BusyBox udhcpd the OFFER and the ACK go to 255.255.255.255 while ciaddr is 0.
        link.pushDhcpToServer(discover(flags = 0), Ipv4Address.ANY, Ipv4Address.BROADCAST)
        link.pushDhcpToServer(selectingRequest(flags = 0), Ipv4Address.ANY, Ipv4Address.BROADCAST)
        runCurrent()

        assertEquals(2, link.sent.size)
        val offer = link.sent[0].dhcpFrame()
        assertEquals(DhcpMessageType.OFFER, offer.dhcp.messageType)
        assertEquals(Ipv4Address.BROADCAST, offer.ip.destination, "no address yet: the OFFER is broadcast")
        assertEquals(ap, offer.ip.source)
        val ack = link.sent[1].dhcpFrame()
        assertEquals(DhcpMessageType.ACK, ack.dhcp.messageType)
        assertEquals(Ipv4Address.BROADCAST, ack.ip.destination, "no address yet: the ACK is broadcast")
    }

    @Test
    fun aRenewalSentToTheApIsAnsweredWithAnAckUnicastToTheKart() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.pushDhcpToServer(renewalRequest(), kart, ap)
        runCurrent()

        val ack = link.sent.single().dhcpFrame()
        assertEquals(DhcpMessageType.ACK, ack.dhcp.messageType)
        assertEquals(kart, ack.ip.destination)
    }

    @Test
    fun aRequestForAnotherAddressIsRefusedWithANakToBroadcast() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.pushDhcpToServer(
            selectingRequest(requested = Ipv4Address.parse("169.254.98.99"), flags = 0),
            Ipv4Address.ANY,
            Ipv4Address.BROADCAST,
        )
        runCurrent()

        val nak = link.sent.single().dhcpFrame()
        assertEquals(DhcpMessageType.NAK, nak.dhcp.messageType)
        assertEquals(Ipv4Address.BROADCAST, nak.ip.destination, "a NAK is always broadcast")
    }

    @Test
    fun garbageOnTheDhcpServerPortIsIgnored() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.pushDhcpToServer(ByteArray(300) { it.toByte() }, Ipv4Address.ANY, Ipv4Address.BROADCAST)
        link.pushDhcpToServer(ByteArray(0), Ipv4Address.ANY, Ipv4Address.BROADCAST)
        runCurrent()

        assertTrue(link.sent.isEmpty(), "an unparseable DHCP payload earns no reply")
    }

    @Test
    fun dhcpTrafficIsNotOfferedToApplicationUdpEndpoints() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)

        assertFailsWith<IllegalArgumentException> { node.bindUdp(DhcpServer.SERVER_PORT) }
    }

    // ---- dispatch robustness ------------------------------------------------------------------

    private fun icmpEchoRequest(): ByteArray = Ipv4Packet(
        source = kart,
        destination = ap,
        protocol = Ipv4Packet.PROTOCOL_ICMP,
        payload = "0800f7ff00010001".hexToByteArray(),
    ).encode()

    @Test
    fun malformedIpv4IsDroppedWithoutThrowingAndTheLoopSurvives() = runTest {
        val link = FakeFrameLink()
        startedNode(link)
        val badChecksum = icmpEchoRequest().also { it[10] = (it[10] + 1).toByte() }
        val notVersionFour = icmpEchoRequest().also { it[0] = 0x65 }

        link.push(EtherType.IPV4, ByteArray(7) { 0x55 })
        link.push(EtherType.IPV4, ByteArray(0))
        link.push(EtherType.IPV4, badChecksum)
        link.push(EtherType.IPV4, notVersionFour)
        link.push(EtherType.IPV4, icmpEchoRequest().copyOf(24))
        link.push(EtherType.ARP, whoHas())
        runCurrent()

        assertEquals(1, link.sent.size, "nothing is sent for the junk, and the loop still answers the later ARP")
        assertEquals(EtherType.ARP, link.sent.single().etherType)
    }

    @Test
    fun anUnknownEtherTypeIsDropped() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.push(0x86DD, ByteArray(40)) // IPv6 is not spoken on this link
        link.push(0x0000, ByteArray(10))
        link.push(EtherType.ARP, whoHas())
        runCurrent()

        assertEquals(1, link.sent.size)
        assertEquals(EtherType.ARP, link.sent.single().etherType)
    }

    @Test
    fun anIpv4PacketWithAnUnhandledProtocolIsDropped() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.push(EtherType.IPV4, icmpEchoRequest())
        link.push(EtherType.ARP, whoHas())
        runCurrent()

        assertEquals(1, link.sent.size, "ICMP is not handled, so only the ARP reply goes out")
        assertEquals(EtherType.ARP, link.sent.single().etherType)
    }

    @Test
    fun anIpv4FragmentIsDroppedBeforeItReachesATransport() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)
        val udp = node.bindUdp(6000)
        val datagram = UdpDatagram(5000, 6000, "frag".encodeToByteArray()).encode(kart, ap)
        val fragment = Ipv4Packet(kart, ap, Ipv4Packet.PROTOCOL_UDP, datagram, moreFragments = true, dontFragment = false).encode()

        link.push(EtherType.IPV4, fragment)
        link.push(EtherType.IPV4, udpOverIpv4(kart, 5000, ap, 6000, "whole".encodeToByteArray()))
        runCurrent()

        assertContentEquals("whole".encodeToByteArray(), udp.receive().payload, "the fragment must be skipped")
    }

    @Test
    fun aFailingLinkSendIsLoggedAndTheLoopKeepsRunning() = runTest {
        val link = FakeFrameLink()
        startedNode(link)

        link.sendFailure = IllegalStateException("link is down")
        link.push(EtherType.ARP, whoHas())
        runCurrent()
        assertTrue(link.sent.isEmpty())

        link.sendFailure = null
        link.push(EtherType.ARP, whoHas())
        runCurrent()
        assertEquals(1, link.sent.size, "the node must survive a send failure")
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    @Test
    fun framesPushedBeforeStartAreNotProcessedUntilTheNodeStarts() = runTest {
        val link = FakeFrameLink()
        val node = NetstackNode(link, backgroundScope, config)

        link.push(EtherType.ARP, whoHas())
        runCurrent()
        assertTrue(link.sent.isEmpty(), "a node that was never started must not dispatch")

        node.start()
        runCurrent()
        assertEquals(1, link.sent.size)
    }

    @Test
    fun startingTwiceDoesNotRunTwoDispatchLoops() = runTest {
        val link = FakeFrameLink()
        val node = startedNode(link)
        node.start()

        link.push(EtherType.ARP, whoHas())
        runCurrent()

        assertEquals(1, link.sent.size, "a second loop would answer the same frame twice or steal frames")
    }

    @Test
    fun stopCancelsTheLoopAndClosesEveryBoundUdpEndpoint() = runTest {
        val link = FakeFrameLink()
        // The node runs directly on the test scope: runTest only returns once every coroutine the node
        // launched has finished, so a leaked loop, TCP receive loop or timer would hang this test.
        val node = NetstackNode(link, this, config)
        node.start()
        val udp = node.bindUdp(6000)
        link.push(EtherType.ARP, whoHas())
        runCurrent()
        assertEquals(1, link.sent.size)

        node.stop()
        runCurrent()

        link.push(EtherType.ARP, whoHas())
        runCurrent()
        assertEquals(1, link.sent.size, "a stopped node answers nothing")
        assertFailsWith<ClosedReceiveChannelException> { udp.receive() }
        assertFailsWith<IllegalStateException> { node.bindUdp(6001) }
        assertFailsWith<IllegalStateException> { node.start() }
        node.stop() // idempotent
    }

    @Test
    fun cancellingTheInjectedScopeAlsoTearsTheNodeDown() = runTest {
        val link = FakeFrameLink()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val node = NetstackNode(link, scope, config)
        node.start()
        val udp = node.bindUdp(6000)
        runCurrent()

        scope.cancel()
        runCurrent()

        link.push(EtherType.ARP, whoHas())
        runCurrent()
        assertTrue(link.sent.isEmpty())
        assertFailsWith<ClosedReceiveChannelException> { udp.receive() }
    }

    @Test
    fun aDatagramArrivingAfterTheInjectedScopeIsCancelledIsDroppedAndTeardownIsIdempotent() = runTest {
        val link = FakeFrameLink()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val node = NetstackNode(link, scope, config)
        node.start()
        val udp = node.bindUdp(6000)
        runCurrent()

        scope.cancel()
        link.push(EtherType.IPV4, udpOverIpv4(kart, 5000, ap, 6000, "late".encodeToByteArray()))
        runCurrent()
        node.stop() // a second and third teardown must be harmless
        node.stop()

        assertFailsWith<ClosedReceiveChannelException> { udp.receive() }
        assertTrue(link.sent.isEmpty())
        assertFailsWith<IllegalStateException> { node.bindUdp(6000) }
    }
}
