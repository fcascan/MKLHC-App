package com.mklhc.core.kart_protocol.telemetry

import com.mklhc.core.kart_protocol.CapturingLogWriter
import com.mklhc.core.kart_protocol.capturingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [FrameStatusDecoder] and the type-3 path of [TelemetryDecoder], against synthetic records built to the layout measured
 * from a telemetry dump (never real dump bytes). Octets the decoder must not read are 0xaa, so a wrong offset shows up.
 */
class FrameStatusDecoderTest {

    private val writer = CapturingLogWriter()
    private val decoder = TelemetryDecoder(capturingLogger(writer, "FrameStatusDecoderTest"))

    @Test
    fun `the current and previous camera records and the drive echo are read from their offsets`() {
        val echo = DriveCommandEcho(timerMicros = CURRENT.timerMicros, throttle = 63, steering = -127, unknownValue = 0.0926f)

        val status = FrameStatusDecoder.decode(payload(current = CURRENT, previous = PREVIOUS, echo = echo))

        assertEquals(CURRENT, status?.current)
        assertEquals(PREVIOUS, status?.previous)
        assertEquals(echo, status?.driveEcho)
    }

    @Test
    fun `the timers are unsigned and the echoed axes are signed octets at both ends`() {
        val high = CURRENT.copy(timerMicros = 0xF000_0001L)
        val echo = DriveCommandEcho(timerMicros = 0xFFFF_FFFFL, throttle = -128, steering = 127, unknownValue = -0.037f)

        val status = FrameStatusDecoder.decode(payload(current = high, previous = PREVIOUS, echo = echo))

        assertEquals(0xF000_0001L, status?.current?.timerMicros)
        assertEquals(echo, status?.driveEcho)
    }

    @Test
    fun `a payload shorter than 87 octets is not decoded`() {
        val short = payload(CURRENT, PREVIOUS, ECHO).copyOfRange(0, FrameStatusDecoder.PAYLOAD_SIZE - 1)

        assertEquals(null, FrameStatusDecoder.decode(short))
    }

    @Test
    fun `a longer payload is decoded and its extra octets are ignored`() {
        val longer = payload(CURRENT, PREVIOUS, ECHO) + byteArrayOf(1, 2, 3)

        val status = FrameStatusDecoder.decode(longer)

        assertEquals(Telemetry.FrameStatus(CURRENT, PREVIOUS, ECHO), status)
    }

    @Test
    fun `a type 3 datagram decodes to a frame status and nothing is logged, since it arrives 30 times a second`() {
        val datagram = byteArrayOf(TelemetryDecoder.TYPE_FRAME_STATUS.toByte()) + payload(CURRENT, PREVIOUS, ECHO)

        val telemetry = decoder.decode(datagram)

        assertEquals(Telemetry.FrameStatus(CURRENT, PREVIOUS, ECHO), telemetry)
        assertTrue(writer.entries.isEmpty(), "nothing logged per type 3 datagram: ${writer.entries.map { it.message }}")
    }

    @Test
    fun `a type 3 datagram too short to decode is Unknown with its payload kept, and is not logged either`() {
        val datagram = byteArrayOf(TelemetryDecoder.TYPE_FRAME_STATUS.toByte(), 0x11, 0x22, 0x33)

        val telemetry = decoder.decode(datagram)

        assertIs<Telemetry.Unknown>(telemetry)
        assertEquals(Telemetry.Unknown(type = 3, raw = byteArrayOf(0x11, 0x22, 0x33)), telemetry)
        assertTrue(writer.entries.isEmpty(), "the session logs the first undecodable one: ${writer.entries.map { it.message }}")
    }

    /** An 87-octet type-3 payload: filler, then the two camera records and the echo at the measured offsets. */
    private fun payload(current: CameraFrameRecord, previous: CameraFrameRecord, echo: DriveCommandEcho): ByteArray {
        val out = ByteArray(FrameStatusDecoder.PAYLOAD_SIZE) { 0xAA.toByte() }
        out.putCameraRecord(7, current)
        out.putCameraRecord(39, previous)
        out.putU32(71, echo.timerMicros)
        out[79] = echo.throttle.toByte()
        out[80] = echo.steering.toByte()
        out.putU32(83, echo.unknownValue.toRawBits().toLong())
        return out
    }

    private fun ByteArray.putCameraRecord(offset: Int, record: CameraFrameRecord) {
        putU32(offset, record.timerMicros)
        putU32(offset + 8, record.exposure.toRawBits().toLong())
        putU32(offset + 12, record.gain.toRawBits().toLong())
        putU32(offset + 16, record.brightness.toRawBits().toLong())
        putU16(offset + 20, record.redGain)
        putU16(offset + 22, record.greenGain)
        putU16(offset + 24, record.blueGain)
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        for (i in 0 until 4) this[offset + i] = (value shr (8 * i)).toByte()
    }

    private companion object {
        val CURRENT = CameraFrameRecord(
            timerMicros = 139_865_273L,
            exposure = 15_900f,
            gain = 8.5f,
            brightness = 73.5f,
            redGain = 1344,
            greenGain = 1024,
            blueGain = 2680,
        )
        val PREVIOUS = CameraFrameRecord(
            timerMicros = 139_831_941L,
            exposure = 14_587f,
            gain = 15.875f,
            brightness = 6.5f,
            redGain = 1152,
            greenGain = 1024,
            blueGain = 3093,
        )
        val ECHO = DriveCommandEcho(timerMicros = 139_865_273L, throttle = -64, steering = 0, unknownValue = 0.155f)
    }
}
