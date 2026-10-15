package com.mklhc.core.netstack

/**
 * The identity of the access point's network stack on the kart link.
 *
 * Business logic: the stack answers ARP and DHCP and originates IPv4 packets as one host, the AP.
 * [ourIp] is the address it answers ARP for, uses as the IPv4 source of everything it sends and
 * announces as the DHCP server identifier; [ourMac] is the hardware address the ARP reply carries,
 * which for an 802.11 access point is its BSSID (the address the kart sees frames coming from).
 *
 * Original code; there is nothing to configure beyond these two values because the link has exactly
 * one neighbour and the address plan is fixed (see [AddressPlan]).
 *
 * @param ourIp our IPv4 address; defaults to [AddressPlan.AP].
 * @param ourMac our six-octet hardware address (a copy is kept).
 */
class NetstackConfig(
    val ourIp: Ipv4Address = AddressPlan.AP,
    ourMac: ByteArray,
) {
    private val macBytes: ByteArray = ourMac.copyOf()

    init {
        require(macBytes.size == MAC_SIZE) { "our MAC must be $MAC_SIZE octets: size=${macBytes.size}" }
    }

    /** Our hardware address, a copy of the six octets. */
    val ourMac: ByteArray get() = macBytes.copyOf()

    private companion object {
        const val MAC_SIZE = 6
    }
}
