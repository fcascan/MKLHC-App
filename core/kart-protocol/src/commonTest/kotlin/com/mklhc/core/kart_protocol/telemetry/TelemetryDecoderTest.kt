package com.mklhc.core.kart_protocol.telemetry

import co.touchlab.kermit.Severity
import com.mklhc.core.kart_protocol.CapturingLogWriter
import com.mklhc.core.kart_protocol.capturingLogger
import com.mklhc.core.kart_protocol.fuji.assertBytes
import com.mklhc.core.kart_protocol.hexBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Known-answer tests for [TelemetryDecoder]: the dispatch on the type octet, the type-1 battery status,
 * and the unknown / type-3 / empty cases.
 *
 * The battery vectors are hand-built from the reference handler (OpenKart `handle_status`): the type
 * octet is stripped, the remaining payload must be 0x1f octets, `cable_connected = payload[0] & 1` and
 * `battery_state = payload[3]`. So in the full datagram the cable octet is at index 1 and the battery
 * bars at index 4. Octets the decoder must not read are filled with 0xaa so a wrong index shows up.
 */
class TelemetryDecoderTest {

    private val writer = CapturingLogWriter()
    private val decoder = TelemetryDecoder(capturingLogger(writer, "TelemetryDecoderTest"))

    /** A type-1 datagram: the cable octet, two filler octets, the bars octet, then filler up to [payloadSize]. */
    private fun batteryPacket(cable: Int, bars: Int, payloadSize: Int = 0x1f): ByteArray {
        val packet = ByteArray(1 + payloadSize) { 0xAA.toByte() }
        packet[0] = 0x01
        if (payloadSize > 0) packet[1] = cable.toByte()
        if (payloadSize > 3) packet[4] = bars.toByte()
        return packet
    }

    @Test
    fun `a literal type 1 datagram decodes to cable on and 4 bars`() {
        // 01 = type; payload: 01 = cable octet, aa aa = unread, 04 = bars, then 27 unread octets (0x1f total).
        val packet = hexBytes("01  01 aa aa 04  ${"aa ".repeat(27)}")

        val telemetry = decoder.decode(packet)

        assertEquals(1 + 0x1f, packet.size)
        assertEquals(Telemetry.BatteryStatus(cableFlags = 1, batteryBars = 4), telemetry)
    }

    @Test
    fun `cable connected is bit 0 of the first payload octet`() {
        val on = decoder.decode(batteryPacket(cable = 0b001, bars = 2))
        val off = decoder.decode(batteryPacket(cable = 0b000, bars = 2))

        assertIs<Telemetry.BatteryStatus>(on)
        assertIs<Telemetry.BatteryStatus>(off)
        assertTrue(on.cableConnected)
        assertFalse(off.cableConnected)
    }

    @Test
    fun `the unknown cable bits 1 and 2 do not change cable connected but are exposed raw`() {
        val bit1And2Only = decoder.decode(batteryPacket(cable = 0b110, bars = 0))
        val allThree = decoder.decode(batteryPacket(cable = 0b111, bars = 0))

        assertIs<Telemetry.BatteryStatus>(bit1And2Only)
        assertIs<Telemetry.BatteryStatus>(allThree)
        assertFalse(bit1And2Only.cableConnected, "bit 0 clear means not connected")
        assertEquals(0b110, bit1And2Only.cableFlags)
        assertTrue(allThree.cableConnected)
        assertEquals(0b111, allThree.cableFlags)
    }

    @Test
    fun `the whole cable octet is kept as an unsigned value`() {
        val telemetry = decoder.decode(batteryPacket(cable = 0xF1, bars = 0))

        assertIs<Telemetry.BatteryStatus>(telemetry)
        assertEquals(0xF1, telemetry.cableFlags)
        assertTrue(telemetry.cableConnected)
    }

    @Test
    fun `battery bars 0 to 4 are read from the fourth payload octet`() {
        for (bars in 0..4) {
            val telemetry = decoder.decode(batteryPacket(cable = 0, bars = bars))

            assertIs<Telemetry.BatteryStatus>(telemetry)
            assertEquals(bars, telemetry.batteryBars, "bars $bars")
        }
    }

    @Test
    fun `a bars value outside 0 to 4 is reported as the kart sent it`() {
        val telemetry = decoder.decode(batteryPacket(cable = 0, bars = 0xFF))

        assertIs<Telemetry.BatteryStatus>(telemetry)
        assertEquals(255, telemetry.batteryBars)
    }

    @Test
    fun `a short battery payload is returned as Unknown and logged without throwing`() {
        val short = batteryPacket(cable = 1, bars = 3, payloadSize = 0x1f - 1)

        val telemetry = decoder.decode(short)

        assertIs<Telemetry.Unknown>(telemetry)
        assertEquals(1, telemetry.type)
        assertBytes(short.copyOfRange(1, short.size), telemetry.raw, "raw payload kept")
        assertTrue(writer.logged(Severity.Warn), "the short payload is logged as a warning")
    }

    @Test
    fun `a battery datagram with no payload at all is Unknown`() {
        val telemetry = decoder.decode(byteArrayOf(0x01))

        assertEquals(Telemetry.Unknown(type = 1, raw = ByteArray(0)), telemetry)
    }

    @Test
    fun `a payload of only 3 octets cannot hold the bars and is Unknown`() {
        val telemetry = decoder.decode(batteryPacket(cable = 1, bars = 2, payloadSize = 3))

        assertIs<Telemetry.Unknown>(telemetry)
    }

    @Test
    fun `a longer than expected battery payload still decodes and is logged`() {
        val telemetry = decoder.decode(batteryPacket(cable = 1, bars = 3, payloadSize = 0x1f + 4))

        assertEquals(Telemetry.BatteryStatus(cableFlags = 1, batteryBars = 3), telemetry)
        assertTrue(writer.logged(Severity.Warn), "the unexpected size is logged as a warning")
    }

    @Test
    fun `a well formed battery datagram logs no warning`() {
        decoder.decode(batteryPacket(cable = 1, bars = 3))

        assertFalse(writer.logged(Severity.Warn))
        assertFalse(writer.logged(Severity.Error))
    }

    @Test
    fun `type 3 is Unknown with its payload kept and is logged`() {
        val packet = hexBytes("03 11 22 33")

        val telemetry = decoder.decode(packet)

        assertEquals(Telemetry.Unknown(type = 3, raw = hexBytes("11 22 33")), telemetry)
        assertTrue(writer.entries.isNotEmpty(), "type 3 is logged")
    }

    @Test
    fun `an unrecognised type is Unknown and logged as a warning`() {
        val telemetry = decoder.decode(hexBytes("7f aa bb"))

        assertEquals(Telemetry.Unknown(type = 0x7F, raw = hexBytes("aa bb")), telemetry)
        assertTrue(writer.logged(Severity.Warn))
    }

    @Test
    fun `the type octet is unsigned so 0xff is type 255 and 0x00 is type 0`() {
        val high = decoder.decode(hexBytes("ff 01"))
        val zero = decoder.decode(hexBytes("00"))

        assertEquals(Telemetry.Unknown(type = 255, raw = hexBytes("01")), high)
        assertEquals(Telemetry.Unknown(type = 0, raw = ByteArray(0)), zero)
    }

    @Test
    fun `an empty datagram is Unknown with no type and is logged`() {
        val telemetry = decoder.decode(ByteArray(0))

        assertEquals(Telemetry.Unknown(type = Telemetry.Unknown.NO_TYPE, raw = ByteArray(0)), telemetry)
        assertTrue(writer.logged(Severity.Warn))
    }

    @Test
    fun `Unknown compares its raw payload by content`() {
        val a = Telemetry.Unknown(type = 9, raw = hexBytes("01 02"))
        val b = Telemetry.Unknown(type = 9, raw = hexBytes("01 02"))
        val c = Telemetry.Unknown(type = 9, raw = hexBytes("01 03"))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `the decoder does not modify the datagram it is given`() {
        val packet = batteryPacket(cable = 1, bars = 4)
        val before = packet.copyOf()

        decoder.decode(packet)

        assertBytes(before, packet, "input datagram")
    }
}
