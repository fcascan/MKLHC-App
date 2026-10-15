package com.mklhc.core.domain

/**
 * The user's driving preferences, persisted with the pairing data.
 *
 * Business logic: the settings shape what the app sends and what it does at the end of a session; they
 * never change the protocol. Throttle and steering are expressed as whole percentages so the stored
 * values are exact and the UI can bind them to sliders:
 * - [maxThrottlePercent] scales to how much of the kart's full throttle the app will ever command. The
 *   default is deliberately half, so a first drive on a fast kart is gentle; the user raises it.
 * - [steeringExpoPercent] softens steering around the centre (0 is linear, 100 is the strongest curve).
 * - [steeringTrimPercent] offsets the steering centre to compensate for a kart that pulls to one side
 *   (negative is left, positive is right).
 * - [shutdownOnExit] powers the kart off when the app leaves the session, instead of leaving it idling
 *   (and draining its battery) with no controller.
 *
 * @property maxThrottlePercent 1..100.
 * @property steeringExpoPercent 0..100.
 * @property steeringTrimPercent -50..50.
 * @property shutdownOnExit whether to shut the kart down when the app exits the session.
 * @throws IllegalArgumentException if a percentage is outside its range.
 */
data class KartSettings(
    val maxThrottlePercent: Int = DEFAULT_MAX_THROTTLE_PERCENT,
    val steeringExpoPercent: Int = 0,
    val steeringTrimPercent: Int = 0,
    val shutdownOnExit: Boolean = true,
) {
    init {
        require(maxThrottlePercent in MAX_THROTTLE_RANGE) { "maxThrottlePercent $maxThrottlePercent is outside $MAX_THROTTLE_RANGE" }
        require(steeringExpoPercent in STEERING_EXPO_RANGE) { "steeringExpoPercent $steeringExpoPercent is outside $STEERING_EXPO_RANGE" }
        require(steeringTrimPercent in STEERING_TRIM_RANGE) { "steeringTrimPercent $steeringTrimPercent is outside $STEERING_TRIM_RANGE" }
    }

    companion object {
        /** Throttle cap a fresh installation starts with. */
        const val DEFAULT_MAX_THROTTLE_PERCENT = 50

        /** The accepted range of [maxThrottlePercent]. */
        val MAX_THROTTLE_RANGE: IntRange = 1..100

        /** The accepted range of [steeringExpoPercent]. */
        val STEERING_EXPO_RANGE: IntRange = 0..100

        /** The accepted range of [steeringTrimPercent]. */
        val STEERING_TRIM_RANGE: IntRange = -50..50

        /** The settings of a fresh installation. */
        val DEFAULT: KartSettings = KartSettings()

        /**
         * Builds settings from possibly out-of-range values by clamping each percentage into its range.
         *
         * Business logic: used when reading values back from storage, where a corrupted or hand-edited
         * value must degrade to the nearest valid setting rather than make the whole read fail.
         */
        fun coerced(
            maxThrottlePercent: Int,
            steeringExpoPercent: Int,
            steeringTrimPercent: Int,
            shutdownOnExit: Boolean,
        ): KartSettings = KartSettings(
            maxThrottlePercent = maxThrottlePercent.coerceIn(MAX_THROTTLE_RANGE),
            steeringExpoPercent = steeringExpoPercent.coerceIn(STEERING_EXPO_RANGE),
            steeringTrimPercent = steeringTrimPercent.coerceIn(STEERING_TRIM_RANGE),
            shutdownOnExit = shutdownOnExit,
        )
    }
}
