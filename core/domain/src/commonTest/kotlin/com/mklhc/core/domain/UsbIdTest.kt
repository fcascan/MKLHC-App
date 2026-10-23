package com.mklhc.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** [UsbId]: range checks, the `vvvv:pppp` text form and its tolerant-but-strict parser. */
class UsbIdTest {

    @Test
    fun formatsAsFourLowercaseHexDigitsEach() {
        assertEquals("2357:012d", UsbId(0x2357, 0x012D).toString())
        assertEquals("0bda:c812", UsbId(0x0BDA, 0xC812).toString())
        assertEquals("0000:0000", UsbId(0, 0).toString())
        assertEquals("ffff:ffff", UsbId(0xFFFF, 0xFFFF).toString())
    }

    @Test
    fun valuesOutsideSixteenBitsAreRejected() {
        assertFailsWith<IllegalArgumentException> { UsbId(-1, 0) }
        assertFailsWith<IllegalArgumentException> { UsbId(0, -1) }
        assertFailsWith<IllegalArgumentException> { UsbId(0x10000, 0) }
        assertFailsWith<IllegalArgumentException> { UsbId(0, 0x10000) }
    }

    @Test
    fun parsesLowerAndUpperCase() {
        assertEquals(UsbId(0x2357, 0x012D), UsbId.parse("2357:012d"))
        assertEquals(UsbId(0x0BDA, 0xC812), UsbId.parse("0BDA:C812"))
        assertEquals(UsbId(0x0BDA, 0xC812), UsbId.parse("0bda:C812"))
    }

    @Test
    fun parseRoundTripsToString() {
        val id = UsbId(0x0BDA, 0x8812)
        assertEquals(id, UsbId.parse(id.toString()))
    }

    @Test
    fun parseReturnsNullOnBadInput() {
        listOf(
            "",
            "auto",
            "2357",
            "2357:",
            ":012d",
            "2357:012",
            "2357:012d0",
            "357:012d",
            "2357-012d",
            "2357:012d:1",
            "0x57:0x2d",
            "235g:012d",
            "+357:012d",
            "-357:012d",
            " 2357:012d",
            "2357:012d ",
            "2357 : 012d",
        ).forEach { assertNull(UsbId.parse(it), "'$it' must not parse") }
    }
}
