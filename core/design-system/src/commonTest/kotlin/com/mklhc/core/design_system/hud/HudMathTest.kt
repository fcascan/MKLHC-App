package com.mklhc.core.design_system.hud

import androidx.compose.ui.geometry.Offset
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure arithmetic behind the HUD widgets: how a battery bar count, a signal strength, a pair of g values
 * and a pitch angle turn into what the canvases draw. Kept out of the draw lambdas so it can be pinned here
 * without rendering anything.
 */
class HudMathTest {

    @Test
    fun batteryBarsAreClampedToTheGaugeRange() {
        assertEquals(0, batteryBarsOf(-3))
        assertEquals(0, batteryBarsOf(0))
        assertEquals(2, batteryBarsOf(2))
        assertEquals(4, batteryBarsOf(4))
        // The kart reports the level as it likes; a value past the gauge must not draw an extra bar.
        assertEquals(4, batteryBarsOf(9))
    }

    @Test
    fun unknownSignalHasNoBars() {
        assertEquals(0, signalLevel(null))
    }

    @Test
    fun signalLevelFollowsTheRssiThresholds() {
        assertEquals(4, signalLevel(-30))
        assertEquals(4, signalLevel(-55))
        assertEquals(3, signalLevel(-56))
        assertEquals(3, signalLevel(-65))
        assertEquals(2, signalLevel(-66))
        assertEquals(2, signalLevel(-75))
        assertEquals(1, signalLevel(-76))
        assertEquals(1, signalLevel(-85))
        assertEquals(0, signalLevel(-86))
        assertEquals(0, signalLevel(-120))
    }

    @Test
    fun gMeterIsCentredAtRest() {
        assertEquals(Offset.Zero, gMeterPosition(gX = 0f, gY = 0f, maxG = 1.5f))
    }

    @Test
    fun gMeterMapsRightAndForwardToRightAndUp() {
        // Screen coordinates: x grows to the right, y grows downward, so forward (positive gY) is negative y.
        val right = gMeterPosition(gX = 0.75f, gY = 0f, maxG = 1.5f)
        assertEquals(0.5f, right.x, 1e-6f)
        assertEquals(0f, right.y, 1e-6f)

        val forward = gMeterPosition(gX = 0f, gY = 0.75f, maxG = 1.5f)
        assertEquals(0f, forward.x, 1e-6f)
        assertEquals(-0.5f, forward.y, 1e-6f)

        val braking = gMeterPosition(gX = 0f, gY = -1.5f, maxG = 1.5f)
        assertEquals(1f, braking.y, 1e-6f)
    }

    @Test
    fun gMeterKeepsTheDotInsideTheUnitCircleAndTheDirection() {
        val point = gMeterPosition(gX = 9f, gY = 9f, maxG = 1.5f)
        assertEquals(1f, sqrt(point.x * point.x + point.y * point.y), 1e-5f)
        assertTrue(point.x > 0f && point.y < 0f, "direction preserved: right and forward")
        assertEquals(point.x, -point.y, 1e-5f)
    }

    @Test
    fun gMeterTreatsNonFiniteInputAsAtRest() {
        assertEquals(Offset.Zero, gMeterPosition(gX = Float.NaN, gY = 1f, maxG = 1.5f))
        assertEquals(Offset.Zero, gMeterPosition(gX = 1f, gY = Float.POSITIVE_INFINITY, maxG = 1.5f))
    }

    @Test
    fun horizonShiftIsZeroLevelAndMovesDownWhenTheNoseIsUp() {
        assertEquals(0f, horizonShiftPx(pitchDegrees = 0f, radiusPx = 100f), 1e-6f)
        assertTrue(horizonShiftPx(pitchDegrees = 10f, radiusPx = 100f) > 0f, "nose up pushes the horizon down the screen")
        assertTrue(horizonShiftPx(pitchDegrees = -10f, radiusPx = 100f) < 0f)
        assertEquals(
            -horizonShiftPx(pitchDegrees = 10f, radiusPx = 100f),
            horizonShiftPx(pitchDegrees = -10f, radiusPx = 100f),
            1e-6f,
        )
    }

    @Test
    fun horizonShiftIsClampedSoARunawayAngleStaysBounded() {
        val atLimit = horizonShiftPx(pitchDegrees = HORIZON_MAX_PITCH_DEGREES, radiusPx = 100f)
        assertEquals(atLimit, horizonShiftPx(pitchDegrees = 500f, radiusPx = 100f), 1e-6f)
        assertEquals(0f, horizonShiftPx(pitchDegrees = Float.NaN, radiusPx = 100f), 1e-6f)
    }

    @Test
    fun tenthsFormattingMatchesTheSpokenDescriptions() {
        assertEquals("0.0", formatTenths(0f))
        assertEquals("0.5", formatTenths(0.5f))
        assertEquals("-0.3", formatTenths(-0.26f))
        assertEquals("1.0", formatTenths(0.96f))
        assertEquals("0.0", formatTenths(Float.NaN))
    }
}
