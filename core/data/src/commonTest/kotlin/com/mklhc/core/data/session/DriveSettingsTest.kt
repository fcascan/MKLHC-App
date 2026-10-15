package com.mklhc.core.data.session

import com.mklhc.core.domain.KartSettings
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focused unit tests for [applyDriveSettings] (FIX I7), the pure function pulled out of the 30 Hz teleop loop.
 *
 * Business logic: this is where the user's driving preferences shape the raw stick command into the wire
 * command. Being pure, it is tested directly, axis by axis: throttle scaling (forward and reverse), the cubic
 * steering expo, the integer trim offset, the centre and full-lock extremes, and the clamp to the signed-octet
 * range. The expectations are computed from the documented formula, not read back from the implementation.
 */
class DriveSettingsTest {

    private fun settings(maxThrottle: Int = 100, expo: Int = 0, trim: Int = 0): KartSettings =
        KartSettings(maxThrottlePercent = maxThrottle, steeringExpoPercent = expo, steeringTrimPercent = trim)

    @Test
    fun centreStickStaysNeutral() {
        val out = applyDriveSettings(DriveCommand(throttle = 0, steering = 0, brakeLight = false), settings(expo = 50, trim = 0))
        assertEquals(0, out.throttle)
        assertEquals(0, out.steering)
    }

    @Test
    fun fullLockPassesThroughAtAnyExpo() {
        // The cubic expo passes through +/-1, so full lock is still full lock however strong the curve.
        val linear = applyDriveSettings(DriveCommand(throttle = 0, steering = 127, brakeLight = false), settings(expo = 0))
        val curved = applyDriveSettings(DriveCommand(throttle = 0, steering = 127, brakeLight = false), settings(expo = 100))
        assertEquals(127, linear.steering)
        assertEquals(127, curved.steering)
    }

    @Test
    fun midStickExpoSoftensTheCurve() {
        val raw = DriveCommand(throttle = 0, steering = 64, brakeLight = false)
        // Linear: round(64/127 * 127) = 64.
        assertEquals(64, applyDriveSettings(raw, settings(expo = 0)).steering)
        // Expo 50 %: curved = 0.5*n + 0.5*n^3 with n = 64/127 -> round(0.31596 * 127) = 40.
        val softened = applyDriveSettings(raw, settings(expo = 50)).steering
        assertEquals(40, softened, "a 50 % cubic expo softens mid-stick steering")
        assertTrue(softened < 64, "expo must pull mid-stick below the linear value")
    }

    @Test
    fun trimOffsetsTheCentre() {
        // trim 20 %: offset = 20 * 127 / 100 = 25, added to a centred stick.
        val out = applyDriveSettings(DriveCommand(throttle = 0, steering = 0, brakeLight = false), settings(trim = 20))
        assertEquals(25, out.steering)
    }

    @Test
    fun steeringClampsToTheSignedOctetRange() {
        // Full right + positive trim would overflow; clamp to +127.
        val high = applyDriveSettings(DriveCommand(throttle = 0, steering = 127, brakeLight = false), settings(trim = 50))
        assertEquals(127, high.steering)
        // Full left + negative trim would underflow; clamp to -128.
        val low = applyDriveSettings(DriveCommand(throttle = 0, steering = -128, brakeLight = false), settings(trim = -50))
        assertEquals(-128, low.steering)
    }

    @Test
    fun throttleScalesForwardAndReverse() {
        // Forward: 120 at 50 % -> 60 (the clamp keeps it on the wire as a signed octet).
        assertEquals(60, applyDriveSettings(DriveCommand(throttle = 120, steering = 0, brakeLight = false), settings(maxThrottle = 50)).throttle)
        // Reverse scales the same way: -100 at 50 % -> -50.
        assertEquals(-50, applyDriveSettings(DriveCommand(throttle = -100, steering = 0, brakeLight = false), settings(maxThrottle = 50)).throttle)
        // Full reverse at 50 % -> -64.
        assertEquals(-64, applyDriveSettings(DriveCommand(throttle = -128, steering = 0, brakeLight = false), settings(maxThrottle = 50)).throttle)
    }

    @Test
    fun brakeLightPassesThrough() {
        val out = applyDriveSettings(DriveCommand(throttle = 10, steering = 10, brakeLight = true), settings())
        assertTrue(out.brakeLight)
    }
}
