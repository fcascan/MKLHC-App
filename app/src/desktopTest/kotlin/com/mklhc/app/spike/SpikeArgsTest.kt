package com.mklhc.app.spike

import com.mklhc.core.wifi_ap.ap.ApConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [parseSpikeArgs]: the spike's command line, parsed without touching the process so it can be tested. The defaults
 * are the reference adapter on channel 6; bad input is reported as a [SpikeArgsException] the user can read.
 */
class SpikeArgsTest {

    @Test
    fun noArgumentsGiveTheReferenceAdapterOnChannel6() {
        val args = parseSpikeArgs(emptyList())

        assertEquals(SpikeOptions.DEFAULT_VID, args.vid)
        assertEquals(SpikeOptions.DEFAULT_PID, args.pid)
        assertEquals(0x0BDA, args.vid)
        assertEquals(0xC812, args.pid)
        assertEquals(6, args.channel)
        assertEquals(0, args.txPowerIndex)
        assertEquals(ApConfig.DEFAULT_BEACON_INTERVAL_TU, args.beaconIntervalTu)
        assertEquals(Path.of("spike-captures"), args.outDir)
        assertEquals(0L, args.durationSeconds)
        assertEquals(SpikeOptions.DEFAULT_READY_TIMEOUT_MS, args.readyTimeoutMs)
        assertNull(args.radiod)
        assertFalse(args.verbose)
        assertTrue(args.showQr)
        assertFalse(args.lightTerminal)
        assertFalse(args.help)
    }

    @Test
    fun optionsAcceptBothTheSeparatedAndTheEqualsForm() {
        val args = parseSpikeArgs(
            listOf(
                "--radiod", "C:\\tools\\radiod.exe",
                "--vid=0x2357", "--pid", "012d",
                "--channel", "11",
                "--tx-power=20",
                "--beacon-interval", "50",
                "--out=captures",
                "--duration", "300",
                "--ready-timeout=120",
            ),
        )

        assertEquals(Path.of("C:\\tools\\radiod.exe"), args.radiod)
        assertEquals(0x2357, args.vid)
        assertEquals(0x012D, args.pid)
        assertEquals(11, args.channel)
        assertEquals(20, args.txPowerIndex)
        assertEquals(50, args.beaconIntervalTu)
        assertEquals(Path.of("captures"), args.outDir)
        assertEquals(300L, args.durationSeconds)
        assertEquals(120_000L, args.readyTimeoutMs)
    }

    @Test
    fun flagsTakeNoValue() {
        val args = parseSpikeArgs(listOf("--verbose", "--no-qr", "--light-terminal"))

        assertTrue(args.verbose)
        assertFalse(args.showQr)
        assertTrue(args.lightTerminal)
        assertTrue(parseSpikeArgs(listOf("--help")).help)
        assertTrue(parseSpikeArgs(listOf("-h")).help)
    }

    @Test
    fun badInputIsRefusedWithAReadableMessage() {
        fun message(vararg arguments: String): String =
            assertFailsWith<SpikeArgsException> { parseSpikeArgs(arguments.toList()) }.message.orEmpty()

        assertTrue("unknown option" in message("--frobnicate"))
        assertTrue("--channel" in message("--channel", "3"), "only 1, 6 and 11 are valid channels")
        assertTrue("--channel" in message("--channel", "abc"))
        assertTrue("--vid" in message("--vid", "zzzz"))
        assertTrue("--vid" in message("--vid", "10000"), "a USB id is at most 16 bits")
        assertTrue("--tx-power" in message("--tx-power", "256"))
        assertTrue("--beacon-interval" in message("--beacon-interval", "0"))
        assertTrue("--duration" in message("--duration", "-1"))
        assertTrue("--ready-timeout" in message("--ready-timeout", "0"))
        assertTrue("--ready-timeout" in message("--ready-timeout", "601"))
        assertTrue("needs a value" in message("--radiod"))
    }
}
