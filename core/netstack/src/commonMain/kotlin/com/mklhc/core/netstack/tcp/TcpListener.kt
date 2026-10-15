package com.mklhc.core.netstack.tcp

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
 * @property localPort the bound local TCP port.
 */
class TcpListener internal constructor(
    val localPort: Int,
    private val onClose: () -> Unit,
) {
    private val accepted = Channel<TcpConnection>(Channel.UNLIMITED)

    /** Suspends until a connection to [localPort] is established and returns it. */
    suspend fun accept(): TcpConnection = accepted.receive()

    /** Delivers an established connection to a waiting (or future) [accept]; called by the endpoint. */
    internal fun onAccepted(connection: TcpConnection) {
        accepted.trySend(connection)
    }

    /** Stops listening; the endpoint forgets this port and [accept] no longer yields new connections. */
    fun close() {
        onClose()
        accepted.close()
    }
}
