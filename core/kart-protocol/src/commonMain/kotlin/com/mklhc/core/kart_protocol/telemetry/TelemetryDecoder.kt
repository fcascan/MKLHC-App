// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/fuji.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.telemetry

import co.touchlab.kermit.Logger

/**
 * Turns one UDP telemetry datagram from the kart into a [Telemetry] value.
 *
 * Business logic: the first octet of every telemetry datagram is its type and the rest is the payload.
 * Type 1 is the battery status (the reference handler requires a 0x1f-octet payload and reads the cable
 * bit from octet 0 and the battery bars from octet 3); type 2 is the IMU packet (see [ImuDecoder]); type
 * 3 is not specified yet (the wiki calls it "motion feedback", the reference ignores it), so it is kept
 * as [Telemetry.Unknown] without a log line (the kart sends one about 30 times a second; the session logs
 * the first one); every other type is reported as [Telemetry.Unknown] with a warning, as the reference does.
 *
 * The decoder never throws on what the kart sends, since one malformed datagram must not stop the
 * stream: an empty datagram, a type-1 or type-2 payload too short to read, and any other type all come
 * back as [Telemetry.Unknown] carrying the payload, and the reason is logged. One deliberate difference
 * from the reference: a type-1 payload longer than 0x1f octets is still decoded (with a warning) rather
 * than dropped, since only octets 0 and 3 are read and a longer status record should not blank the HUD.
 *
 * Stateless; safe to share between threads.
 *
 * @param log the logger; replaceable so tests can capture what is logged.
 */
class TelemetryDecoder(
    private val log: Logger = Logger.withTag(TAG),
) {

    /**
     * Decodes [packet], a whole telemetry datagram including its leading type octet.
     *
     * @return the decoded telemetry, or [Telemetry.Unknown] for anything not decoded; never throws on
     *   the contents of [packet].
     */
    fun decode(packet: ByteArray): Telemetry {
        if (packet.isEmpty()) {
            log.w { "decode(): empty telemetry datagram -> ignoring" }
            return Telemetry.Unknown(Telemetry.Unknown.NO_TYPE, ByteArray(0))
        }

        val type = packet[0].toInt() and 0xFF
        val payload = packet.copyOfRange(1, packet.size)

        return when (type) {
            TYPE_BATTERY_STATUS -> decodeBatteryStatus(payload) ?: Telemetry.Unknown(type, payload)
            TYPE_IMU -> ImuDecoder.decode(payload, log) ?: Telemetry.Unknown(type, payload)
            // Not logged here: it arrives about 30 times a second, so a line per datagram would drown the log.
            TYPE_MOTION_FEEDBACK -> Telemetry.Unknown(type, payload)
            else -> {
                log.w { "decode(): unknown telemetry type -> type=0x${type.toString(16).padStart(2, '0')} size=${payload.size}" }
                Telemetry.Unknown(type, payload)
            }
        }
    }

    /**
     * Reads the cable flags (payload octet 0) and the battery bars (payload octet 3).
     *
     * @return the status, or null (and a warning logged) if [payload] is shorter than the 0x1f octets the
     *   status record is expected to have.
     */
    private fun decodeBatteryStatus(payload: ByteArray): Telemetry.BatteryStatus? {
        if (payload.size < BATTERY_PAYLOAD_SIZE) {
            log.w { "decodeBatteryStatus(): status telemetry too short -> size=${payload.size} expected=$BATTERY_PAYLOAD_SIZE" }
            return null
        }
        if (payload.size > BATTERY_PAYLOAD_SIZE) {
            log.w { "decodeBatteryStatus(): status telemetry longer than expected -> size=${payload.size} expected=$BATTERY_PAYLOAD_SIZE" }
        }
        return Telemetry.BatteryStatus(
            cableFlags = payload.u8At(BATTERY_CABLE_OFFSET),
            batteryBars = payload.u8At(BATTERY_BARS_OFFSET),
        )
    }

    companion object {
        /** Type octet of the battery status datagram. */
        const val TYPE_BATTERY_STATUS = 1

        /** Type octet of the IMU datagram. */
        const val TYPE_IMU = 2

        /** Type octet of the datagram the wiki lists as "motion feedback" (layout not specified). */
        const val TYPE_MOTION_FEEDBACK = 3

        private const val TAG = "TelemetryDecoder"

        /** Payload octets of a battery status record, after the type octet. */
        private const val BATTERY_PAYLOAD_SIZE = 0x1f

        private const val BATTERY_CABLE_OFFSET = 0
        private const val BATTERY_BARS_OFFSET = 3
    }
}
