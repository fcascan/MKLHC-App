package com.mklhc.core.netstack.tcp

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel

/*
 * Passive-open accept queue for a bound local port (RFC 9293 LISTEN).
 *
 * Original code, implemented from RFC 9293.
 */

/**
 * A bound local port that accepts incoming connections.
 *
 * Business logic: a [TcpEndpoint] creates one per [listen][TcpEndpoint.listen] call and feeds it each
 * connection that completes the three-way handshake. [accept] hands those out in arrival order. The
 * link admits one peer at a time, so in practice one connection is live per port; the queue still
 * decouples the handshake (driven by the endpoint's receive loop) from the application thread.
 *
 * No orphans: a connection that reaches the queue is either handed out by [accept] or reset, so none is
 * left holding its local port or retransmitting to a peer nobody serves. [accept] skips a queued connection
 * that already ended (for example, reset by a restarted peer before anyone accepted it); an [accept]
 * cancelled after it took a connection off the queue resets that connection; [close] resets every connection
 * still queued; and a handshake that completes after [close] is refused, so the endpoint resets it.
 *
 * @property localPort the bound local TCP port.
 */
class TcpListener internal constructor(
    val localPort: Int,
    private val onClose: (TcpListener) -> Unit,
) {
    private val log = Logger.withTag(TAG)

    // The channel reports an element as undelivered when an accept() that already took it off the queue is
    // cancelled before returning it. It does not on a trySend to a closed channel (onAccepted reports that to
    // the endpoint instead) nor for the elements still buffered at close (close() drains and resets those).
    private val accepted = Channel<TcpConnection>(Channel.UNLIMITED) { lost -> lost.abortSoon() }

    /**
     * Suspends until a connection to [localPort] is established and returns it. A connection that ended
     * while it waited in the queue is skipped.
     *
     * @throws kotlinx.coroutines.channels.ClosedReceiveChannelException once the listener is closed.
     */
    suspend fun accept(): TcpConnection {
        while (true) {
            val connection = accepted.receive()
            if (connection.state.value != TcpState.CLOSED) return connection
            log.d { "accept(): skipping a connection that ended before it was accepted -> localPort=$localPort peer=${connection.peer}:${connection.peerPort}" }
        }
    }

    /**
     * Delivers an established connection to a waiting (or future) [accept]; called by the endpoint.
     *
     * @return false when the listener is already closed: the connection was not queued and the caller must reset it.
     */
    internal fun onAccepted(connection: TcpConnection): Boolean = accepted.trySend(connection).isSuccess

    /**
     * Stops listening: the endpoint forgets this port (resetting a handshake still in progress there), every
     * connection queued but not yet accepted is reset, and [accept] no longer yields connections. Idempotent.
     */
    fun close() {
        if (!accepted.close()) return
        onClose(this)
        var reset = 0
        while (true) {
            val queued = accepted.tryReceive().getOrNull() ?: break
            queued.abortSoon()
            reset++
        }
        if (reset > 0) log.i { "close(): resetting connections nobody accepted -> localPort=$localPort count=$reset" }
    }

    private companion object {
        const val TAG = "TcpListener"
    }
}
