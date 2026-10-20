package com.mklhc.core.netstack

import co.touchlab.kermit.Logger

/*
 * Single-lease DHCPv4 server.
 *
 * Implemented from RFC 2131 ("Dynamic Host Configuration Protocol") and RFC 2132 ("DHCP Options").
 * Original code; no third-party source was consulted or copied.
 *
 * Address plan mirrors OpenKart (https://github.com/OpenKart-SDK) openkart.conf.example /
 * openkartd/config.py (BSD-3-Clause). OpenKart runs BusyBox udhcpd for the kart with the lease range
 * starting at network+2 and `option subnet` set to the netmask; the plan values live in
 * [AddressPlan]. Only the plan (subnet, AP address, lease) is mirrored; no third-party code is copied.
 */

/**
 * A DHCPv4 server that owns exactly one lease: [AddressPlan.KART_LEASE] for the kart.
 *
 * Business logic: the kart joins the LP2P network and asks for an address by DHCP
 * before it talks to the app. [handle] answers the client's messages the way the udhcpd instance in
 * OpenKart does:
 *
 * - DISCOVER is answered with an OFFER of the one lease (option 53 = 2).
 * - REQUEST for the lease address (option 50, or `ciaddr` when renewing) is answered with an ACK
 *   (option 53 = 5) and the lease is bound to the requester's hardware address. A REQUEST for any
 *   other address is answered with a NAK (option 53 = 6). A REQUEST that names another server in
 *   option 54 is a client that chose someone else and is ignored.
 * - RELEASE from the bound client frees the lease. DECLINE, INFORM, replies and anything malformed
 *   are ignored.
 *
 * OFFER and ACK carry exactly: 53 (message type), 54 (server identifier = the AP address), 51
 * (lease time) and 1 (subnet mask). Router (3) and DNS (6) are OMITTED on purpose: the kart link is
 * a dead end with nothing behind the AP, as in OpenKart's udhcpd configuration. If the hardware
 * spike shows that the kart insists on a router or DNS option, add it here (the codes are in
 * [DhcpOption]).
 *
 * One lease: because the access point admits one station at a time, any previously bound client has
 * necessarily left, so a new client simply takes over the lease; [boundClientMac] reports the most
 * recent ACKed client and [reset] forgets it (call it when the station disassociates).
 *
 * Wire handling is left to the caller: [handlePayload] takes and returns the BOOTP/DHCP bytes only.
 * The caller wraps a reply in UDP ([SERVER_PORT] to [CLIENT_PORT]) and IPv4 from [AddressPlan.AP]:
 * to 255.255.255.255 while the client has no address yet (the reply's `ciaddr` is 0.0.0.0), when the
 * reply's [DhcpMessage.isBroadcast] flag is set, or for a NAK; unicast to the offered address only for a
 * client that already has one (a renewal). That is BusyBox udhcpd's rule, which OpenKart runs, rather than
 * RFC 2131 section 4.1's unicast-to-yiaddr: the kart clears the broadcast flag but ignores an OFFER
 * unicast to the offered address (seen on hardware, 2026-10-20).
 *
 * Not thread-safe: drive it from a single coroutine / thread.
 *
 * @param serverIp the server identifier (option 54), normally the AP address.
 * @param leaseIp the one address to lease.
 * @param netmask the subnet mask (option 1).
 * @param leaseSeconds the lease time (option 51) in seconds.
 */
class DhcpServer(
    private val serverIp: Ipv4Address = AddressPlan.AP,
    private val leaseIp: Ipv4Address = AddressPlan.KART_LEASE,
    private val netmask: Ipv4Address = AddressPlan.NETMASK,
    private val leaseSeconds: Int = DEFAULT_LEASE_SECONDS,
) {
    private val log = Logger.withTag(TAG)

    private var boundMac: ByteArray? = null

    init {
        require(leaseSeconds > 0) { "lease time must be positive: leaseSeconds=$leaseSeconds" }
    }

    /** The hardware address of the client the lease is bound to, or null while the lease is free. */
    val boundClientMac: ByteArray? get() = boundMac?.copyOf()

    /** Forgets the bound client, freeing the lease (for example when the station disassociates). */
    fun reset() {
        boundMac = null
    }

    /**
     * Parses [requestPayload] (a BOOTP/DHCP message, no UDP/IP) and returns the OFFER / ACK / NAK
     * payload to send back, or null when nothing should be sent.
     */
    fun handlePayload(requestPayload: ByteArray): ByteArray? {
        val request = DhcpMessage.parse(requestPayload)
        if (request == null) {
            log.d { "handlePayload(): ignoring unparseable DHCP payload -> size=${requestPayload.size}" }
            return null
        }
        return handle(request)?.encode()
    }

    /** Returns the OFFER / ACK / NAK for [request], or null when nothing should be sent. */
    fun handle(request: DhcpMessage): DhcpMessage? {
        if (request.op != DhcpMessage.OP_REQUEST) {
            log.d { "handle(): ignoring a message that is not a client request -> op=${request.op}" }
            return null
        }
        if (request.hardwareType != DhcpMessage.HARDWARE_TYPE_ETHERNET || request.hardwareLength != MAC_BYTES) {
            log.d {
                "handle(): ignoring unsupported hardware address -> " +
                    "htype=${request.hardwareType} hlen=${request.hardwareLength}"
            }
            return null
        }
        return when (val type = request.messageType) {
            DhcpMessageType.DISCOVER -> offerFor(request)
            DhcpMessageType.REQUEST -> answerRequest(request)
            DhcpMessageType.RELEASE -> {
                releaseFrom(request)
                null
            }
            else -> {
                log.d { "handle(): ignoring message -> type=$type xid=${request.xid.toUInt().toString(HEX_RADIX)}" }
                null
            }
        }
    }

    private fun offerFor(request: DhcpMessage): DhcpMessage {
        log.i {
            "offerFor(): offering the lease -> xid=${request.xid.toUInt().toString(HEX_RADIX)} " +
                "client=${request.clientMac.macText()} yiaddr=$leaseIp"
        }
        return buildReply(request, DhcpMessageType.OFFER, yourIp = leaseIp, clientIp = Ipv4Address.ANY, withLease = true)
    }

    private fun answerRequest(request: DhcpMessage): DhcpMessage? {
        val chosenServer = request.serverIdentifier
        if (chosenServer != null && chosenServer != serverIp) {
            log.d { "answerRequest(): client chose another server, ignoring -> serverIdentifier=$chosenServer" }
            return null
        }
        val requested = request.requestedIp ?: request.clientIp.takeIf { it != Ipv4Address.ANY }
        if (requested != leaseIp) {
            log.w {
                "answerRequest(): refusing a request for an address we do not lease -> " +
                    "requested=$requested lease=$leaseIp client=${request.clientMac.macText()}"
            }
            return buildReply(request, DhcpMessageType.NAK, yourIp = Ipv4Address.ANY, clientIp = Ipv4Address.ANY, withLease = false)
        }
        boundMac = request.clientMac
        log.i {
            "answerRequest(): lease acknowledged -> xid=${request.xid.toUInt().toString(HEX_RADIX)} " +
                "client=${request.clientMac.macText()} yiaddr=$leaseIp leaseSeconds=$leaseSeconds"
        }
        return buildReply(request, DhcpMessageType.ACK, yourIp = leaseIp, clientIp = request.clientIp, withLease = true)
    }

    private fun releaseFrom(request: DhcpMessage) {
        val bound = boundMac
        if (bound != null && bound.contentEquals(request.clientMac)) {
            boundMac = null
            log.i { "releaseFrom(): lease released -> client=${request.clientMac.macText()}" }
        } else {
            log.d { "releaseFrom(): ignoring a release from a client that holds no lease -> client=${request.clientMac.macText()}" }
        }
    }

    /**
     * Builds the server reply for [request]: BOOTREPLY with the request's xid, flags, `giaddr` and
     * client hardware address, option 53 first, then 54, and (unless a NAK) 51 and 1.
     */
    private fun buildReply(
        request: DhcpMessage,
        type: Int,
        yourIp: Ipv4Address,
        clientIp: Ipv4Address,
        withLease: Boolean,
    ): DhcpMessage {
        val options = LinkedHashMap<Int, ByteArray>()
        options[DhcpOption.MESSAGE_TYPE] = byteArrayOf(type.toByte())
        options[DhcpOption.SERVER_IDENTIFIER] = serverIp.toByteArray()
        if (withLease) {
            options[DhcpOption.LEASE_TIME] = ByteArray(Int.SIZE_BYTES).also { it.writeI32(0, leaseSeconds) }
            options[DhcpOption.SUBNET_MASK] = netmask.toByteArray()
        }
        return DhcpMessage(
            op = DhcpMessage.OP_REPLY,
            xid = request.xid,
            clientMac = request.clientMac,
            hardwareType = request.hardwareType,
            hops = 0,
            secs = 0,
            flags = request.flags,
            clientIp = clientIp,
            yourIp = yourIp,
            serverIp = Ipv4Address.ANY,
            gatewayIp = request.gatewayIp,
            options = options,
        )
    }

    companion object {
        /** UDP port the server listens on. */
        const val SERVER_PORT = 67

        /** UDP port clients listen on; replies go here. */
        const val CLIENT_PORT = 68

        /** Default lease time, one day; a placeholder until the hardware spike confirms what the kart wants. */
        const val DEFAULT_LEASE_SECONDS = 86_400

        private const val TAG = "DhcpServer"
        private const val MAC_BYTES = 6
        private const val HEX_RADIX = 16
    }
}
