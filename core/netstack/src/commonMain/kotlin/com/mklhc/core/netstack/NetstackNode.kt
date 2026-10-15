package com.mklhc.core.netstack

import co.touchlab.kermit.Logger
import com.mklhc.core.netstack.tcp.TcpConfig
import com.mklhc.core.netstack.tcp.TcpEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/*
 * The netstack composition layer: one node that ties the FrameLink boundary to ARP, IPv4, UDP, DHCP
 * and TCP.
 *
 * Original code. It only wires modules that already exist in this package (Arp, Ipv4Receiver,
 * UdpDatagram, DhcpServer, TcpEndpoint) to the FrameLink interface; there is no third-party port.
 */

/**
 * The access point's network stack for the single kart link.
 *
 * Business logic: the kart joins the LP2P network, asks for an address by DHCP, resolves the AP's
 * address by ARP and then opens TCP connections to the app (the RCD channels) and exchanges UDP
 * datagrams with it. This class owns the [FrameLink] and the whole host side of that conversation:
 *
 * - A receive loop collects [FrameLink.inbound] and dispatches on the EtherType: ARP goes to the
 *   responder ([Arp.handle], which answers who-has for [NetstackConfig.ourIp] with
 *   [NetstackConfig.ourMac]); IPv4 is validated by [Ipv4Receiver] (malformed packets and fragments
 *   are dropped) and then filtered to packets addressed to us or to the limited broadcast address;
 *   anything else is dropped with a debug log.
 * - By IP protocol: UDP to port 67 feeds the built-in [DhcpServer] and its reply goes back as UDP
 *   67 -> 68 from [NetstackConfig.ourIp] (to 255.255.255.255 when the client set the broadcast flag
 *   or the reply is a NAK, otherwise to the leased address, RFC 2131 section 4.1); UDP to any other
 *   port goes to the [UdpEndpoint] bound by [bindUdp]; TCP unicast to us goes to [tcp]; every other
 *   protocol is dropped.
 * - Outbound, [tcp] and the UDP endpoints wrap their segments and datagrams in IPv4 from
 *   [NetstackConfig.ourIp] and transmit them with [FrameLink.send]; all transmissions are
 *   serialised, so the link never sees two concurrent sends.
 *
 * What it does not do (by design for a dead-end single-station link): no ICMP (an unbound port is not
 * answered), no IP reassembly, no routing, no ARP cache or outgoing ARP (the link has one neighbour,
 * so [FrameLink.send] is delivered to it without any link address).
 *
 * Lifecycle: construct, take [tcp] and [bindUdp] endpoints as needed, [start] the receive loop,
 * and [stop] to tear everything down. The node runs on a child of the injected [scope], so cancelling
 * that scope has the same effect as [stop]. Timers and loops all use [scope], so tests drive the node
 * with virtual time; nothing reads a wall clock.
 *
 * Threading: like the protocol classes it composes ([TcpEndpoint], [DhcpServer], [Ipv4Receiver]) the
 * node is not thread-safe. Inject a scope on a single-threaded dispatcher (for example
 * `limitedParallelism(1)`) and call [bindUdp], [start] and [stop] from it. Precondition: the injected
 * scope MUST be cancelled from that dispatcher, or call [stop] instead; cancelling it from another
 * thread is undefined.
 *
 * Hot links: [start] only launches the loop, and the loop subscribes to [FrameLink.inbound] when it
 * first runs. A link whose flow does not buffer (a replay-less SharedFlow) loses frames emitted
 * before that, so start the node before the link can deliver anything.
 *
 * @param frameLink the link to the single station; the only thing this node depends on.
 * @param scope the scope the receive loop, the TCP endpoint and every connection timer run on.
 * @param config our address and hardware address.
 * @param tcpConfig the TCP tuning shared by [tcp]'s connections.
 * @param tcpIssRandom the source of TCP initial sequence numbers (seedable for deterministic tests).
 * @param dhcpServer the built-in DHCP server; by default one that leases [AddressPlan.KART_LEASE] with
 * [NetstackConfig.ourIp] as the server identifier. Exposed so the integration layer can [DhcpServer.reset]
 * the lease when the station disassociates.
 */
class NetstackNode(
    private val frameLink: FrameLink,
    scope: CoroutineScope,
    private val config: NetstackConfig,
    tcpConfig: TcpConfig = TcpConfig(),
    tcpIssRandom: Random = Random.Default,
    val dhcpServer: DhcpServer = DhcpServer(serverIp = config.ourIp),
) {
    private val log = Logger.withTag(TAG)

    // The node's own job under the caller's: stop() cancels it without touching the caller's scope,
    // and a failing child (a connection actor) does not take the others down.
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val nodeScope = CoroutineScope(scope.coroutineContext + supervisor)

    private val ipv4Receiver = Ipv4Receiver()
    private val sendLock = Mutex()

    // Copy-on-write: the map is never mutated, only replaced wholesale, so any thread reads a
    // consistent snapshot without a lock. The node's dispatcher is its only regular writer (bindUdp and
    // UdpEndpoint.close); teardown only reads it, and refuses further writes through the state.
    @Volatile
    private var udpEndpoints: Map<Int, UdpEndpoint> = emptyMap()
    private val tcpLink = FrameLinkTcpLink(config.ourIp) { peer, segment ->
        sendIpv4(peer, Ipv4Packet.PROTOCOL_TCP, segment)
    }
    private var nextIdentification = 0

    @Volatile
    private var state = State.NEW

    /**
     * The TCP endpoint on this node's address: the app calls [TcpEndpoint.listen] on the RCD ports
     * (5201 / 5202) and accepts the kart's connections, or [TcpEndpoint.open]s one itself. Its
     * segments travel as IPv4 over the [FrameLink]. It stops with the node.
     */
    val tcp: TcpEndpoint = TcpEndpoint(tcpLink, nodeScope, tcpConfig, tcpIssRandom)

    init {
        // Covers a cancelled injected scope as well as stop(): both end in the same teardown.
        // Precondition: the injected scope MUST be cancelled from the node's single-threaded
        // dispatcher, or call stop() instead; cancelling it from another thread is undefined.
        // This handler runs on whichever thread completes the job, so teardown() is written to
        // tolerate that (idempotent, state first, reads only a snapshot), but that is damage
        // limitation, not a contract.
        supervisor.invokeOnCompletion { teardown() }
    }

    /**
     * Launches the receive loop. Idempotent while running.
     *
     * Requires a buffered [FrameLink.inbound] (see its contract): the loop subscribes only when it first runs.
     *
     * @throws IllegalStateException if the node was already stopped; build a new one.
     */
    fun start() {
        check(state != State.STOPPED) { "the node is stopped and cannot be restarted" }
        if (state == State.RUNNING) {
            log.d { "start(): already running, ignoring" }
            return
        }
        state = State.RUNNING
        nodeScope.launch { runDispatchLoop() }
        log.i { "start(): node started -> ourIp=${config.ourIp} ourMac=${config.ourMac.macText()}" }
    }

    /**
     * Stops the node: cancels the receive loop, the TCP endpoint with its connections and timers, and
     * closes every bound [UdpEndpoint]. Idempotent. Pending [UdpEndpoint.receive] calls fail; code
     * waiting on a TCP listener or connection should run in a scope that is cancelled with the node.
     */
    fun stop() {
        if (state == State.STOPPED) return
        log.i { "stop(): stopping the node -> boundUdpPorts=${udpEndpoints.keys.sorted()}" }
        supervisor.cancel()
        teardown()
    }

    /**
     * Binds UDP [localPort] and returns its endpoint. Datagrams addressed to that port are queued on
     * it (the oldest is dropped once [queueCapacity] are waiting) and it can [send][UdpEndpoint.send]
     * from it.
     *
     * @param localPort the port, 1..65535, other than 67, which the built-in DHCP server owns.
     * @param queueCapacity how many received datagrams to hold for a slow consumer.
     * @throws IllegalStateException if the node is stopped.
     * @throws IllegalArgumentException if the port is invalid, reserved or already bound, or the capacity is below 1.
     */
    fun bindUdp(localPort: Int, queueCapacity: Int = DEFAULT_UDP_QUEUE_CAPACITY): UdpEndpoint {
        check(state != State.STOPPED) { "the node is stopped, cannot bind UDP port $localPort" }
        require(localPort in MIN_PORT..MAX_PORT) { "port must be $MIN_PORT..$MAX_PORT: localPort=$localPort" }
        require(localPort != DhcpServer.SERVER_PORT) { "port $localPort is reserved for the built-in DHCP server" }
        require(queueCapacity >= 1) { "queue capacity must be at least 1: queueCapacity=$queueCapacity" }
        require(udpEndpoints[localPort] == null) { "UDP port $localPort is already bound" }
        val endpoint = UdpEndpoint(
            localPort = localPort,
            queueCapacity = queueCapacity,
            transmit = { destinationIp, sourcePort, destinationPort, payload ->
                sendUdp(destinationIp, sourcePort, destinationPort, payload)
            },
            onClosed = ::unbindUdp,
        )
        udpEndpoints = udpEndpoints + (localPort to endpoint)
        if (state == State.STOPPED) {
            // A teardown on another thread set the state after the check above and may have taken its
            // snapshot before this endpoint was published, so nobody else will close it.
            endpoint.close()
            throw IllegalStateException("the node was stopped while binding UDP port $localPort")
        }
        log.i { "bindUdp(): UDP port bound -> localPort=$localPort queueCapacity=$queueCapacity" }
        return endpoint
    }

    /** Frees [closed]'s port. A stopped node's map belongs to [teardown], which already released it. */
    private fun unbindUdp(closed: UdpEndpoint) {
        if (state == State.STOPPED) return
        if (udpEndpoints[closed.localPort] === closed) udpEndpoints = udpEndpoints - closed.localPort
    }

    // ---- receive loop -------------------------------------------------------------------------

    private suspend fun runDispatchLoop() {
        log.i { "runDispatchLoop(): receive loop running -> ourIp=${config.ourIp}" }
        try {
            frameLink.inbound.collect { frame -> dispatchGuarded(frame) }
            log.i { "runDispatchLoop(): the link's inbound flow completed, receive loop ends" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.e(failure) { "runDispatchLoop(): the link's inbound flow failed, receive loop ends -> message=${failure.message}" }
        }
    }

    /**
     * Handles one frame so that no failure while doing so can end the receive loop. The failure may
     * come after the inbound frame was handled (for example a reply that could not be sent), so the
     * log does not claim the frame was dropped.
     */
    private suspend fun dispatchGuarded(frame: LinkPayload) {
        try {
            dispatch(frame)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.e(failure) {
                "dispatchGuarded(): dispatch or handler error while processing a frame, receive loop continues -> " +
                    "etherType=0x${frame.etherType.toString(HEX_RADIX)} message=${failure.message}"
            }
        }
    }

    private suspend fun dispatch(frame: LinkPayload) {
        val bytes = frame.payload
        when (frame.etherType) {
            EtherType.ARP -> dispatchArp(bytes)
            EtherType.IPV4 -> dispatchIpv4(bytes)
            else -> log.d {
                "dispatch(): dropping a frame with an unsupported EtherType -> " +
                    "etherType=0x${frame.etherType.toString(HEX_RADIX)} size=${bytes.size}"
            }
        }
    }

    private suspend fun dispatchArp(bytes: ByteArray) {
        val request = ArpPacket.decode(bytes)
        if (request == null) {
            log.d { "dispatchArp(): dropping an ARP packet that is not Ethernet/IPv4 or is truncated -> size=${bytes.size}" }
            return
        }
        val reply = Arp.handle(request, config.ourMac, config.ourIp)
        if (reply == null) {
            log.d { "dispatchArp(): ignoring ARP that does not ask for our address -> operation=${request.operation} targetIp=${request.targetIp}" }
            return
        }
        log.d { "dispatchArp(): answering who-has -> requester=${request.senderIp} ourIp=${config.ourIp}" }
        sendFrame(EtherType.ARP, reply.encode())
    }

    private suspend fun dispatchIpv4(bytes: ByteArray) {
        val packet = ipv4Receiver.receive(bytes) ?: return // malformed or fragment: already counted and logged
        val toUs = packet.destination == config.ourIp
        val toBroadcast = packet.destination == Ipv4Address.BROADCAST
        if (!toUs && !toBroadcast) {
            log.d { "dispatchIpv4(): dropping a packet addressed to another host -> destination=${packet.destination} protocol=${packet.protocol}" }
            return
        }
        when (packet.protocol) {
            Ipv4Packet.PROTOCOL_UDP -> dispatchUdp(packet)
            Ipv4Packet.PROTOCOL_TCP -> {
                if (toUs) {
                    tcpLink.deliver(packet.source, packet.payload)
                } else {
                    log.d { "dispatchIpv4(): dropping TCP sent to the broadcast address -> source=${packet.source}" }
                }
            }
            else -> log.d { "dispatchIpv4(): dropping a packet of an unhandled protocol -> protocol=${packet.protocol} source=${packet.source}" }
        }
    }

    private suspend fun dispatchUdp(packet: Ipv4Packet) {
        if (state == State.STOPPED) {
            log.d { "dispatchUdp(): dropping a datagram that arrived while the node is stopping -> source=${packet.source}" }
            return
        }
        val datagram = UdpDatagram.parse(packet.payload, packet.source, packet.destination)
        if (datagram == null) {
            log.w { "dispatchUdp(): dropping a malformed UDP datagram -> source=${packet.source} size=${packet.payload.size}" }
            return
        }
        if (datagram.destinationPort == DhcpServer.SERVER_PORT) {
            dispatchDhcp(datagram)
            return
        }
        val endpoint = udpEndpoints[datagram.destinationPort]
        if (endpoint == null) {
            log.d { "dispatchUdp(): dropping a datagram for an unbound port -> destinationPort=${datagram.destinationPort} source=${packet.source}" }
            return
        }
        endpoint.deliver(UdpInbound(packet.source, datagram.sourcePort, datagram.payload))
    }

    private suspend fun dispatchDhcp(datagram: UdpDatagram) {
        val request = DhcpMessage.parse(datagram.payload)
        if (request == null) {
            log.d { "dispatchDhcp(): ignoring an unparseable DHCP payload -> size=${datagram.payload.size}" }
            return
        }
        val reply = dhcpServer.handle(request) ?: return
        // RFC 2131 section 4.1: a NAK, or a reply to a client that set the broadcast flag, is broadcast;
        // otherwise it is unicast to the leased address (the link has one station, so no link address is needed).
        val toBroadcast = reply.isBroadcast || reply.messageType == DhcpMessageType.NAK
        val destination = if (toBroadcast) Ipv4Address.BROADCAST else reply.yourIp
        log.i { "dispatchDhcp(): answering -> type=${reply.messageType} destination=$destination xid=${reply.xid.toUInt().toString(HEX_RADIX)}" }
        sendUdp(destination, DhcpServer.SERVER_PORT, DhcpServer.CLIENT_PORT, reply.encode())
    }

    // ---- transmit path ------------------------------------------------------------------------

    private suspend fun sendUdp(destinationIp: Ipv4Address, sourcePort: Int, destinationPort: Int, payload: ByteArray) {
        val segment = UdpDatagram(sourcePort, destinationPort, payload).encode(config.ourIp, destinationIp)
        sendIpv4(destinationIp, Ipv4Packet.PROTOCOL_UDP, segment)
    }

    /** Wraps [payload] in an IPv4 packet from our address to [destination] and transmits it. */
    private suspend fun sendIpv4(destination: Ipv4Address, protocol: Int, payload: ByteArray) {
        val packet = Ipv4Packet(
            source = config.ourIp,
            destination = destination,
            protocol = protocol,
            payload = payload,
            identification = nextIdentification,
        )
        nextIdentification = (nextIdentification + 1) and MAX_IDENTIFICATION
        sendFrame(EtherType.IPV4, packet.encode())
    }

    /** Transmits one frame, one at a time: the receive loop and every connection actor share the link. */
    private suspend fun sendFrame(etherType: Int, payload: ByteArray) {
        sendLock.withLock { frameLink.send(etherType, payload) }
    }

    // ---- teardown -----------------------------------------------------------------------------

    /**
     * Idempotent: ends the TCP receive loop and closes every bound UDP endpoint.
     *
     * Normally runs on the node's dispatcher (from [stop]), but the completion handler of the node's
     * job may run it on another thread. So it publishes the stopped state first (making [dispatchUdp],
     * [bindUdp] and [unbindUdp] back off), then only reads the copy-on-write endpoint snapshot, and
     * closes each endpoint, which is thread-safe and idempotent, instead of mutating the map in place.
     */
    private fun teardown() {
        state = State.STOPPED
        tcpLink.close()
        val endpoints = udpEndpoints.values
        udpEndpoints = emptyMap()
        for (endpoint in endpoints) endpoint.close()
    }

    private enum class State { NEW, RUNNING, STOPPED }

    companion object {
        /** How many received datagrams a [UdpEndpoint] holds for a slow consumer by default. */
        const val DEFAULT_UDP_QUEUE_CAPACITY = 64

        private const val TAG = "NetstackNode"
        private const val MIN_PORT = 1
        private const val MAX_PORT = 0xFFFF
        private const val MAX_IDENTIFICATION = 0xFFFF
        private const val HEX_RADIX = 16
    }
}
