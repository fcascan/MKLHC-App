package com.mklhc.app.spike

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.data.session.SessionConfig
import com.mklhc.core.kart_protocol.rcd.RcdDevice
import com.mklhc.core.kart_protocol.rcd.RcdDeviceHandler
import com.mklhc.core.kart_protocol.rcd.RcdHandshakeService
import com.mklhc.core.kart_protocol.rcd.RcdServer
import com.mklhc.core.kart_protocol.rcd.RcdServerInfo
import com.mklhc.core.kart_protocol.rcd.asRcdTransport
import com.mklhc.core.netstack.NetstackNode
import com.mklhc.core.netstack.tcp.TcpConnection
import com.mklhc.core.netstack.tcp.TcpException
import com.mklhc.core.wifi_ap.mac.MacAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * The spike's pairing probe: what the app does once the kart is on the network, up to the RCD pairing handshake, and
 * the reset of the DHCP lease when the kart leaves.
 *
 * Original code. The handshake wiring (an RcdHandshakeService with pairing on, served by an RcdServer over the TCP
 * connection's RCD transport) mirrors KartSessionRepository.serveHandshakeAwaitConnect in :core:data
 * (core/data/src/commonMain/kotlin/com/mklhc/core/data/session/KartSessionRepository.kt), minus the session around
 * it: the probe only observes how far the kart gets. It uses pieces that already exist (the network stack's TCP
 * listener and DHCP server, the RCD handshake from :core:kart-protocol, the identity derivation from :core:data).
 */

/**
 * The pairing probe: listens on the RCD pairing port and serves the RCD handshake to every connection the kart opens,
 * so the spike shows that the kart gets as far as the handshake.
 *
 * Business logic: once the kart has its lease and has resolved the app's address by ARP, it connects to the app's TCP
 * pairing port (the same one the production session uses) and runs the RCD handshake as a client. The probe plays the
 * app's side of exactly that, with a throwaway identity derived from 32 fresh random octets each run, so no stored
 * secret is involved. It logs `pairing TCP accepted` when a connection is established and `RCD pairing handshake
 * completed` when the kart finishes the handshake (the device's name only, never its ident or any key).
 *
 * It deliberately goes no further. A real pairing continues by connecting to the kart's own pairing service and
 * sending it the game network's SSID and key (the group information), which makes the kart store that pairing. The
 * spike must not change what the kart has stored, so it never connects to the kart and never sends the group
 * information. After the handshake the kart is expected to wait about 5 s for the app to do that (the design's
 * timeout), give up, reset and start over; the probe then accepts its next connection. That loop is expected.
 *
 * Each connection is served on its own and lives as long as the association it came from (see
 * [serveProbeConnection]), because the stack keeps one connection per port: a connection left over from a kart that
 * has gone would make the stack ignore the kart's next attempt.
 *
 * Runs until cancelled. Call it from the stack's single-threaded dispatcher, like the node it listens on.
 *
 * @param node the network stack to listen on.
 * @param station the access point's associated station.
 * @param primitives the crypto provider for the identity and the handshake.
 * @param log the session's logger.
 */
internal suspend fun runPairingProbe(
    node: NetstackNode,
    station: StateFlow<MacAddress?>,
    primitives: CryptoPrimitives,
    log: Logger,
) {
    try {
        val identity = deriveKartIdentity(primitives.secureRandomBytes(PROBE_SECRET_BYTES), primitives)
        val serverInfo = identity.toRcdServerInfo(primitives)
        val listener = node.tcp.listen(PAIRING_RCD_PORT)
        log.i { "runPairingProbe(): listening for the kart's pairing connection -> port=$PAIRING_RCD_PORT" }
        coroutineScope {
            try {
                while (true) {
                    val connection = listener.accept()
                    log.i {
                        "runPairingProbe(): pairing TCP accepted -> peer=${connection.peer}:${connection.peerPort}"
                    }
                    launch { serveProbeConnection(connection, serverInfo, station, primitives, log) }
                }
            } finally {
                listener.close()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        log.e(failure) { "runPairingProbe(): the pairing probe stopped -> message=${failure.message}" }
    }
}

/**
 * Serves the RCD handshake on one pairing [connection] until the kart closes it, the association it came from ends or
 * the probe is cancelled, then aborts it so the stack's port is free again.
 *
 * Business logic: the handshake runs with pairing on, which accepts a device the server does not know yet (the kart,
 * which has never paired with this run's throwaway identity). Its completion is the spike's last milestone. Nothing
 * is done with the device afterwards.
 *
 * @param connection the established connection from the kart.
 * @param serverInfo the throwaway RCD server identity of this run.
 * @param station the access point's associated station; the connection is aborted when it turns null.
 * @param primitives the crypto provider for the handshake.
 * @param log the session's logger.
 */
internal suspend fun serveProbeConnection(
    connection: TcpConnection,
    serverInfo: RcdServerInfo,
    station: StateFlow<MacAddress?>,
    primitives: CryptoPrimitives,
    log: Logger,
) = coroutineScope {
    val handler = object : RcdDeviceHandler {
        override fun deviceConnected(device: RcdDevice) {
            log.i { "serveProbeConnection(): RCD pairing handshake completed -> device=${device.name}" }
        }

        override fun deviceDisconnected(device: RcdDevice) {
            log.d { "serveProbeConnection(): RCD pairing device disconnected -> device=${device.name}" }
        }
    }
    val service = RcdHandshakeService(
        serverInfo = serverInfo,
        peerAddress = connection.peer.toString(),
        handler = handler,
        pairing = true,
        crypto = primitives,
    )
    // The read below would wait on a kart that has gone, so end it from here: aborting the connection fails the read.
    val peer = "${connection.peer}:${connection.peerPort}"
    val leaveWatch = launch {
        station.first { it == null }
        // Logged before the abort: aborting ends the read below, which cancels this watcher while it is still waiting.
        log.d { "serveProbeConnection(): the station left, aborting its pairing connection -> peer=$peer" }
        abortQuietly(connection)
    }
    try {
        RcdServer(connection.asRcdTransport(), listOf(service)).serve()
        log.i { "serveProbeConnection(): the pairing connection ended -> peer=$peer" }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        log.w { "serveProbeConnection(): the pairing connection failed -> message=${failure.message}" }
    } finally {
        leaveWatch.cancel()
        service.close()
        abortQuietly(connection)
    }
}

/**
 * Frees the DHCP server's single lease whenever the associated station goes away.
 *
 * Business logic: the access point does not touch the DHCP server, which belongs to the network stack, so the
 * integration layer does it: the station is gone when the kart deauthenticates (it releases its lease and starts over
 * every few seconds while its pairing does not finish), and also when the access point drops it because radiod
 * restarted. Either way the kart's next DHCP exchange must start from a free lease. The first emission (no station
 * yet) is not a departure and is ignored.
 *
 * Runs until cancelled. Call it from the stack's single-threaded dispatcher, like the node whose DHCP server it resets.
 *
 * @param station the access point's associated station.
 * @param node the network stack whose DHCP server is reset.
 * @param log the session's logger.
 */
internal suspend fun freeLeaseWhenStationLeaves(station: StateFlow<MacAddress?>, node: NetstackNode, log: Logger) {
    var previous: MacAddress? = null
    station.collect { current ->
        if (current == null && previous != null) {
            node.dhcpServer.reset()
            log.i { "freeLeaseWhenStationLeaves(): the station left, DHCP lease freed -> sta=$previous" }
        }
        previous = current
    }
}

/**
 * Aborts [connection] (a RST goes to the kart and the stack's port is freed), ignoring the stack's own failure type.
 *
 * Business logic: by the time the probe cleans up, the connection may already have been reset or closed (the kart reset
 * it, or the stack gave up on it); the stack reports that with its [TcpException], which is not worth more than
 * leaving the connection alone. Only that type is caught: a cancellation is NOT swallowed, it must reach the caller.
 *
 * On cancellation the abort is fire-and-forget: the request is already queued to the connection's own coroutine, and
 * only the wait for its answer is cancelled. It is deliberately not run under `NonCancellable`: once the network stack
 * has stopped, that coroutine is gone and the wait would never end.
 *
 * @param connection the connection to abort.
 */
private suspend fun abortQuietly(connection: TcpConnection) {
    try {
        connection.abort()
    } catch (failure: TcpException) {
        // Already torn down by the stack (reset or closed): there is nothing left to abort.
    }
}

private const val PROBE_SECRET_BYTES = 32

// The TCP port the kart's RCD client connects to while pairing: the one the production session listens on.
private val PAIRING_RCD_PORT = SessionConfig().pairingRcdPort
