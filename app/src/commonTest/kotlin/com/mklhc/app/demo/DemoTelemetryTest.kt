package com.mklhc.app.demo

import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Quaternion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [DemoTelemetry]: the pure generators behind the demo session. The orientation must be a unit quaternion that
 * moves slowly and stays within a believable tilt, using the same convention the cockpit's artificial horizon reads
 * (so the horizon visibly swings instead of flipping), and the drive command must show up in the acceleration.
 */
class DemoTelemetryTest {

    /** The attitude the cockpit HUD derives from a quaternion (see the cockpit's IMU mapping): roll and pitch in degrees. */
    private fun attitudeOf(q: Quaternion): Pair<Double, Double> {
        val downX = 2.0 * (q.x * q.z - q.y * q.w)
        val downY = 2.0 * (q.y * q.z + q.x * q.w)
        val downZ = 1.0 - 2.0 * (q.x * q.x + q.y * q.y)
        val roll = atan2(downX, downZ) * 180.0 / PI
        val pitch = atan2(downY, hypot(downX, downZ)) * 180.0 / PI
        return roll to pitch
    }

    @Test
    fun theOrientationIsAUnitQuaternionAtEveryInstant() {
        for (tenths in 0..600) {
            val q = DemoTelemetry.imu(timeSeconds = tenths / 10.0, sequence = tenths).quaternion
            val norm = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
            assertTrue(abs(norm - 1.0) < 1e-9, "norm=$norm at t=${tenths / 10.0}")
        }
    }

    @Test
    fun theHorizonSwingsWithinAGentleRangeAndReallyMoves() {
        var minRoll = Double.MAX_VALUE
        var maxRoll = -Double.MAX_VALUE
        var minPitch = Double.MAX_VALUE
        var maxPitch = -Double.MAX_VALUE
        for (tenths in 0..600) {
            val (roll, pitch) = attitudeOf(DemoTelemetry.imu(tenths / 10.0, tenths).quaternion)
            minRoll = minOf(minRoll, roll)
            maxRoll = maxOf(maxRoll, roll)
            minPitch = minOf(minPitch, pitch)
            maxPitch = maxOf(maxPitch, pitch)
        }
        // Visible swing, but never anywhere near a flip.
        assertTrue(maxRoll - minRoll > 20.0, "roll swing was ${maxRoll - minRoll}")
        assertTrue(maxPitch - minPitch > 6.0, "pitch swing was ${maxPitch - minPitch}")
        assertTrue(maxRoll <= DemoTelemetry.ROLL_AMPLITUDE_DEGREES + 0.5 && minRoll >= -DemoTelemetry.ROLL_AMPLITUDE_DEGREES - 0.5)
        assertTrue(maxPitch <= DemoTelemetry.PITCH_AMPLITUDE_DEGREES + 0.5 && minPitch >= -DemoTelemetry.PITCH_AMPLITUDE_DEGREES - 0.5)
    }

    @Test
    fun theOrientationChangesSlowlyBetweenConsecutiveFrames() {
        val a = attitudeOf(DemoTelemetry.imu(timeSeconds = 1.00, sequence = 0).quaternion)
        val b = attitudeOf(DemoTelemetry.imu(timeSeconds = 1.033, sequence = 1).quaternion)

        assertNotEquals(a, b)
        assertTrue(abs(a.first - b.first) < 2.0, "roll jumped ${abs(a.first - b.first)} degrees in one frame")
        assertTrue(abs(a.second - b.second) < 2.0, "pitch jumped ${abs(a.second - b.second)} degrees in one frame")
    }

    @Test
    fun theRawQuaternionMirrorsTheNormalisedOne() {
        val packet = DemoTelemetry.imu(timeSeconds = 0.7, sequence = 3)
        val scale = (1 shl 30).toDouble()

        assertEquals(packet.quaternion.x, packet.quaternionRaw.i / scale, 1e-6)
        assertEquals(packet.quaternion.y, packet.quaternionRaw.j / scale, 1e-6)
        assertEquals(packet.quaternion.z, packet.quaternionRaw.k / scale, 1e-6)
        assertEquals(packet.quaternion.w, packet.quaternionRaw.r / scale, 1e-6)
    }

    @Test
    fun aPacketHasOneSampleWithGravityOnZAndTheSmallAccelerationOnXAndY() {
        for (tenths in 0..200) {
            val packet = DemoTelemetry.imu(tenths / 10.0, tenths)
            val accel = packet.samples.single().accel
            assertEquals(1, packet.flags.declaredSampleCount)
            assertEquals(false, packet.truncated)
            assertEquals(DemoTelemetry.GRAVITY, accel.z, 1e-9)
            assertTrue(abs(accel.x) < 0.5 * DemoTelemetry.GRAVITY, "lateral g out of range: ${accel.x}")
            assertTrue(abs(accel.y) < 0.5 * DemoTelemetry.GRAVITY, "longitudinal g out of range: ${accel.y}")
        }
    }

    @Test
    fun theAccelerationOscillatesWhileTheKartIsIdle() {
        val samples = (0..200).map { DemoTelemetry.imu(it / 10.0, it).samples.single().accel }

        assertTrue(samples.any { it.x > 0.05 } && samples.any { it.x < -0.05 }, "lateral acceleration must swing both ways")
        assertTrue(samples.any { it.y > 0.05 } && samples.any { it.y < -0.05 }, "longitudinal acceleration must swing both ways")
    }

    @Test
    fun theDriveCommandBiasesTheAcceleration() {
        val idle = DemoTelemetry.imu(timeSeconds = 2.0, sequence = 0, drive = DriveCommand.NEUTRAL).samples.single().accel
        val forward = DemoTelemetry.imu(timeSeconds = 2.0, sequence = 0, drive = DriveCommand(127, 0, false)).samples.single().accel
        val reverse = DemoTelemetry.imu(timeSeconds = 2.0, sequence = 0, drive = DriveCommand(-128, 0, false)).samples.single().accel
        val right = DemoTelemetry.imu(timeSeconds = 2.0, sequence = 0, drive = DriveCommand(0, 127, false)).samples.single().accel
        val left = DemoTelemetry.imu(timeSeconds = 2.0, sequence = 0, drive = DriveCommand(0, -128, false)).samples.single().accel

        // The IMU's +Y points to the rear, so accelerating forward reads negative.
        assertTrue(forward.y < idle.y && reverse.y > idle.y)
        // +X points to the driver's right.
        assertTrue(right.x > idle.x && left.x < idle.x)
    }

    @Test
    fun packetsAreNumberedAndTimestampedInOrder() {
        val a = DemoTelemetry.imu(timeSeconds = 1.0, sequence = 10)
        val b = DemoTelemetry.imu(timeSeconds = 1.5, sequence = 11)

        assertEquals(10, a.totalSampleCount)
        assertEquals(11, b.totalSampleCount)
        assertEquals(1_000_000L, a.timerMicros)
        assertEquals(1_500_000L, b.timerMicros)
    }

    @Test
    fun theBatteryShowsAFewBarsAndNoCable() {
        val battery = DemoTelemetry.battery()

        assertEquals(3, battery.batteryBars)
        assertEquals(false, battery.cableConnected)
    }

    @Test
    fun aVideoAccessUnitStartsWithAnAnnexBStartCodeAndCarriesItsSequenceNumber() {
        val first = DemoTelemetry.videoAccessUnit(sequence = 1)
        val second = DemoTelemetry.videoAccessUnit(sequence = 2)

        assertContentEquals(byteArrayOf(0, 0, 0, 1), first.copyOfRange(0, 4))
        assertNotEquals(first.toList(), second.toList())
        // Never an IDR picture: this is placeholder data, so a real decoder must keep skipping it.
        assertNotEquals(IDR_NAL_TYPE, first[4].toInt() and 0x1F)
    }

    @Test
    fun theSampleKartIsLabelledAsADemo() {
        assertTrue(DemoTelemetry.SAMPLE_KART_INFO.serial.startsWith("DEMO"))
        assertTrue(DemoTelemetry.PAIRING_SSID.encodeToByteArray().size <= 32, "the SSID must fit the payload field")
    }

    private companion object {
        const val IDR_NAL_TYPE = 5
    }
}
