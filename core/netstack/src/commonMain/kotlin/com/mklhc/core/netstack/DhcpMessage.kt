package com.mklhc.core.netstack

/*
 * BOOTP / DHCPv4 message codec.
 *
 * Implemented from RFC 2131 ("Dynamic Host Configuration Protocol", the figure 1 message layout and
 * the magic cookie) and RFC 2132 ("DHCP Options and BOOTP Vendor Extensions"); the fixed header is
 * RFC 951 (BOOTP). Original code; no third-party source was consulted or copied.
 *
 * Layout, all multi-octet fields big-endian: op (1), htype (1), hlen (1), hops (1), xid (4),
 * secs (2), flags (2), ciaddr (4), yiaddr (4), siaddr (4), giaddr (4), chaddr (16), sname (64),
 * file (128), the magic cookie 63 82 53 63 (4), then options: a code octet, a length octet and the
 * data, with 0 as a one-octet pad and 255 as the end marker. A DHCP message rides in UDP, server
 * port 67 and client port 68, inside IPv4.
 */

/** DHCP option codes (RFC 2132) used by this stack. */
object DhcpOption {
    /** One-octet padding; carries no length or data. */
    const val PAD = 0

    /** Subnet mask (4 octets). */
    const val SUBNET_MASK = 1

    /** Router list. Deliberately never sent by [DhcpServer]; see its class comment. */
    const val ROUTER = 3

    /** Domain name server list. Deliberately never sent by [DhcpServer]; see its class comment. */
    const val DNS_SERVERS = 6

    /** Requested IP address (4 octets), sent by a client in DISCOVER / REQUEST. */
    const val REQUESTED_IP = 50

    /** IP address lease time in seconds (4 octets, unsigned). */
    const val LEASE_TIME = 51

    /** DHCP message type (1 octet, see [DhcpMessageType]). */
    const val MESSAGE_TYPE = 53

    /** Server identifier (4 octets): the address of the server that sent or was chosen. */
    const val SERVER_IDENTIFIER = 54

    /** Parameter request list: the option codes the client would like. */
    const val PARAMETER_REQUEST_LIST = 55

    /** Client identifier. */
    const val CLIENT_IDENTIFIER = 61

    /** End of the option list; everything after it is padding. */
    const val END = 255
}

/** DHCP message types, the value of option 53 (RFC 2132 section 9.6). */
object DhcpMessageType {
    const val DISCOVER = 1
    const val OFFER = 2
    const val REQUEST = 3
    const val DECLINE = 4
    const val ACK = 5
    const val NAK = 6
    const val RELEASE = 7
    const val INFORM = 8
}

/**
 * One BOOTP/DHCP message in either direction.
 *
 * Business logic: the kart (a DHCP client) broadcasts DISCOVER and REQUEST messages; [DhcpServer]
 * parses them with [parse] and answers with an OFFER/ACK/NAK built from the same type and written
 * with [encode]. The first 236 octets are fixed; [options] holds the variable part in wire order
 * (the first occurrence of a repeated code wins when parsing).
 *
 * @param op 1 for a client request (BOOTREQUEST), 2 for a server reply (BOOTREPLY).
 * @param xid the transaction id chosen by the client and echoed by the server.
 * @param clientMac the client hardware address, exactly `hlen` octets (6 for Ethernet), at most 16.
 * @param hardwareType `htype`; 1 is Ethernet.
 * @param hops relay hop count.
 * @param secs seconds elapsed since the client began acquiring a lease.
 * @param flags the flags field; the high bit is the broadcast flag (see [isBroadcast]).
 * @param clientIp `ciaddr`: the client's current address when it has one, otherwise 0.0.0.0.
 * @param yourIp `yiaddr`: the address the server assigns.
 * @param serverIp `siaddr`: the next server for bootstrapping (unused here, 0.0.0.0).
 * @param gatewayIp `giaddr`: the relay agent address (unused here, 0.0.0.0).
 * @param options option code to data, in wire order; codes 0 (pad) and 255 (end) are not options.
 */
class DhcpMessage(
    val op: Int,
    val xid: Int,
    clientMac: ByteArray,
    val hardwareType: Int = HARDWARE_TYPE_ETHERNET,
    val hops: Int = 0,
    val secs: Int = 0,
    val flags: Int = 0,
    val clientIp: Ipv4Address = Ipv4Address.ANY,
    val yourIp: Ipv4Address = Ipv4Address.ANY,
    val serverIp: Ipv4Address = Ipv4Address.ANY,
    val gatewayIp: Ipv4Address = Ipv4Address.ANY,
    options: Map<Int, ByteArray> = emptyMap(),
) {
    private val clientMacBytes: ByteArray = clientMac.copyOf()

    /** Option code to data, in wire order. The map is read-only; treat the arrays as read-only too. */
    val options: Map<Int, ByteArray> = LinkedHashMap<Int, ByteArray>().also { copy ->
        for ((code, data) in options) {
            require(code != DhcpOption.PAD && code != DhcpOption.END) { "option code $code is reserved" }
            require(code in 1..MAX_OPTION_CODE && data.size <= MAX_OPTION_DATA) {
                "option does not fit: code=$code size=${data.size}"
            }
            copy[code] = data.copyOf()
        }
    }

    init {
        require(clientMacBytes.size <= CHADDR_BYTES) { "client hardware address longer than $CHADDR_BYTES: size=${clientMacBytes.size}" }
    }

    /** The client hardware address, `hlen` octets, a copy. */
    val clientMac: ByteArray get() = clientMacBytes.copyOf()

    /** `hlen`: the length of [clientMac] in octets. */
    val hardwareLength: Int get() = clientMacBytes.size

    /** True when the client set the broadcast flag: it cannot receive unicast before it is configured. */
    val isBroadcast: Boolean get() = flags and FLAG_BROADCAST != 0

    /** Option 53, or null when absent or malformed. */
    val messageType: Int? get() = options[DhcpOption.MESSAGE_TYPE]?.takeIf { it.size == 1 }?.readU8(0)

    /** Option 50 (requested IP address), or null when absent or malformed. */
    val requestedIp: Ipv4Address? get() = addressOption(DhcpOption.REQUESTED_IP)

    /** Option 54 (server identifier), or null when absent or malformed. */
    val serverIdentifier: Ipv4Address? get() = addressOption(DhcpOption.SERVER_IDENTIFIER)

    /** Option 1 (subnet mask), or null when absent or malformed. */
    val subnetMask: Ipv4Address? get() = addressOption(DhcpOption.SUBNET_MASK)

    /** Option 51 (lease time) in seconds as an unsigned value, or null when absent or malformed. */
    val leaseSeconds: Long?
        get() = options[DhcpOption.LEASE_TIME]?.takeIf { it.size == Ipv4Address.SIZE }?.readI32(0)?.toLong()?.and(UNSIGNED_INT_MASK)

    /** The on-wire form: fixed header, magic cookie, options, end marker, zero padded to 300 octets. */
    fun encode(): ByteArray {
        var optionsLength = 1 // the end marker
        for ((_, data) in options) optionsLength += OPTION_HEADER_BYTES + data.size
        val wire = ByteArray(maxOf(MIN_WIRE_BYTES, OPTIONS_OFFSET + optionsLength))
        wire.writeU8(OP_OFFSET, op)
        wire.writeU8(HTYPE_OFFSET, hardwareType)
        wire.writeU8(HLEN_OFFSET, clientMacBytes.size)
        wire.writeU8(HOPS_OFFSET, hops)
        wire.writeI32(XID_OFFSET, xid)
        wire.writeU16(SECS_OFFSET, secs)
        wire.writeU16(FLAGS_OFFSET, flags)
        clientIp.toByteArray().copyInto(wire, CIADDR_OFFSET)
        yourIp.toByteArray().copyInto(wire, YIADDR_OFFSET)
        serverIp.toByteArray().copyInto(wire, SIADDR_OFFSET)
        gatewayIp.toByteArray().copyInto(wire, GIADDR_OFFSET)
        clientMacBytes.copyInto(wire, CHADDR_OFFSET)
        wire.writeI32(COOKIE_OFFSET, MAGIC_COOKIE)
        var index = OPTIONS_OFFSET
        for ((code, data) in options) {
            wire.writeU8(index, code)
            wire.writeU8(index + 1, data.size)
            data.copyInto(wire, index + OPTION_HEADER_BYTES)
            index += OPTION_HEADER_BYTES + data.size
        }
        wire.writeU8(index, DhcpOption.END)
        return wire
    }

    private fun addressOption(code: Int): Ipv4Address? =
        options[code]?.takeIf { it.size == Ipv4Address.SIZE }?.let { Ipv4Address.fromBytes(it) }

    companion object {
        /** `op` 1: a client request. */
        const val OP_REQUEST = 1

        /** `op` 2: a server reply. */
        const val OP_REPLY = 2

        /** `htype` 1: Ethernet. */
        const val HARDWARE_TYPE_ETHERNET = 1

        /** The broadcast bit of the flags field. */
        const val FLAG_BROADCAST = 0x8000

        /** Size of the fixed header (through `file`) plus the magic cookie: where options begin. */
        const val OPTIONS_OFFSET = 240

        /** Replies are padded to at least this many octets, the BOOTP minimum (236 fixed + 64 vendor). */
        const val MIN_WIRE_BYTES = 300

        private const val MAGIC_COOKIE = 0x63825363
        private const val CHADDR_BYTES = 16
        private const val MAX_OPTION_CODE = 254
        private const val MAX_OPTION_DATA = 255
        private const val OPTION_HEADER_BYTES = 2
        private const val UNSIGNED_INT_MASK = 0xFFFFFFFFL
        private const val OP_OFFSET = 0
        private const val HTYPE_OFFSET = 1
        private const val HLEN_OFFSET = 2
        private const val HOPS_OFFSET = 3
        private const val XID_OFFSET = 4
        private const val SECS_OFFSET = 8
        private const val FLAGS_OFFSET = 10
        private const val CIADDR_OFFSET = 12
        private const val YIADDR_OFFSET = 16
        private const val SIADDR_OFFSET = 20
        private const val GIADDR_OFFSET = 24
        private const val CHADDR_OFFSET = 28
        private const val COOKIE_OFFSET = 236

        /**
         * Parses a BOOTP/DHCP message from the start of [bytes]. Returns null when the payload is
         * shorter than the fixed header plus the cookie, the magic cookie is wrong, `hlen` exceeds the
         * 16-octet `chaddr` field, or an option runs past the end of the payload. A missing end marker
         * is tolerated (the options end with the payload). Duplicate option codes keep the first.
         */
        fun parse(bytes: ByteArray): DhcpMessage? {
            if (bytes.size < OPTIONS_OFFSET) return null
            if (bytes.readI32(COOKIE_OFFSET) != MAGIC_COOKIE) return null
            val hardwareLength = bytes.readU8(HLEN_OFFSET)
            if (hardwareLength > CHADDR_BYTES) return null
            val options = parseOptions(bytes) ?: return null
            return DhcpMessage(
                op = bytes.readU8(OP_OFFSET),
                xid = bytes.readI32(XID_OFFSET),
                clientMac = bytes.copyOfRange(CHADDR_OFFSET, CHADDR_OFFSET + hardwareLength),
                hardwareType = bytes.readU8(HTYPE_OFFSET),
                hops = bytes.readU8(HOPS_OFFSET),
                secs = bytes.readU16(SECS_OFFSET),
                flags = bytes.readU16(FLAGS_OFFSET),
                clientIp = Ipv4Address.fromBytes(bytes, CIADDR_OFFSET),
                yourIp = Ipv4Address.fromBytes(bytes, YIADDR_OFFSET),
                serverIp = Ipv4Address.fromBytes(bytes, SIADDR_OFFSET),
                gatewayIp = Ipv4Address.fromBytes(bytes, GIADDR_OFFSET),
                options = options,
            )
        }

        private fun parseOptions(bytes: ByteArray): Map<Int, ByteArray>? {
            val options = LinkedHashMap<Int, ByteArray>()
            var index = OPTIONS_OFFSET
            while (index < bytes.size) {
                val code = bytes.readU8(index)
                if (code == DhcpOption.END) break
                if (code == DhcpOption.PAD) {
                    index++
                    continue
                }
                if (index + 1 >= bytes.size) return null
                val end = index + OPTION_HEADER_BYTES + bytes.readU8(index + 1)
                if (end > bytes.size) return null
                if (code !in options) options[code] = bytes.copyOfRange(index + OPTION_HEADER_BYTES, end)
                index = end
            }
            return options
        }
    }
}
