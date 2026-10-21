package com.mklhc.app.spike

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [awaitEnterKey]: the spike's console Enter, read from a stream so it can be tested without a console. The case that
 * matters is the lone CR: a terminal that writes the Enter key straight into a pipe sends it, and `readlnOrNull()`
 * (what the spike used before) never returned on it, so the run could not be stopped from that terminal.
 */
class SpikeEnterKeyTest {

    @Test
    fun anEnterEndsTheLineWhetherItIsLfCrlfOrALoneCr() {
        assertTrue(awaitEnterKey(bytes("\n")), "LF")
        assertTrue(awaitEnterKey(bytes("\r\n")), "CRLF")
        assertTrue(awaitEnterKey(bytes("\r")), "a lone CR")
        assertTrue(awaitEnterKey(bytes("stop\r")), "text, then a lone CR")
        assertTrue(awaitEnterKey(bytes("stop\n")), "text, then LF")
    }

    @Test
    fun aLoneCrReturnsAtOnceWithoutWaitingForTheInputToEnd() {
        // After the CR the stream would block on a real console; reading further must be a test failure, not a hang.
        val input = object : InputStream() {
            private var served = false

            override fun read(): Int {
                check(!served) { "read past the Enter" }
                served = true
                return '\r'.code
            }
        }

        assertTrue(awaitEnterKey(input))
    }

    @Test
    fun theInputEndingWithNothingReadIsNotAnEnterButTextBeforeTheEndIs() {
        // A closed stdin (no console) must not stop the run; like readlnOrNull(), text then the end of the input is a line.
        assertFalse(awaitEnterKey(bytes("")), "a closed stdin")
        assertTrue(awaitEnterKey(bytes("stop")), "text, then the end of the input")
    }

    @Test
    fun readingStopsRightAfterTheFirstLineEndOctet() {
        val input = bytes("\r\nmore")

        assertTrue(awaitEnterKey(input))

        // The LF of a CRLF stays behind, which is harmless: the reader runs once and the run stops.
        assertEquals('\n'.code, input.read())
    }

    @Test
    fun aConsoleInputThatCannotBeReadIsNotAnEnterAndDoesNotThrow() {
        // No console, or an invalid handle: the reader thread must end quietly, and the run must not stop.
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("the handle is invalid")
        }

        assertFalse(awaitEnterKey(input))
    }

    private fun bytes(text: String): InputStream = ByteArrayInputStream(text.toByteArray())
}
