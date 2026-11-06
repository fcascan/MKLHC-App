// Layout measured by this project from a capture of the kart's telemetry; original code, no reference implementation.
package com.mklhc.core.kart_protocol.telemetry

/**
 * Decodes the payload of a type-3 telemetry datagram into a [Telemetry.FrameStatus].
 *
 * Business logic: the payload is 87 octets, measured from a telemetry dump (every one of 6,916 records fitted this
 * layout). Offsets are relative to the payload, the octet after the type octet; multi-octet values are little-endian:
 *
 * ```
 *  0 (7)    zero in every record seen, not read
 *  7 (32)   camera record of this frame
 * 39 (32)   camera record of the previous frame (octet for octet the previous datagram's record at 7)
 * 71 (16)   drive command echo
 *
 * Camera record (offsets inside it)       Drive command echo (offsets inside it)
 *  0 (4)  timer, u32 microseconds           0 (4)  timer, u32 microseconds (the camera record's)
 *  4 (4)  zero                               4 (4)  zero
 *  8 (4)  exposure, f32                      8 (1)  throttle, s8
 * 12 (4)  gain, f32                          9 (1)  steering, s8
 * 16 (4)  brightness, f32                   10 (2)  zero
 * 20 (2)  red gain, u16                     12 (4)  unknown value, f32
 * 22 (2)  green gain, u16
 * 24 (2)  blue gain, u16
 * 26 (6)  zero
 * ```
 *
 * The camera fields' meaning is a hypothesis (see [CameraFrameRecord]). The octets that were always zero are not read
 * or checked, so a firmware that fills them changes nothing here. A payload shorter than 87 octets cannot hold the echo
 * and is not decoded; a longer one is decoded and its extra octets ignored. Nothing is logged here: the kart sends 30 of
 * these a second, so the caller decides what to report (the session logs the first one).
 */
internal object FrameStatusDecoder {

    /** Octets of a type-3 payload, after the type octet. */
    const val PAYLOAD_SIZE = 87

    private const val CURRENT_RECORD_OFFSET = 7
    private const val PREVIOUS_RECORD_OFFSET = 39
    private const val ECHO_OFFSET = 71

    private const val TIMER_OFFSET = 0
    private const val EXPOSURE_OFFSET = 8
    private const val GAIN_OFFSET = 12
    private const val BRIGHTNESS_OFFSET = 16
    private const val RED_GAIN_OFFSET = 20
    private const val GREEN_GAIN_OFFSET = 22
    private const val BLUE_GAIN_OFFSET = 24

    private const val ECHO_THROTTLE_OFFSET = 8
    private const val ECHO_STEERING_OFFSET = 9
    private const val ECHO_UNKNOWN_VALUE_OFFSET = 12

    /**
     * Decodes [payload] (the datagram without its type octet).
     *
     * @return the frame status, or null when [payload] is shorter than [PAYLOAD_SIZE]; never throws.
     */
    fun decode(payload: ByteArray): Telemetry.FrameStatus? {
        if (payload.size < PAYLOAD_SIZE) return null
        return Telemetry.FrameStatus(
            current = payload.cameraRecordAt(CURRENT_RECORD_OFFSET),
            previous = payload.cameraRecordAt(PREVIOUS_RECORD_OFFSET),
            driveEcho = DriveCommandEcho(
                timerMicros = payload.u32LeAt(ECHO_OFFSET + TIMER_OFFSET),
                throttle = payload[ECHO_OFFSET + ECHO_THROTTLE_OFFSET].toInt(),
                steering = payload[ECHO_OFFSET + ECHO_STEERING_OFFSET].toInt(),
                unknownValue = payload.f32LeAt(ECHO_OFFSET + ECHO_UNKNOWN_VALUE_OFFSET),
            ),
        )
    }

    /** Reads the 32-octet camera record starting at [offset]. */
    private fun ByteArray.cameraRecordAt(offset: Int): CameraFrameRecord = CameraFrameRecord(
        timerMicros = u32LeAt(offset + TIMER_OFFSET),
        exposure = f32LeAt(offset + EXPOSURE_OFFSET),
        gain = f32LeAt(offset + GAIN_OFFSET),
        brightness = f32LeAt(offset + BRIGHTNESS_OFFSET),
        redGain = u16LeAt(offset + RED_GAIN_OFFSET),
        greenGain = u16LeAt(offset + GREEN_GAIN_OFFSET),
        blueGain = u16LeAt(offset + BLUE_GAIN_OFFSET),
    )
}
