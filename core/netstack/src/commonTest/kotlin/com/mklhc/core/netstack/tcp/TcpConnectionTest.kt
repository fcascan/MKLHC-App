package com.mklhc.core.netstack.tcp

import com.mklhc.core.netstack.AddressPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end tests for the TCP connection layer over a simulated lossy link ([FakeNetwork]), driven
 * by virtual time so they run instantly and deterministically.
 *
 * The client is at [AddressPlan.AP] and actively opens; the server is at [AddressPlan.KART_LEASE] and
 * accepts. Every timer (retransmission, keepalive, TIME_WAIT) runs on the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy / runCurrent drive the virtual-time timers
class TcpConnectionTest {

    private val serverAddress = AddressPlan.KART_LEASE
    private val clientAddress = AddressPlan.AP
    private val serverPort = 5201
    private val clientPort = 40000

    private fun config(
        timeWait: Duration = 1.seconds,
        keepaliveIdle: Duration = 10.seconds,
    ) = TcpConfig(timeWait = timeWait, keepaliveIdle = keepaliveIdle)

    /** Brings up an established client/server pair over [net]. */
    private suspend fun establish(scope: CoroutineScope, net: FakeNetwork, config: TcpConfig): Pair<TcpConnection, TcpConnection> {
        val clientEndpoint = TcpEndpoint(net.linkA, scope, config, Random(100))
        val serverEndpoint = TcpEndpoint(net.linkB, scope, config, Random(200))
        val listener = serverEndpoint.listen(serverPort)
        val client = clientEndpoint.open(clientPort, net.addressB, serverPort)
        val server = coroutineScope {
            val accepted = async { listener.accept() }
            client.connect()
            accepted.await()
        }
        return client to server
    }

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

    private suspend fun TcpConnection.awaitState(target: TcpState) {
        state.first { it == target }
    }

    // ---- handshake ----------------------------------------------------------------------------

    @Test
    fun anActiveOpenAndAPassiveAcceptBothReachEstablished() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)

        val (client, server) = establish(backgroundScope, net, config())

        assertEquals(TcpState.ESTABLISHED, client.state.value)
        assertEquals(TcpState.ESTABLISHED, server.state.value)
        assertEquals(clientAddress, server.peer)
        assertEquals(clientPort, server.peerPort)
    }

    @Test
    fun connectingToAPortWithNoListenerIsRefusedWithAReset() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val clientEndpoint = TcpEndpoint(net.linkA, backgroundScope, config(), Random(100))
        // The server endpoint exists but listens on no port, so a SYN earns a RST.
        TcpEndpoint(net.linkB, backgroundScope, config(), Random(200))

        val client = clientEndpoint.open(clientPort, net.addressB, serverPort)

        assertFailsWith<TcpResetException> { client.connect() }
        assertEquals(TcpState.CLOSED, client.state.value)
    }

    // ---- data transfer ------------------------------------------------------------------------

    @Test
    fun dataFlowsInOrderInBothDirectionsOnACleanLink() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val (client, server) = establish(backgroundScope, net, config())

        val toServer = Random(7).nextBytes(8 * 1024)
        val serverGot = async { server.readFully(toServer.size) }
        client.write(toServer)
        assertContentEquals(toServer, serverGot.await())

        val toClient = Random(8).nextBytes(4 * 1024)
        val clientGot = async { client.readFully(toClient.size) }
        server.write(toClient)
        assertContentEquals(toClient, clientGot.await())

        assertEquals(0, client.totalRetransmits, "a clean link needs no retransmission")
        assertEquals(0, server.totalRetransmits, "a clean link needs no retransmission")
    }

    @Test
    fun theByteStreamSurvivesSegmentLossAndRetransmissionRecoversIt() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress, seed = 42L)
        val (client, server) = establish(backgroundScope, net, config())

        net.lossRate = 0.3 // drop a fraction of data segments and acks after the handshake

        val payload = Random(11).nextBytes(4 * 1024)
        val serverGot = async { server.readFully(payload.size) }
        client.write(payload)

        assertContentEquals(payload, serverGot.await(), "the exact byte stream is recovered despite loss")
        assertTrue(client.totalRetransmits > 0, "loss must have forced at least one retransmission")
        assertTrue(net.dropped > 0, "the lossy link must actually have dropped segments")
    }

    @Test
    fun outOfOrderSegmentsAreDroppedAndRecoveredByRetransmission() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress, seed = 5L)
        val (client, server) = establish(backgroundScope, net, config())

        net.reorderDelay = 5.milliseconds // hold every other segment back, forcing out-of-order arrival

        val payload = Random(13).nextBytes(4 * 1024)
        val serverGot = async { server.readFully(payload.size) }
        client.write(payload)

        assertContentEquals(payload, serverGot.await(), "stream integrity holds under reordering")
        assertTrue(client.totalRetransmits > 0, "dropped out-of-order segments must be retransmitted")
    }

    // ---- graceful close -----------------------------------------------------------------------

    @Test
    fun aGracefulCloseCompletesTheFinHandshakeAndBothEndEndUpClosed() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val (client, server) = establish(backgroundScope, net, config(timeWait = 500.milliseconds))

        val hello = "hello".encodeToByteArray()
        client.write(hello)
        assertContentEquals(hello, server.readFully(hello.size))

        client.close()
        assertTrue(server.read(16).isEmpty(), "the server sees end-of-stream after the client's FIN")
        server.close()

        client.awaitState(TcpState.CLOSED) // FIN_WAIT_1 -> FIN_WAIT_2 -> TIME_WAIT -> CLOSED
        server.awaitState(TcpState.CLOSED) // CLOSE_WAIT -> LAST_ACK -> CLOSED
    }

    // ---- abort / RST --------------------------------------------------------------------------

    @Test
    fun anAbortSendsAResetThatTearsDownBothEndsAndSurfacesAnError() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val (client, server) = establish(backgroundScope, net, config())

        client.abort()

        assertEquals(TcpState.CLOSED, client.state.value)
        assertFailsWith<TcpResetException> { server.read(16) } // the RST surfaces an error to the peer
        server.awaitState(TcpState.CLOSED)
    }

    // ---- retransmission exhaustion ------------------------------------------------------------

    @Test
    fun theSenderGivesUpAndResetsAfterTheMaximumRetransmits() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val config = config(keepaliveIdle = 60.seconds) // keep keepalive out of the way so RTO drives the reset
        val (client, _) = establish(backgroundScope, net, config)

        net.lossRate = 1.0 // nothing gets through after the handshake

        client.write("ping".encodeToByteArray())
        assertFailsWith<TcpResetException> { client.read(16) } // read unblocks when the connection resets

        assertEquals(TcpState.CLOSED, client.state.value)
        assertEquals(config.maxRetransmits, client.totalRetransmits, "exactly maxRetransmits resends before the reset")
    }

    // ---- keepalive ----------------------------------------------------------------------------

    @Test
    fun keepaliveProbesAnIdleConnectionAndResetsItWhenUnanswered() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val config = config()
        val (client, _) = establish(backgroundScope, net, config)

        net.lossRate = 1.0 // the peer can no longer answer, so probes go unanswered

        client.awaitState(TcpState.CLOSED)
        assertEquals(config.keepaliveCount, client.keepaliveProbesSent, "all keepalive probes are sent before the reset")
    }

    @Test
    fun answeredKeepaliveProbesKeepAnIdleConnectionEstablished() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val config = config(keepaliveIdle = 1.seconds) // probe every second; four unanswered would reset by ~5 s
        val (client, server) = establish(backgroundScope, net, config)

        // The link is healthy, so each probe is answered. Advance well past the unanswered-exhaustion
        // deadline: a connection whose probes went unanswered would already have reset.
        advanceTimeBy(10.seconds)
        runCurrent()

        assertEquals(TcpState.ESTABLISHED, client.state.value, "answered probes keep the connection up")
        assertEquals(TcpState.ESTABLISHED, server.state.value)
        assertEquals(0, client.totalRetransmits, "a healthy link needs no retransmission")
        assertTrue(client.keepaliveProbesSent < config.keepaliveCount, "an answered probe resets the unanswered count")
    }

    // ---- retransmission timing ----------------------------------------------------------------

    @Test
    fun theRetransmissionTimeoutStartsNear200MsAndDoublesUntilExhaustion() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val config = config(keepaliveIdle = 600.seconds) // keep keepalive far away so only the RTO fires
        val (client, _) = establish(backgroundScope, net, config)

        net.lossRate = 1.0 // nothing gets through, so every attempt times out
        client.write("ping".encodeToByteArray())
        assertEquals(0, client.totalRetransmits, "no retransmit before the first RTO")

        // Initial RTO is ~200 ms: nothing at 199 ms, the first retransmit just after 200 ms.
        advanceTimeBy(199.milliseconds); runCurrent()
        assertEquals(0, client.totalRetransmits, "still waiting at 199 ms")
        advanceTimeBy(2.milliseconds); runCurrent()
        assertEquals(1, client.totalRetransmits, "first retransmit at ~200 ms")

        // The RTO then doubles: 400, 800, 1600, 3200 ms.
        advanceTimeBy(400.milliseconds); runCurrent()
        assertEquals(2, client.totalRetransmits, "RTO doubled to ~400 ms")
        advanceTimeBy(800.milliseconds); runCurrent()
        assertEquals(3, client.totalRetransmits, "RTO doubled to ~800 ms")
        advanceTimeBy(1600.milliseconds); runCurrent()
        assertEquals(4, client.totalRetransmits, "RTO doubled to ~1600 ms")
        advanceTimeBy(3200.milliseconds); runCurrent()
        assertEquals(5, client.totalRetransmits, "five retransmits, matching maxRetransmits")

        // The sixth timeout (RTO ~6400 ms, cumulative ~12600 ms since the write) exhausts and resets.
        assertEquals(TcpState.ESTABLISHED, client.state.value, "still up until the final RTO fires")
        advanceTimeBy(6400.milliseconds); runCurrent()
        assertEquals(TcpState.CLOSED, client.state.value, "reset after the final RTO")
    }

    // ---- post-close exception contract (Fix 2) ------------------------------------------------

    @Test
    fun writeAndConnectAfterAResetThrowTcpResetException() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val (client, _) = establish(backgroundScope, net, config())

        client.abort()
        assertEquals(TcpState.CLOSED, client.state.value)

        assertFailsWith<TcpResetException> { client.write("x".encodeToByteArray()) }
        assertFailsWith<TcpResetException> { client.connect() }
    }

    @Test
    fun writeAfterAGracefulCloseThrowsANonResetTcpException() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val (client, server) = establish(backgroundScope, net, config(timeWait = 100.milliseconds))

        client.close()
        assertTrue(server.read(16).isEmpty())
        server.close()
        client.awaitState(TcpState.CLOSED) // reaches terminal CLOSED through TIME_WAIT

        val error = assertFailsWith<TcpException> { client.write("x".encodeToByteArray()) }
        assertFalse(error is TcpResetException, "a graceful close is not a reset")
    }

    @Test
    fun closeAndAbortAreIdempotentNoOpsOnceClosed() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val (client, _) = establish(backgroundScope, net, config())

        client.abort()
        assertEquals(TcpState.CLOSED, client.state.value)

        // Calling close()/abort() again must return normally, not raise a channel-closed error.
        client.close()
        client.abort()
        client.close()
        assertEquals(TcpState.CLOSED, client.state.value)
    }

    // ---- TIME_WAIT reopen (Fix 3) -------------------------------------------------------------

    @Test
    fun aFreshSynReopensAConnectionThatIsInTimeWait() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val cfg = config(timeWait = 30.seconds, keepaliveIdle = 600.seconds)
        val serverEndpoint = TcpEndpoint(net.linkB, backgroundScope, cfg, Random(200))
        val listener = serverEndpoint.listen(serverPort)
        val clientEndpoint = TcpEndpoint(net.linkA, backgroundScope, cfg, Random(100))

        // First incarnation; the server closes first so its side lands in TIME_WAIT.
        val client1 = clientEndpoint.open(clientPort, net.addressB, serverPort)
        val server1 = coroutineScope {
            val accepted = async { listener.accept() }
            client1.connect()
            accepted.await()
        }
        server1.close()
        assertTrue(client1.read(16).isEmpty())
        client1.close()
        server1.awaitState(TcpState.TIME_WAIT)
        client1.awaitState(TcpState.CLOSED)

        // Second incarnation on the same port pair: the SYN must reopen, not be swallowed by TIME_WAIT.
        val client2 = clientEndpoint.open(clientPort, net.addressB, serverPort)
        val server2 = withTimeout(10.seconds) {
            coroutineScope {
                val accepted = async { listener.accept() }
                client2.connect()
                accepted.await()
            }
        }

        assertEquals(TcpState.ESTABLISHED, client2.state.value)
        assertEquals(TcpState.ESTABLISHED, server2.state.value)
        assertNotSame(server1, server2, "the reopening SYN is handled by a fresh TCB")
    }

    // ---- listener ------------------------------------------------------------------------------

    @Test
    fun closingAListenerUnbindsThePortAndStopsAccepting() = runTest {
        val net = FakeNetwork(backgroundScope, clientAddress, serverAddress)
        val serverEndpoint = TcpEndpoint(net.linkB, backgroundScope, config(), Random(200))
        val listener = serverEndpoint.listen(serverPort)

        listener.close()

        // The port is free again, so re-listening succeeds (close() unbound it).
        val relisten = serverEndpoint.listen(serverPort)
        assertEquals(serverPort, relisten.localPort)
        // accept() on the closed listener fails rather than hanging.
        assertFailsWith<ClosedReceiveChannelException> { listener.accept() }
    }
}
