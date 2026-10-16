package com.mklhc.feature.cockpit.presentation

import com.mklhc.core.kart_protocol.telemetry.Quaternion
import com.mklhc.core.kart_protocol.telemetry.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The pure IMU to HUD mapping on known inputs: the quaternion to pitch and roll, the acceleration to g, and the
 * packet-level mapping that combines them. The axis conventions are the ones documented on the mapper (kart frame
 * +X right, +Y rear, +Z down; identity quaternion is level; pitch positive nose up; roll positive banking right).
 */
class ImuHudMappingTest {

    private val tolerance = 1e-3f

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertEquals(expected, actual, tolerance, what)

    // ---- quaternion to pitch and roll -------------------------------------------------------

    @Test
    fun aLevelKartReadsZeroPitchAndRoll() {
        val attitude = Quaternion.IDENTITY.toAttitude()

        assertNear(0f, attitude.pitchDegrees, "pitch")
        assertNear(0f, attitude.rollDegrees, "roll")
    }

    @Test
    fun theNoseRisingReadsPositivePitch() {
        // +20 degrees about +X (the right axis) lifts the nose (-Y is forward) and drops the tail.
        val attitude = rotationAbout(ImuAxis.X, 20.0).toAttitude()

        assertNear(20f, attitude.pitchDegrees, "pitch")
        assertNear(0f, attitude.rollDegrees, "roll")
    }

    @Test
    fun theNoseFallingReadsNegativePitch() {
        val attitude = rotationAbout(ImuAxis.X, -35.0).toAttitude()

        assertNear(-35f, attitude.pitchDegrees, "pitch")
        assertNear(0f, attitude.rollDegrees, "roll")
    }

    @Test
    fun bankingRightReadsPositiveRoll() {
        // -30 degrees about +Y (the rear axis) is +30 about the forward axis: the right side goes down.
        val attitude = rotationAbout(ImuAxis.Y, -30.0).toAttitude()

        assertNear(30f, attitude.rollDegrees, "roll")
        assertNear(0f, attitude.pitchDegrees, "pitch")
    }

    @Test
    fun bankingLeftReadsNegativeRoll() {
        val attitude = rotationAbout(ImuAxis.Y, 45.0).toAttitude()

        assertNear(-45f, attitude.rollDegrees, "roll")
    }

    @Test
    fun turningAboutTheVerticalChangesNeitherPitchNorRoll() {
        // Heading is not shown on the horizon, so a pure yaw must read level.
        val attitude = rotationAbout(ImuAxis.Z, 70.0).toAttitude()

        assertNear(0f, attitude.pitchDegrees, "pitch")
        assertNear(0f, attitude.rollDegrees, "roll")
    }

    @Test
    fun aSlightlyUnnormalisedQuaternionReadsTheSameAsTheNormalOne() {
        val unit = rotationAbout(ImuAxis.X, 20.0)
        val scaled = Quaternion(unit.x * 1.5, unit.y * 1.5, unit.z * 1.5, unit.w * 1.5)

        assertNear(unit.toAttitude().pitchDegrees, scaled.toAttitude().pitchDegrees, "pitch")
    }

    @Test
    fun aDegenerateQuaternionReadsLevelInsteadOfNaN() {
        val zero = Quaternion(0.0, 0.0, 0.0, 0.0).toAttitude()
        val broken = Quaternion(Double.NaN, 0.0, 0.0, 1.0).toAttitude()

        assertNear(0f, zero.pitchDegrees, "zero pitch")
        assertNear(0f, zero.rollDegrees, "zero roll")
        assertNear(0f, broken.pitchDegrees, "NaN pitch")
        assertNear(0f, broken.rollDegrees, "NaN roll")
    }

    // ---- acceleration to g ------------------------------------------------------------------

    @Test
    fun aKartAtRestOnTheFlatReadsNoLateralOrLongitudinalG() {
        val g = Vector3(0.0, 0.0, REST_Z).toGForce()

        assertNear(0f, g.lateral, "lateral")
        assertNear(0f, g.longitudinal, "longitudinal")
    }

    @Test
    fun accelerationToTheRightIsPositiveLateralG() {
        val g = Vector3(0.5 * STANDARD_GRAVITY, 0.0, REST_Z).toGForce()

        assertNear(0.5f, g.lateral, "lateral")
        assertNear(0f, g.longitudinal, "longitudinal")
    }

    @Test
    fun accelerationForwardIsPositiveLongitudinalGBecauseTheImuYAxisPointsToTheRear() {
        // Forward is -Y in the IMU frame, so speeding up reads a negative Y and must give a positive gY.
        val g = Vector3(0.0, -0.25 * STANDARD_GRAVITY, REST_Z).toGForce()

        assertNear(0.25f, g.longitudinal, "longitudinal")
    }

    @Test
    fun brakingIsNegativeLongitudinalG() {
        val g = Vector3(0.0, 0.8 * STANDARD_GRAVITY, REST_Z).toGForce()

        assertNear(-0.8f, g.longitudinal, "longitudinal")
    }

    @Test
    fun theVerticalAxisDoesNotLeakIntoTheMeter() {
        val g = Vector3(0.0, 0.0, 3 * STANDARD_GRAVITY).toGForce()

        assertNear(0f, g.lateral, "lateral")
        assertNear(0f, g.longitudinal, "longitudinal")
    }

    // ---- the packet-level mapping -----------------------------------------------------------

    @Test
    fun aPacketMapsItsOrientationAndItsNewestSample() {
        val packet = imuPacket(
            quaternion = rotationAbout(ImuAxis.X, 10.0),
            samples = listOf(
                imuSample(9.0, 9.0, REST_Z),
                imuSample(0.25 * STANDARD_GRAVITY, 0.0, REST_Z),
            ),
        )

        val reading = packet.toHudReading()

        assertNear(10f, reading.pitchDegrees, "pitch")
        val g = assertNotNull(reading.gForce)
        assertNear(0.25f, g.lateral, "lateral")
    }

    @Test
    fun aPacketWithoutSamplesHasAttitudeButNoG() {
        val reading = imuPacket(samples = emptyList()).toHudReading()

        assertNull(reading.gForce)
        assertNear(0f, reading.pitchDegrees, "pitch")
    }

    @Test
    fun readingsNeverCarryNegativeZero() {
        val reading = imuPacket(samples = listOf(imuSample(0.0, 0.0, REST_Z))).toHudReading()

        // -0f and 0f are different Floats; a state flow would treat a flip between them as a change.
        assertEquals(0f.toRawBits(), assertNotNull(reading.gForce).longitudinal.toRawBits())
        assertEquals(0f.toRawBits(), reading.rollDegrees.toRawBits())
    }

    @Test
    fun aNaNAccelerationComponentReadsZeroInsteadOfNaN() {
        // NaN is not equal to itself as a Float, so a NaN in the state would make every update look like a change.
        val g = Vector3(Double.NaN, Double.NaN, REST_Z).toGForce()

        assertEquals(0f.toRawBits(), g.lateral.toRawBits())
        assertEquals(0f.toRawBits(), g.longitudinal.toRawBits())

        val reading = imuPacket(samples = listOf(imuSample(Double.NaN, 0.5 * STANDARD_GRAVITY, REST_Z))).toHudReading()
        val fromPacket = assertNotNull(reading.gForce)
        assertEquals(0f.toRawBits(), fromPacket.lateral.toRawBits())
        assertNear(-0.5f, fromPacket.longitudinal, "longitudinal")
    }
}
