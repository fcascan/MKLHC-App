package com.mklhc.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [KartSettings]: the safe defaults, the accepted ranges and the clamping helper. */
class KartSettingsTest {

    @Test
    fun defaultsAreTheSafeFirstRunValues() {
        val defaults = KartSettings()
        assertEquals(KartSettings.DEFAULT_MAX_THROTTLE_PERCENT, defaults.maxThrottlePercent)
        assertEquals(0, defaults.steeringExpoPercent)
        assertEquals(0, defaults.steeringTrimPercent)
        assertEquals(true, defaults.shutdownOnExit)
        assertEquals(KartSettings(), KartSettings.DEFAULT)
    }

    @Test
    fun boundaryValuesAreAccepted() {
        KartSettings(maxThrottlePercent = 1, steeringExpoPercent = 0, steeringTrimPercent = -50)
        KartSettings(maxThrottlePercent = 100, steeringExpoPercent = 100, steeringTrimPercent = 50)
    }

    @Test
    fun outOfRangeValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> { KartSettings(maxThrottlePercent = 0) }
        assertFailsWith<IllegalArgumentException> { KartSettings(maxThrottlePercent = 101) }
        assertFailsWith<IllegalArgumentException> { KartSettings(steeringExpoPercent = -1) }
        assertFailsWith<IllegalArgumentException> { KartSettings(steeringExpoPercent = 101) }
        assertFailsWith<IllegalArgumentException> { KartSettings(steeringTrimPercent = -51) }
        assertFailsWith<IllegalArgumentException> { KartSettings(steeringTrimPercent = 51) }
    }

    @Test
    fun coercedClampsEveryFieldIntoRange() {
        val clamped = KartSettings.coerced(
            maxThrottlePercent = 500,
            steeringExpoPercent = -20,
            steeringTrimPercent = 90,
            shutdownOnExit = false,
        )
        assertEquals(KartSettings(maxThrottlePercent = 100, steeringExpoPercent = 0, steeringTrimPercent = 50, shutdownOnExit = false), clamped)
    }

    @Test
    fun coercedLeavesValidValuesUntouched() {
        val valid = KartSettings(maxThrottlePercent = 70, steeringExpoPercent = 30, steeringTrimPercent = -5, shutdownOnExit = false)
        assertEquals(
            valid,
            KartSettings.coerced(
                maxThrottlePercent = 70,
                steeringExpoPercent = 30,
                steeringTrimPercent = -5,
                shutdownOnExit = false,
            ),
        )
    }
}
