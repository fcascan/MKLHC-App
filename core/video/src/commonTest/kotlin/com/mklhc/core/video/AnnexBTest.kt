package com.mklhc.core.video

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AnnexB.scan] on hand-built access units: it must find every NAL unit, with its type, whether the stream uses
 * three- or four-octet start codes, and must hand back each unit's exact octets (start code included, trailing
 * zero padding removed) because those octets are fed to the decoder as its parameter sets.
 */
class AnnexBTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
    private val pps = bytes(0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21)
    private val slice = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x24, 0x6C)

    @Test
    fun findsEachNalUnitWithItsType() {
        val units = AnnexB.scan(sps + pps + idr)

        assertEquals(listOf(7, 8, 5), units.map { it.type })
    }

    @Test
    fun unitRangesCoverTheStartCodeAndThePayloadExactly() {
        val data = sps + pps + idr

        val units = AnnexB.scan(data)

        assertContentEquals(sps, data.copyOfRange(units[0].start, units[0].end))
        assertContentEquals(pps, data.copyOfRange(units[1].start, units[1].end))
        assertContentEquals(idr, data.copyOfRange(units[2].start, units[2].end))
    }

    @Test
    fun handlesThreeOctetStartCodes() {
        val shortSps = bytes(0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
        val shortIdr = bytes(0, 0, 1, 0x65, 0x88, 0x84)

        val units = AnnexB.scan(shortSps + shortIdr)

        assertEquals(listOf(7, 5), units.map { it.type })
        assertEquals(0, units[0].start)
        assertEquals(shortSps.size, units[1].start)
    }

    @Test
    fun trailingZeroPaddingBelongsToNoUnit() {
        val padded = sps + bytes(0, 0) + idr

        val units = AnnexB.scan(padded)

        assertEquals(listOf(7, 5), units.map { it.type })
        assertContentEquals(sps, padded.copyOfRange(units[0].start, units[0].end))
    }

    @Test
    fun aStartCodeWithNoHeaderOctetIsIgnored() {
        assertTrue(AnnexB.scan(bytes(0, 0, 1)).isEmpty())
    }

    @Test
    fun dataWithoutStartCodesHasNoUnits() {
        assertTrue(AnnexB.scan(bytes(0x65, 0x88, 0x84, 0x21)).isEmpty())
        assertTrue(AnnexB.scan(ByteArray(0)).isEmpty())
    }
}
