package com.mklhc.core.kart_protocol.teleop

import com.mklhc.core.kart_protocol.fuji.assertBytes
import com.mklhc.core.kart_protocol.hexBytes
import com.mklhc.core.kart_protocol.zeros
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

/**
 * Known-answer tests for [TeleopEncoder] and [DriveCommand].
 *
 * Every vector is hand-built from the teleoperation datagram layout of the community protocol wiki
 * (Fuji kart protocol), 32 octets: [0] throttle int8, [1] steering int8, [2] brake light (0/1), [3] zero,
 * [4..7] packet counter u32 little-endian, [8..31] zero. Throttle -128 is 0x80, +127 is 0x7f and -1 is
 * 0xff (two's complement); the counter 0x0A0B0C0D is stored least-significant octet first as 0d 0c 0b 0a.
 */
class TeleopEncoderTest {

    @Test
    fun `full forward throttle is 0x7f with the first counter value zero`() {
        val packet = TeleopEncoder().encode(DriveCommand(throttle = 127, steering = 0, brakeLight = false))

        assertBytes(
            hexBytes("7f 00 00 00  00 00 00 00  ${zeros(24)}"),
            packet,
            "throttle +127",
        )
    }

    @Test
    fun `full reverse throttle is 0x80`() {
        val packet = TeleopEncoder().encode(DriveCommand(throttle = -128, steering = 0, brakeLight = false))

        assertBytes(
            hexBytes("80 00 00 00  00 00 00 00  ${zeros(24)}"),
            packet,
            "throttle -128",
        )
    }

    @Test
    fun `steering is a signed octet in byte 1`() {
        val encoder = TeleopEncoder()

        val fullRight = encoder.encode(DriveCommand(0, 127, false))
        val fullLeft = encoder.encode(DriveCommand(0, -128, false))
        val slightLeft = encoder.encode(DriveCommand(0, -1, false))

        assertBytes(hexBytes("00 7f 00 00  00 00 00 00  ${zeros(24)}"), fullRight, "steering +127")
        assertBytes(hexBytes("00 80 00 00  01 00 00 00  ${zeros(24)}"), fullLeft, "steering -128")
        assertBytes(hexBytes("00 ff 00 00  02 00 00 00  ${zeros(24)}"), slightLeft, "steering -1")
    }

    @Test
    fun `brake light is 0x01 when on and 0x00 when off`() {
        val encoder = TeleopEncoder()

        val on = encoder.encode(DriveCommand(0, 0, brakeLight = true))
        val off = encoder.encode(DriveCommand(0, 0, brakeLight = false))

        assertBytes(hexBytes("00 00 01 00  00 00 00 00  ${zeros(24)}"), on, "brake on")
        assertBytes(hexBytes("00 00 00 00  01 00 00 00  ${zeros(24)}"), off, "brake off")
    }

    @Test
    fun `a combined command lays out all fields and stores the counter little-endian`() {
        val encoder = TeleopEncoder(initialCounter = 0x0A0B0C0DL)

        val packet = encoder.encode(DriveCommand(throttle = -128, steering = 127, brakeLight = true))

        assertBytes(
            hexBytes("80 7f 01 00  0d 0c 0b 0a  ${zeros(24)}"),
            packet,
            "throttle -128, steering +127, brake on, counter 0x0A0B0C0D",
        )
    }

    @Test
    fun `every packet is exactly 32 octets`() {
        val encoder = TeleopEncoder()

        repeat(5) { assertEquals(32, encoder.encode(DriveCommand(it, -it, it % 2 == 0)).size) }
        assertEquals(32, encoder.neutral().size)
        assertEquals(32, TeleopEncoder.PACKET_SIZE)
    }

    @Test
    fun `the counter increments by one per encoded packet`() {
        val encoder = TeleopEncoder()
        val command = DriveCommand(10, 20, false)

        val first = encoder.encode(command)
        val second = encoder.encode(command)
        val third = encoder.encode(command)

        assertBytes(hexBytes("00 00 00 00"), first.copyOfRange(4, 8), "counter of the first packet")
        assertBytes(hexBytes("01 00 00 00"), second.copyOfRange(4, 8), "counter of the second packet")
        assertBytes(hexBytes("02 00 00 00"), third.copyOfRange(4, 8), "counter of the third packet")
    }

    @Test
    fun `the counter carries across octets`() {
        val encoder = TeleopEncoder(initialCounter = 0x000000FFL)

        val a = encoder.encode(DriveCommand.NEUTRAL)
        val b = encoder.encode(DriveCommand.NEUTRAL)

        assertBytes(hexBytes("ff 00 00 00"), a.copyOfRange(4, 8), "0x000000ff")
        assertBytes(hexBytes("00 01 00 00"), b.copyOfRange(4, 8), "0x00000100")
    }

    @Test
    fun `the counter wraps from 2^32 minus one back to zero`() {
        val encoder = TeleopEncoder(initialCounter = 0xFFFFFFFFL)

        val last = encoder.encode(DriveCommand.NEUTRAL)
        val wrapped = encoder.encode(DriveCommand.NEUTRAL)
        val next = encoder.encode(DriveCommand.NEUTRAL)

        assertBytes(hexBytes("ff ff ff ff"), last.copyOfRange(4, 8), "0xffffffff")
        assertBytes(hexBytes("00 00 00 00"), wrapped.copyOfRange(4, 8), "wrapped to 0")
        assertBytes(hexBytes("01 00 00 00"), next.copyOfRange(4, 8), "then 1")
    }

    @Test
    fun `neutral is a zero drive command that still consumes a counter value`() {
        val encoder = TeleopEncoder(initialCounter = 7)

        val neutral = encoder.neutral()
        val following = encoder.encode(DriveCommand(1, 1, true))

        assertBytes(hexBytes("00 00 00 00  07 00 00 00  ${zeros(24)}"), neutral, "neutral packet")
        assertBytes(hexBytes("01 01 01 00  08 00 00 00  ${zeros(24)}"), following, "counter advanced past neutral")
        assertEquals(DriveCommand(0, 0, false), DriveCommand.NEUTRAL)
    }

    @Test
    fun `each call returns a fresh array`() {
        val encoder = TeleopEncoder()

        val a = encoder.neutral()
        val b = encoder.neutral()

        assertNotSame(a, b)
    }

    @Test
    fun `a throttle outside -128 to 127 is rejected`() {
        assertFailsWith<IllegalArgumentException> { DriveCommand(128, 0, false) }
        assertFailsWith<IllegalArgumentException> { DriveCommand(-129, 0, false) }
    }

    @Test
    fun `a steering outside -128 to 127 is rejected`() {
        assertFailsWith<IllegalArgumentException> { DriveCommand(0, 128, false) }
        assertFailsWith<IllegalArgumentException> { DriveCommand(0, -129, false) }
    }

    @Test
    fun `an initial counter outside the u32 range is rejected`() {
        assertFailsWith<IllegalArgumentException> { TeleopEncoder(initialCounter = -1) }
        assertFailsWith<IllegalArgumentException> { TeleopEncoder(initialCounter = 0x1_0000_0000L) }
    }
}
