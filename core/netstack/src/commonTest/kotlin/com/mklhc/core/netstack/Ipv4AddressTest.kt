package com.mklhc.core.netstack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [Ipv4Address] and the [AddressPlan] constants.
 *
 * The plan values are the OpenKart ones (subnet 169.254.98.64/26, AP .65, kart lease .66); they are
 * asserted as literal octets so an accidental change of the plan fails loudly.
 */
class Ipv4AddressTest {

    @Test
    fun parseThenToByteArrayYieldsTheFourOctetsInOrder() {
        val address = Ipv4Address.parse("169.254.98.65")

        assertEquals("a9fe6241", address.toByteArray().toHexString())
    }

    @Test
    fun toStringRendersDottedDecimal() {
        assertEquals("169.254.98.65", Ipv4Address.parse("169.254.98.65").toString())
        assertEquals("0.0.0.0", Ipv4Address.ANY.toString())
        assertEquals("255.255.255.255", Ipv4Address.BROADCAST.toString())
    }

    @Test
    fun fromBytesReadsFourOctetsAtTheOffset() {
        val bytes = "ffff" + "c0a80001" + "ff"

        assertEquals("192.168.0.1", Ipv4Address.fromBytes(bytes.hexToByteArray(), offset = 2).toString())
    }

    @Test
    fun addressesWithTheSameOctetsAreEqual() {
        assertEquals(Ipv4Address.parse("10.0.0.1"), Ipv4Address.fromBytes("0a000001".hexToByteArray()))
    }

    @Test
    fun parseRejectsMalformedText() {
        val malformed = listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "a.b.c.d", "1..2.3", "1.2.3.-4", "1.2.3.1234", " 1.2.3.4")

        for (text in malformed) {
            assertFailsWith<IllegalArgumentException>("'$text' must be rejected") { Ipv4Address.parse(text) }
        }
    }

    @Test
    fun addressPlanMatchesTheOpenKartConfiguration() {
        assertEquals("169.254.98.64", AddressPlan.SUBNET.toString())
        assertEquals("255.255.255.192", AddressPlan.NETMASK.toString())
        assertEquals("169.254.98.65", AddressPlan.AP.toString())
        assertEquals("169.254.98.66", AddressPlan.KART_LEASE.toString())
        assertEquals(26, AddressPlan.PREFIX_LENGTH)
    }
}
