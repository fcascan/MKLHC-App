package com.mklhc.core.domain

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** [PairedKart]: identity of the kart this installation is paired with, compared by content. */
class PairedKartTest {

    private fun ident(seed: Int) = ByteArray(PairedKart.IDENT_SIZE) { (seed + it).toByte() }

    @Test
    fun identIsComparedByContent() {
        assertEquals(PairedKart("S1", ident(1)), PairedKart("S1", ident(1)))
        assertEquals(PairedKart("S1", ident(1)).hashCode(), PairedKart("S1", ident(1)).hashCode())
        assertNotEquals(PairedKart("S1", ident(1)), PairedKart("S1", ident(2)))
        assertNotEquals(PairedKart("S1", ident(1)), PairedKart("S2", ident(1)))
    }

    @Test
    fun identMustBeSixteenOctets() {
        assertFailsWith<IllegalArgumentException> { PairedKart("S1", ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { PairedKart("S1", ByteArray(17)) }
    }

    @Test
    fun constructorCopiesTheIdentAndReadsAreCopies() {
        val source = ident(5)
        val kart = PairedKart("S1", source)
        source[0] = 0x7F
        assertContentEquals(ident(5), kart.ident)

        kart.ident[1] = 0x7F
        assertContentEquals(ident(5), kart.ident)
    }

    @Test
    fun toStringShowsTheSerialButNotTheIdent() {
        val text = PairedKart("S1", ident(0x41)).toString()
        assertEquals(true, text.contains("S1"))
        assertFalse(text.contains("41424344"), "the ident must not be echoed as hex")
        assertFalse(text.contains("65, 66"), "the ident must not be echoed as decimal octets")
    }
}
