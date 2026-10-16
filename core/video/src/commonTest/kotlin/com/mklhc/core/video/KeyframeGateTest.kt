package com.mklhc.core.video

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KeyframeGate]: decoding must not begin until an IDR picture is in hand together with the stream's SPS and
 * PPS, the parameter sets may arrive in the IDR's own access unit or earlier, and after the stream is declared
 * out of sync (the decoder fell behind) every picture is skipped until the next IDR.
 */
class KeyframeGateTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val sps = bytes(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
    private val pps = bytes(0, 0, 0, 1, 0x68, 0xCE, 0x3C, 0x80)
    private val idr = bytes(0, 0, 0, 1, 0x65, 0x88, 0x84, 0x21)
    private val slice = bytes(0, 0, 0, 1, 0x41, 0x9A, 0x24, 0x6C)

    private fun KeyframeGate.Decision.asDecode() = assertIs<KeyframeGate.Decision.Decode>(this)

    @Test
    fun skipsEverythingBeforeTheFirstKeyframe() {
        val gate = KeyframeGate()

        assertEquals(KeyframeGate.Decision.Skip, gate.decide(slice))
        assertEquals(KeyframeGate.Decision.Skip, gate.decide(slice))
    }

    @Test
    fun anIdrWithItsOwnParameterSetsStartsTheDecoder() {
        val gate = KeyframeGate()

        val decode = gate.decide(sps + pps + idr).asDecode()

        assertTrue(decode.keyFrame)
        val sets = assertNotNull(decode.parameterSets)
        assertContentEquals(sps, sets.sps)
        assertContentEquals(pps, sets.pps)
    }

    @Test
    fun parameterSetsFromEarlierAccessUnitsAreRemembered() {
        val gate = KeyframeGate()
        assertEquals(KeyframeGate.Decision.Skip, gate.decide(sps))
        assertEquals(KeyframeGate.Decision.Skip, gate.decide(pps))

        val decode = gate.decide(idr).asDecode()

        assertTrue(decode.keyFrame)
        assertContentEquals(sps, assertNotNull(decode.parameterSets).sps)
    }

    @Test
    fun anIdrWithoutParameterSetsCannotStartTheDecoder() {
        val gate = KeyframeGate()

        assertEquals(KeyframeGate.Decision.Skip, gate.decide(idr))
        assertEquals(KeyframeGate.Decision.Skip, gate.decide(sps + idr))
    }

    @Test
    fun onceRunningEveryPictureIsDecodedAndOnlyTheStartCarriesParameterSets() {
        val gate = KeyframeGate()
        gate.decide(sps + pps + idr)

        val next = gate.decide(slice).asDecode()

        assertFalse(next.keyFrame)
        assertNull(next.parameterSets)
        assertTrue(gate.decide(idr).asDecode().keyFrame)
        assertNull(gate.decide(idr).asDecode().parameterSets)
    }

    @Test
    fun afterLosingSyncPicturesAreSkippedUntilTheNextKeyframe() {
        val gate = KeyframeGate()
        gate.decide(sps + pps + idr)
        gate.lostSync()

        assertEquals(KeyframeGate.Decision.Skip, gate.decide(slice))
        val restart = gate.decide(idr).asDecode()
        assertTrue(restart.keyFrame)
        // The remembered parameter sets let it restart without waiting for new ones.
        assertNotNull(restart.parameterSets)
        assertFalse(gate.decide(slice).asDecode().keyFrame)
    }

    @Test
    fun newerParameterSetsReplaceOlderOnes() {
        val gate = KeyframeGate()
        gate.decide(sps + pps + idr)
        gate.lostSync()
        val newSps = bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28)

        val restart = gate.decide(newSps + pps + idr).asDecode()

        assertContentEquals(newSps, assertNotNull(restart.parameterSets).sps)
    }
}
