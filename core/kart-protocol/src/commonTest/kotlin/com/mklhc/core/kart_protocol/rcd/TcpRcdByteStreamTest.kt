package com.mklhc.core.kart_protocol.rcd

import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.Ipv4Address
import com.mklhc.core.netstack.tcp.InboundSegment
import com.mklhc.core.netstack.tcp.TcpConnection
import com.mklhc.core.netstack.tcp.TcpEndpoint
import com.mklhc.core.netstack.tcp.TcpLink
import com.mklhc.core.netstack.tcp.TcpState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for [TcpRcdByteStream], the adapter that frames RCD over a real netstack [TcpConnection].
 *
 * A reset is the NORMAL teardown on the kart's RF link, so a [com.mklhc.core.netstack.tcp.TcpException]
 * (its subtype [com.mklhc.core.netstack.tcp.TcpResetException] included) from the underlying connection
 * must reach RCD as one clean [RcdClosedException], never as a raw TCP exception. These drive two real
 * [TcpConnection]s over a zero-loss in-memory link (so the TCP state machine, not a stub, produces the
 * teardown) and assert the mapping on both read and write.
 */
class TcpRcdByteStreamTest {

    private val serverAddress = AddressPlan.KART_LEASE
    private val clientAddress = AddressPlan.AP
    private val serverPort = 5300
    private val clientPort = 41000

    /** A lossless two-node in-memory [TcpLink] pair, delivering each encoded segment to the peer at once. */
    private class Loopback(addressA: Ipv4Address, addressB: Ipv4Address) {
        private val inboundA = Channel<InboundSegment>(Channel.UNLIMITED)
        private val inboundB = Channel<InboundSegment>(Channel.UNLIMITED)

        val linkA: TcpLink = Node(addressA, inboundA) { bytes -> inboundB.trySend(InboundSegment(addressA, bytes)) }
        val linkB: TcpLink = Node(addressB, inboundB) { bytes -> inboundA.trySend(InboundSegment(addressB, bytes)) }

        private class Node(
            override val localAddress: Ipv4Address,
            private val inboundChannel: Channel<InboundSegment>,
            private val onSend: (ByteArray) -> Unit,
        ) : TcpLink {
            override suspend fun send(peer: Ipv4Address, segment: ByteArray) = onSend(segment)
            override val inbound: Flow<InboundSegment> get() = inboundChannel.receiveAsFlow()
        }
    }

    /** Brings up an established client/server connection pair over a fresh loopback. */
    private suspend fun establish(scope: CoroutineScope): Pair<TcpConnection, TcpConnection> {
        val net = Loopback(clientAddress, serverAddress)
        val clientEndpoint = TcpEndpoint(net.linkA, scope, issRandom = Random(100))
        val serverEndpoint = TcpEndpoint(net.linkB, scope, issRandom = Random(200))
        val listener = serverEndpoint.listen(serverPort)
        val client = clientEndpoint.open(clientPort, serverAddress, serverPort)
        val server = coroutineScope {
            val accepted = async { listener.accept() }
            client.connect()
            accepted.await()
        }
        return client to server
    }

    @Test
    fun `receive maps a clean peer close to RcdClosedException`() = runTest {
        val (client, server) = establish(backgroundScope)
        val transport = FramedRcdTransport(TcpRcdByteStream(client))

        // The kart ends the connection gracefully (FIN); the half-read stream must look closed to RCD.
        server.close()

        assertFailsWith<RcdClosedException> { transport.receive() }
    }

    @Test
    fun `receive maps a connection reset to RcdClosedException`() = runTest {
        val (client, server) = establish(backgroundScope)
        val transport = FramedRcdTransport(TcpRcdByteStream(client))

        // A reset (RST) is the normal RF-link teardown; it must surface as RcdClosedException, not as a
        // raw TcpResetException leaking out of the adapter.
        server.abort()

        assertFailsWith<RcdClosedException> { transport.receive() }
    }

    @Test
    fun `send maps a write on a reset connection to RcdClosedException`() = runTest {
        val (client, _) = establish(backgroundScope)
        client.abort()
        client.state.first { it == TcpState.CLOSED }
        val transport = FramedRcdTransport(TcpRcdByteStream(client))

        assertFailsWith<RcdClosedException> {
            transport.send(RcdMsg(service = 1, command = 1, status = 0, isResponse = false, data = byteArrayOf(1, 2, 3)))
        }
    }
}
