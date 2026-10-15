// Layout per a community protocol wiki (Fuji kart protocol); format spec, no code copied.
package com.mklhc.core.kart_protocol.teleop

/**
 * One drive state to send to the kart: throttle, steering and the brake light.
 *
 * Business logic: the kart is steered by a stream of 32-octet datagrams (see [TeleopEncoder]) that each
 * carry the complete drive state, so a command is just that state; there is no "delta" form. Throttle and
 * steering are signed octets, so both are limited to -128..127: throttle -128 is full reverse and +127
 * full forward, steering -128 is full left and +127 full right, and 0 is neutral on both. A value outside
 * that range cannot be represented on the wire and is a programming error, rejected at construction.
 *
 * @property throttle -128 (full reverse) .. 127 (full forward); 0 is stopped.
 * @property steering -128 (full left) .. 127 (full right); 0 is straight.
 * @property brakeLight whether the kart's brake light is lit.
 * @throws IllegalArgumentException if [throttle] or [steering] is outside -128..127.
 */
data class DriveCommand(
    val throttle: Int,
    val steering: Int,
    val brakeLight: Boolean,
) {
    init {
        require(throttle in AXIS_RANGE) { "throttle $throttle is outside ${AXIS_RANGE.first}..${AXIS_RANGE.last}" }
        require(steering in AXIS_RANGE) { "steering $steering is outside ${AXIS_RANGE.first}..${AXIS_RANGE.last}" }
    }

    companion object {
        /** The signed-octet range both axes are limited to. */
        val AXIS_RANGE: IntRange = -128..127

        /** Throttle and steering at zero with the brake light off; what the deadman / safety path sends. */
        val NEUTRAL = DriveCommand(throttle = 0, steering = 0, brakeLight = false)
    }
}
