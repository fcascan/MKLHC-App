package com.mklhc.core.kart_protocol.rcd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * A scripted [RcdTransport] for client tests: records every sent message and, for each one, queues
 * the response [respond] returns (or nothing, so the next [receive] blocks forever -- used to drive
 * the timeout path).
 */
internal class FakeClientTransport(
    private val respond: (RcdMsg) -> RcdMsg?,
) : RcdTransport {
    val sent = mutableListOf<RcdMsg>()
    var closed = false
        private set

    private val responses = Channel<RcdMsg>(Channel.UNLIMITED)

    override suspend fun send(msg: RcdMsg) {
        sent += msg
        respond(msg)?.let { responses.send(it) }
    }

    override suspend fun receive(): RcdMsg =
        try {
            responses.receive()
        } catch (closed: ClosedReceiveChannelException) {
            throw RcdClosedException()
        }

    override suspend fun close() {
        closed = true
        responses.close()
    }
}

/**
 * A two-ended in-memory [RcdTransport] duplex for client/server integration tests. Messages sent on
 * one end are received on the other; closing an end makes the peer's [receive] see end of stream.
 */
internal class InMemoryDuplex {
    private val clientToServer = Channel<RcdMsg>(Channel.UNLIMITED)
    private val serverToClient = Channel<RcdMsg>(Channel.UNLIMITED)

    val client: RcdTransport = Endpoint(outbound = clientToServer, inbound = serverToClient)
    val server: RcdTransport = Endpoint(outbound = serverToClient, inbound = clientToServer)

    private class Endpoint(
        private val outbound: Channel<RcdMsg>,
        private val inbound: Channel<RcdMsg>,
    ) : RcdTransport {
        override suspend fun send(msg: RcdMsg) {
            outbound.send(msg)
        }

        override suspend fun receive(): RcdMsg =
            try {
                inbound.receive()
            } catch (closed: ClosedReceiveChannelException) {
                throw RcdClosedException()
            }

        override suspend fun close() {
            outbound.close()
        }
    }
}

/**
 * An [RcdTransport] whose [receive] always throws what [toThrow] returns, used to drive the serve/run
 * loops' error handling. Records whether [close] was called so a test can assert the loop tore the
 * transport down.
 */
internal class ThrowingReceiveTransport(private val toThrow: () -> Throwable) : RcdTransport {
    var closed = false
        private set
    val sent = mutableListOf<RcdMsg>()

    override suspend fun send(msg: RcdMsg) {
        sent += msg
    }

    override suspend fun receive(): RcdMsg = throw toThrow()

    override suspend fun close() {
        closed = true
    }
}

/**
 * A fake [RcdByteStream] fed a fixed script of chunks for read, recording everything written. Each
 * [read] returns the next scripted chunk (truncated to `max`); once the script is exhausted it
 * returns an empty array (end of stream).
 */
internal class FakeByteStream(chunks: List<ByteArray>) : RcdByteStream {
    private val queue = ArrayDeque(chunks)
    private var pending = ByteArray(0)
    val written = mutableListOf<ByteArray>()

    override suspend fun read(max: Int): ByteArray {
        if (pending.isEmpty()) {
            if (queue.isEmpty()) return ByteArray(0)
            pending = queue.removeFirst()
        }
        val take = minOf(max, pending.size)
        val out = pending.copyOfRange(0, take)
        pending = pending.copyOfRange(take, pending.size)
        return out
    }

    override suspend fun write(bytes: ByteArray) {
        written += bytes
    }

    override suspend fun close() {}
}
