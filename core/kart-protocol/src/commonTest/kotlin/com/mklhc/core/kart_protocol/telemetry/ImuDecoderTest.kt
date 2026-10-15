package com.mklhc.core.kart_protocol.telemetry

import co.touchlab.kermit.Severity
import com.mklhc.core.kart_protocol.CapturingLogWriter
import com.mklhc.core.kart_protocol.capturingLogger
import com.mklhc.core.kart_protocol.hexBytes
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Known-answer tests for the type-2 IMU telemetry path of [TelemetryDecoder].
 *
 * There is no reference implementation for this path (the OpenKart reference leaves it as a TODO), so
 * every vector is hand-built from the IMU layout of the community protocol wiki (Fuji kart protocol).
 * Offsets are within the payload after the 1-octet type, and multi-octet values are assumed
 * little-endian; both are spike-confirmation items. Layout: 0x00 u16 total sample count, 0x02 flags
 * `IGA??NNN`, 0x04 u32 microsecond timer, 0x08 quaternion 4 x s32 (qi, qj, qk, qr; unit 2^-30), 0x18 /
 * 0x24 integrator A / B positions 3 x s32, 0x30 / 0x3C integrator A / B velocities 3 x s32, 0x48
 * onward NNN samples of accel 3 x s16 then gyro 3 x s16.
 *
 * Scaling used by the expectations: accel = raw * 9.81 / 4096 m/s^2 when A = 1, raw * 15.99 * 9.81 / 4096
 * when A = 0, with 9.81 then added to the Z axis; gyro = raw * 0.004375 deg/s when G = 1, raw * 0.1 when
 * G = 0. Worked numbers are in the comments next to each vector.
 */
class ImuDecoderTest {

    private val writer = CapturingLogWriter()
    private val decoder = TelemetryDecoder(capturingLogger(writer, "ImuDecoderTest"))

    private companion object {
        const val TOLERANCE = 1e-9
        const val HEADER_SIZE = 0x48
        const val SAMPLE_SIZE = 12
    }

    // -- test-side packet builder (independent of the decoder; checked against a literal vector below) --

    private class LeWriter {
        val out = mutableListOf<Byte>()
        fun u8(v: Int) = out.add(v.toByte())
        fun u16(v: Int) { u8(v and 0xFF); u8((v shr 8) and 0xFF) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v shr 16) and 0xFFFF).toInt()) }
        fun s16(v: Int) = u16(v and 0xFFFF)
        fun s32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        fun vec3(v: IntArray) { v.forEach { s32(it) } }
    }

    private fun sample(ax: Int, ay: Int, az: Int, gx: Int, gy: Int, gz: Int) = intArrayOf(ax, ay, az, gx, gy, gz)

    /** A full type-2 datagram: type octet, header, then each of [samples] (accel xyz, gyro xyz as s16). */
    private fun imuPacket(
        totalCount: Int = 0,
        flags: Int = 0x01,
        timer: Long = 0,
        quaternion: IntArray = intArrayOf(0, 0, 0, 0x40000000),
        positionA: IntArray = intArrayOf(0, 0, 0),
        positionB: IntArray = intArrayOf(0, 0, 0),
        velocityA: IntArray = intArrayOf(0, 0, 0),
        velocityB: IntArray = intArrayOf(0, 0, 0),
        samples: List<IntArray> = listOf(sample(0, 0, 0, 0, 0, 0)),
    ): ByteArray {
        val w = LeWriter()
        w.u8(0x02)
        w.u16(totalCount)
        w.u8(flags)
        w.u8(0x00)
        w.u32(timer)
        w.vec3(quaternion.copyOf(3)); w.s32(quaternion[3])
        w.vec3(positionA); w.vec3(positionB)
        w.vec3(velocityA); w.vec3(velocityB)
        for (s in samples) s.forEach { w.s16(it) }
        return w.out.toByteArray()
    }

    private fun decodeImu(packet: ByteArray): Telemetry.Imu {
        val telemetry = decoder.decode(packet)
        assertIs<Telemetry.Imu>(telemetry)
        return telemetry
    }

    private fun assertVec(expected: Vector3, actual: Vector3, message: String) {
        assertEquals(expected.x, actual.x, TOLERANCE, "$message x")
        assertEquals(expected.y, actual.y, TOLERANCE, "$message y")
        assertEquals(expected.z, actual.z, TOLERANCE, "$message z")
    }

    // -- vector 1: a literal hex packet, one sample, low-precision accel and gyro --

    /**
     * Vector 1, written out octet by octet (after the type octet 02):
     *
     * ```
     * 02 01                  total sample count 0x0102 = 258
     * 19                     flags 0b0001_1001: I=0 G=0 A=0, ?? = 0b11, NNN = 1
     * 00                     unused octet 0x03
     * 40 42 0f 00            timer 0x000F4240 = 1_000_000 us
     * 00 00 00 20            qi = 0x20000000 -> +0.5
     * 00 00 00 e0            qj = 0xE0000000 = -0x20000000 -> -0.5
     * 00 00 00 20            qk -> +0.5
     * 00 00 00 20            qr -> +0.5     (unit norm already: 4 x 0.25 = 1)
     * 01 00 00 00 ff ff ff ff 00 01 00 00    position A  ( 1, -1, 256)
     * 02 00 00 00 fe ff ff ff 00 02 00 00    position B  ( 2, -2, 512)
     * 03 00 00 00 fd ff ff ff 04 00 00 00    velocity A  ( 3, -3,   4)
     * 05 00 00 00 fb ff ff ff 06 00 00 00    velocity B  ( 5, -5,   6)
     * 00 10 00 f0 00 00      accel raw (4096, -4096, 0)
     * e8 03 30 f8 1e 00      gyro raw (1000, -2000, 30)
     * ```
     *
     * Low-precision accel scale is 15.99 * 9.81 / 4096, so 4096 -> 156.8619 and 0 on Z -> 0 + 9.81.
     * Low-precision gyro scale is 0.1, so (1000, -2000, 30) -> (100, -200, 3).
     */
    private val vector1 = hexBytes(
        """
        02
        02 01
        19
        00
        40 42 0f 00
        00 00 00 20  00 00 00 e0  00 00 00 20  00 00 00 20
        01 00 00 00  ff ff ff ff  00 01 00 00
        02 00 00 00  fe ff ff ff  00 02 00 00
        03 00 00 00  fd ff ff ff  04 00 00 00
        05 00 00 00  fb ff ff ff  06 00 00 00
        00 10  00 f0  00 00
        e8 03  30 f8  1e 00
        """,
    )

    @Test
    fun `vector 1 header fields decode`() {
        val imu = decodeImu(vector1)

        assertEquals(1 + HEADER_SIZE + SAMPLE_SIZE, vector1.size)
        assertEquals(258, imu.totalSampleCount)
        assertEquals(0x19, imu.flags.raw)
        assertEquals(1_000_000L, imu.timerMicros)
        assertFalse(imu.truncated)
    }

    @Test
    fun `vector 1 flag bits decode IGA unknown and NNN`() {
        val flags = decodeImu(vector1).flags

        assertFalse(flags.highPrecisionPosition, "I")
        assertFalse(flags.highPrecisionGyro, "G")
        assertFalse(flags.highPrecisionAccel, "A")
        assertEquals(0b11, flags.unknownBits, "the two unknown bits are exposed, not folded into NNN")
        assertEquals(1, flags.declaredSampleCount, "NNN")
    }

    @Test
    fun `vector 1 quaternion is read in qi qj qk qr order and is unit length`() {
        val imu = decodeImu(vector1)

        assertEquals(QuaternionRaw(i = 0x20000000, j = -0x20000000, k = 0x20000000, r = 0x20000000), imu.quaternionRaw)
        assertEquals(0.5, imu.quaternion.x, TOLERANCE, "x = qi")
        assertEquals(-0.5, imu.quaternion.y, TOLERANCE, "y = qj, negative")
        assertEquals(0.5, imu.quaternion.z, TOLERANCE, "z = qk")
        assertEquals(0.5, imu.quaternion.w, TOLERANCE, "w = qr")
    }

    @Test
    fun `vector 1 integrator positions and velocities are signed s32 triples`() {
        val imu = decodeImu(vector1)

        assertEquals(Vector3Int(1, -1, 256), imu.positionA)
        assertEquals(Vector3Int(2, -2, 512), imu.positionB)
        assertEquals(Vector3Int(3, -3, 4), imu.velocityA)
        assertEquals(Vector3Int(5, -5, 6), imu.velocityB)
    }

    @Test
    fun `vector 1 sample is scaled with the low-precision constants and gravity on Z`() {
        val imu = decodeImu(vector1)

        assertEquals(1, imu.samples.size)
        val s = imu.samples.single()
        assertEquals(Vector3Int(4096, -4096, 0), s.accelRaw)
        assertEquals(Vector3Int(1000, -2000, 30), s.gyroRaw)
        assertVec(Vector3(156.8619, -156.8619, 9.81), s.accel, "accel m/s^2")
        assertVec(Vector3(100.0, -200.0, 3.0), s.gyro, "gyro deg/s")
    }

    @Test
    fun `the test-side builder reproduces the literal vector 1 octet for octet`() {
        val built = imuPacket(
            totalCount = 258,
            flags = 0x19,
            timer = 1_000_000,
            quaternion = intArrayOf(0x20000000, -0x20000000, 0x20000000, 0x20000000),
            positionA = intArrayOf(1, -1, 256),
            positionB = intArrayOf(2, -2, 512),
            velocityA = intArrayOf(3, -3, 4),
            velocityB = intArrayOf(5, -5, 6),
            samples = listOf(sample(4096, -4096, 0, 1000, -2000, 30)),
        )

        assertEquals(vector1.toHexString(), built.toHexString())
    }

    // -- vector 2: three samples, high-precision accel, gyro and position --

    /**
     * Vector 2: total count 0xFFFF (wrapped counter), flags 0xE3 = 0b1110_0011 (I=1 G=1 A=1, ?? = 0,
     * NNN = 3), timer 0xFFFFFFFF (an unsigned 4_294_967_295, not -1), and the non-unit quaternion
     * (qi, qj, qk, qr) = (2^30, 0, 0, 2^30), i.e. (1, 0, 0, 1) with norm sqrt(2), which must normalise to
     * x = w = 1/sqrt(2) = 0.70710678.
     *
     * High-precision accel scale is 9.81 / 4096 and gyro 0.004375:
     *  - sample 1: accel (4096, 2048, -4096) -> (9.81, 4.905, -9.81 + 9.81 = 0.0); gyro (1000, -2000, 0) -> (4.375, -8.75, 0).
     *  - sample 2: all zero -> accel (0, 0, 9.81) (the gravity note: a sensor at rest reads 9.81 on Z), gyro 0.
     *  - sample 3: the s16 extremes accel (-32768, 32767, -32768) -> (-78.48, 78.4776..., -78.48 + 9.81 = -68.67);
     *    gyro (-32768, 32767, 1) -> (-143.36, 143.355625, 0.004375).
     */
    private val vector2 = imuPacket(
        totalCount = 0xFFFF,
        flags = 0xE3,
        timer = 0xFFFFFFFFL,
        quaternion = intArrayOf(0x40000000, 0, 0, 0x40000000),
        positionA = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE, 0),
        positionB = intArrayOf(0, 0, -1),
        velocityA = intArrayOf(-2, -3, -4),
        velocityB = intArrayOf(0x01020304, 0, 0),
        samples = listOf(
            sample(4096, 2048, -4096, 1000, -2000, 0),
            sample(0, 0, 0, 0, 0, 0),
            sample(-32768, 32767, -32768, -32768, 32767, 1),
        ),
    )

    @Test
    fun `vector 2 header decodes with unsigned counter and timer`() {
        val imu = decodeImu(vector2)

        assertEquals(65535, imu.totalSampleCount)
        assertEquals(4_294_967_295L, imu.timerMicros)
        assertEquals(0xE3, imu.flags.raw)
        assertTrue(imu.flags.highPrecisionPosition, "I")
        assertTrue(imu.flags.highPrecisionGyro, "G")
        assertTrue(imu.flags.highPrecisionAccel, "A")
        assertEquals(0, imu.flags.unknownBits)
        assertEquals(3, imu.flags.declaredSampleCount)
        assertEquals(3, imu.samples.size)
        assertFalse(imu.truncated)
    }

    @Test
    fun `vector 2 quaternion is normalised before use`() {
        val imu = decodeImu(vector2)
        val inverseRoot2 = sqrt(0.5)

        assertEquals(QuaternionRaw(0x40000000, 0, 0, 0x40000000), imu.quaternionRaw, "the raw values are kept")
        assertEquals(inverseRoot2, imu.quaternion.x, TOLERANCE)
        assertEquals(0.0, imu.quaternion.y, TOLERANCE)
        assertEquals(0.0, imu.quaternion.z, TOLERANCE)
        assertEquals(inverseRoot2, imu.quaternion.w, TOLERANCE)
        val q = imu.quaternion
        assertEquals(1.0, q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w, TOLERANCE, "unit length")
    }

    @Test
    fun `vector 2 integrators keep the full signed s32 range`() {
        val imu = decodeImu(vector2)

        assertEquals(Vector3Int(Int.MIN_VALUE, Int.MAX_VALUE, 0), imu.positionA)
        assertEquals(Vector3Int(0, 0, -1), imu.positionB)
        assertEquals(Vector3Int(-2, -3, -4), imu.velocityA)
        assertEquals(Vector3Int(0x01020304, 0, 0), imu.velocityB)
    }

    @Test
    fun `vector 2 samples are scaled with the high-precision constants`() {
        val samples = decodeImu(vector2).samples

        assertVec(Vector3(9.81, 4.905, 0.0), samples[0].accel, "sample 1 accel")
        assertVec(Vector3(4.375, -8.75, 0.0), samples[0].gyro, "sample 1 gyro")
        assertVec(Vector3(0.0, 0.0, 9.81), samples[1].accel, "sample 2 accel at rest")
        assertVec(Vector3(0.0, 0.0, 0.0), samples[1].gyro, "sample 2 gyro")
        assertVec(Vector3(-78.48, 32767 * 9.81 / 4096, -68.67), samples[2].accel, "sample 3 accel")
        assertVec(Vector3(-143.36, 143.355625, 0.004375), samples[2].gyro, "sample 3 gyro")
    }

    @Test
    fun `vector 2 samples keep their raw s16 values in order`() {
        val samples = decodeImu(vector2).samples

        assertEquals(Vector3Int(4096, 2048, -4096), samples[0].accelRaw)
        assertEquals(Vector3Int(1000, -2000, 0), samples[0].gyroRaw)
        assertEquals(Vector3Int(0, 0, 0), samples[1].accelRaw)
        assertEquals(Vector3Int(-32768, 32767, -32768), samples[2].accelRaw)
        assertEquals(Vector3Int(-32768, 32767, 1), samples[2].gyroRaw)
    }

    // -- the A and G flags select accel and gyro precision independently --

    @Test
    fun `accel high precision with gyro low precision`() {
        // flags 0x21 = A only, NNN = 1. accel raw 4096 -> 9.81, Z 0 -> 9.81; gyro raw 1000 -> 100.0 (G = 0).
        val imu = decodeImu(imuPacket(flags = 0x21, samples = listOf(sample(4096, 0, 0, 1000, 0, 0))))

        assertVec(Vector3(9.81, 0.0, 9.81), imu.samples.single().accel, "accel")
        assertVec(Vector3(100.0, 0.0, 0.0), imu.samples.single().gyro, "gyro")
    }

    @Test
    fun `accel low precision with gyro high precision`() {
        // flags 0x41 = G only, NNN = 1. accel raw 4096 -> 156.8619 (A = 0); gyro raw 1000 -> 4.375.
        val imu = decodeImu(imuPacket(flags = 0x41, samples = listOf(sample(4096, 0, 0, 1000, 0, 0))))

        assertVec(Vector3(156.8619, 0.0, 9.81), imu.samples.single().accel, "accel")
        assertVec(Vector3(4.375, 0.0, 0.0), imu.samples.single().gyro, "gyro")
    }

    @Test
    fun `the position precision flag does not change the unscaled integrator values`() {
        // flags 0x81 = I only. The integrators are exposed raw because the wiki gives no unit for them.
        val imu = decodeImu(imuPacket(flags = 0x81, positionA = intArrayOf(7, 8, 9)))

        assertTrue(imu.flags.highPrecisionPosition)
        assertFalse(imu.flags.highPrecisionAccel)
        assertEquals(Vector3Int(7, 8, 9), imu.positionA)
    }

    // -- the NNN sample count --

    @Test
    fun `NNN of 7 decodes seven samples in order`() {
        val seven = List(7) { sample(it, 0, 0, 0, 0, it * 10) }

        val imu = decodeImu(imuPacket(flags = 0x07, samples = seven))

        assertEquals(7, imu.flags.declaredSampleCount)
        assertEquals(7, imu.samples.size)
        assertEquals(List(7) { it }, imu.samples.map { it.accelRaw.x })
        assertEquals(List(7) { it * 10 }, imu.samples.map { it.gyroRaw.z })
        assertFalse(imu.truncated)
    }

    @Test
    fun `NNN of 0 decodes the header with no samples`() {
        val imu = decodeImu(imuPacket(flags = 0x00, samples = emptyList()))

        assertEquals(0, imu.flags.declaredSampleCount)
        assertTrue(imu.samples.isEmpty())
        assertFalse(imu.truncated)
    }

    @Test
    fun `only NNN samples are decoded when more octets follow`() {
        val extra = List(3) { sample(it + 1, 0, 0, 0, 0, 0) }

        val imu = decodeImu(imuPacket(flags = 0x01, samples = extra))

        assertEquals(1, imu.samples.size)
        assertEquals(1, imu.samples.single().accelRaw.x)
        assertFalse(imu.truncated, "trailing octets are not a truncation")
    }

    // -- tolerance for short packets --

    @Test
    fun `a packet cut in the middle of the samples decodes the complete ones and is flagged`() {
        // Declared NNN = 3 (flags 0x03), two complete samples present, then 5 stray octets of a third.
        val full = imuPacket(flags = 0x03, samples = listOf(sample(1, 2, 3, 4, 5, 6), sample(7, 8, 9, 10, 11, 12)))
        val cut = full + ByteArray(5) { 0x55 }

        val imu = decodeImu(cut)

        assertEquals(3, imu.flags.declaredSampleCount)
        assertEquals(2, imu.samples.size, "only complete samples are returned")
        assertEquals(Vector3Int(7, 8, 9), imu.samples[1].accelRaw)
        assertTrue(imu.truncated)
        assertTrue(writer.logged(Severity.Warn), "the truncation is logged")
    }

    @Test
    fun `a packet that ends right after the header has no samples and is flagged when NNN is above 0`() {
        val headerOnly = imuPacket(flags = 0x02, samples = emptyList())

        val imu = decodeImu(headerOnly)

        assertEquals(1 + HEADER_SIZE, headerOnly.size)
        assertTrue(imu.samples.isEmpty())
        assertTrue(imu.truncated)
    }

    @Test
    fun `a payload shorter than the 0x48 header is Unknown and logged`() {
        val full = imuPacket(samples = emptyList())
        val short = full.copyOf(full.size - 1)

        val telemetry = decoder.decode(short)

        assertEquals(Telemetry.Unknown(type = 2, raw = short.copyOfRange(1, short.size)), telemetry)
        assertTrue(writer.logged(Severity.Warn))
    }

    @Test
    fun `a type 2 datagram with no payload is Unknown`() {
        assertEquals(Telemetry.Unknown(type = 2, raw = ByteArray(0)), decoder.decode(byteArrayOf(0x02)))
    }

    @Test
    fun `an all-zero quaternion cannot be normalised and becomes the identity rotation`() {
        val imu = decodeImu(imuPacket(quaternion = intArrayOf(0, 0, 0, 0)))

        assertEquals(Quaternion(0.0, 0.0, 0.0, 1.0), imu.quaternion)
        assertEquals(QuaternionRaw(0, 0, 0, 0), imu.quaternionRaw)
        assertTrue(writer.logged(Severity.Warn))
    }

    @Test
    fun `a well formed packet logs no warning`() {
        decodeImu(vector1)

        assertFalse(writer.logged(Severity.Warn))
        assertFalse(writer.logged(Severity.Error))
    }

    @Test
    fun `the datagram is not modified by decoding`() {
        val before = vector1.copyOf()

        decoder.decode(vector1)

        assertEquals(before.toHexString(), vector1.toHexString())
    }
}
