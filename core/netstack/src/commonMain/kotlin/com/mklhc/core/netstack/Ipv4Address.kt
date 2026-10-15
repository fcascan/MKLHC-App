package com.mklhc.core.netstack

import kotlin.jvm.JvmInline

/**
 * An IPv4 address (RFC 791), held as its 32-bit big-endian value and compared by value.
 *
 * Business logic: the stack addresses the kart by IPv4 address only, so addresses need cheap
 * equality (a raw `ByteArray` compares by reference), a dotted-decimal form for logs and config, and
 * a four-octet wire form. [bits] keeps the octets in network order, so `169.254.98.65` is
 * `0xA9FE6241`; values above `0x7FFFFFFF` are negative, which only matters for the sign.
 *
 * Original code, implemented from RFC 791.
 */
@JvmInline
value class Ipv4Address(val bits: Int) {

    /** The four octets in transmission order. */
    fun toByteArray(): ByteArray = ByteArray(SIZE).also { it.writeI32(0, bits) }

    /** The octet at [index] (0 is the most significant), as 0..255. */
    fun octet(index: Int): Int {
        require(index in 0 until SIZE) { "an IPv4 address has $SIZE octets: index=$index" }
        return (bits ushr ((SIZE - 1 - index) * Byte.SIZE_BITS)) and OCTET_MASK
    }

    /** Dotted decimal, for example `169.254.98.65`. */
    override fun toString(): String = (0 until SIZE).joinToString(".") { octet(it).toString() }

    companion object {
        /** Number of octets in an IPv4 address. */
        const val SIZE = 4

        private const val OCTET_MASK = 0xFF
        private const val MAX_DIGITS_PER_OCTET = 3

        /** 0.0.0.0, the "this host" source address a DHCP client uses before it has a lease. */
        val ANY: Ipv4Address = Ipv4Address(0)

        /** 255.255.255.255, the limited broadcast address. */
        val BROADCAST: Ipv4Address = Ipv4Address(-1)

        /** Reads the four octets starting at [offset] of [bytes]. */
        fun fromBytes(bytes: ByteArray, offset: Int = 0): Ipv4Address {
            require(offset >= 0 && bytes.size - offset >= SIZE) { "need $SIZE octets at offset $offset" }
            return Ipv4Address(bytes.readI32(offset))
        }

        /** Parses dotted decimal `a.b.c.d` (each part 0..255, plain digits only). */
        fun parse(text: String): Ipv4Address {
            val parts = text.split('.')
            require(parts.size == SIZE) { "an IPv4 address has $SIZE dot-separated parts: $text" }
            var bits = 0
            for (part in parts) {
                val isPlainNumber = part.length in 1..MAX_DIGITS_PER_OCTET && part.all { it in '0'..'9' }
                val value = if (isPlainNumber) part.toInt() else -1
                require(value in 0..OCTET_MASK) { "bad IPv4 address part '$part' in $text" }
                bits = (bits shl Byte.SIZE_BITS) or value
            }
            return Ipv4Address(bits)
        }
    }
}

/**
 * The fixed address plan of the kart link.
 *
 * Business logic: the kart joins a tiny dead-end network served by this app. The plan is a /26 in
 * the IPv4 link-local range with the AP on the first host address and exactly one DHCP lease for
 * the kart on the second.
 *
 * Address plan mirrors OpenKart (https://github.com/OpenKart-SDK) openkart.conf.example /
 * openkartd/config.py (BSD-3-Clause). OpenKart configures `network = 169.254.98.64/26`, puts the AP
 * on the first usable address and starts udhcpd with `start` at network+2 (the kart's address) and
 * `option subnet` as the netmask. No third-party code is copied.
 */
object AddressPlan {
    /** Prefix length of the kart subnet (a /26). */
    const val PREFIX_LENGTH = 26

    /** The subnet's network address, 169.254.98.64. */
    val SUBNET: Ipv4Address = Ipv4Address.parse("169.254.98.64")

    /** The subnet mask for [PREFIX_LENGTH], 255.255.255.192. */
    val NETMASK: Ipv4Address = Ipv4Address.parse("255.255.255.192")

    /** Our (the access point's) address, 169.254.98.65. */
    val AP: Ipv4Address = Ipv4Address.parse("169.254.98.65")

    /** The single DHCP lease handed to the kart, 169.254.98.66. */
    val KART_LEASE: Ipv4Address = Ipv4Address.parse("169.254.98.66")
}
