// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/fuji.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
//
// readApplicationData (command 0x12) is not in OpenKart:
// Adapted from mk-live https://github.com/olus85/mk-live, patches/openkartd/fuji.py (MIT).
package com.mklhc.core.kart_protocol.fuji

import co.touchlab.kermit.Logger
import com.mklhc.core.kart_protocol.rcd.RcdClient
import com.mklhc.core.kart_protocol.rcd.RcdProtocolException

/**
 * The client of the kart's Fuji Control service (RCD service 0x100, TCP port [FujiPorts.CONTROL]).
 *
 * Business logic: after the RCD handshake the app connects to the kart's control port and drives the
 * session set-up and run state through this service. A normal session is [getSystemInfo], then
 * [getProductCode], then [setConnectionInfo] (which tells the kart where to send telemetry and video),
 * then [setState] with [STATE_DRIVE] to start driving; [setState] with [STATE_SLEEP] parks the kart and
 * [shutdown] powers it off. Every request is built exactly as the reference does -- zero-padded
 * fixed-width fields with big-endian length prefixes, and the little-endian `connection_info` struct --
 * and sent through the supplied [RcdClient], so a non-zero kart status surfaces as an
 * [com.mklhc.core.kart_protocol.rcd.RcdError] and transport problems as the RCD exceptions.
 *
 * @param rcd the RCD client connected to the kart's control port.
 */
class FujiControlClient(private val rcd: RcdClient) {
    private val log = Logger.withTag(TAG)

    /** Asks the kart for its firmware identity (command 1). */
    suspend fun getSystemInfo(): FujiSystemInfo {
        log.d { "getSystemInfo(): requesting" }
        val response = rcd.invoke(SERVICE_ID, CMD_GET_SYSTEM_INFO)
        // FujiSystemInfo has no logger, so log the too-short-payload rejection at this call site.
        val info = try {
            FujiSystemInfo.decode(response)
        } catch (malformed: RcdProtocolException) {
            log.w { "getSystemInfo(): kart returned a too-short payload -> size=${response.size} error=${malformed.message}" }
            throw malformed
        }
        log.d { "getSystemInfo(): received -> boot=${info.bootVersion} system=${info.systemVersion} sha1=${info.sha1}" }
        return info
    }

    /**
     * Sets the named kart parameter to [value] (command 2).
     *
     * The request is the parameter name zero-padded to 0x80 octets, then the value length as a 2-octet
     * big-endian number zero-padded to 0x10 octets, then the value itself.
     *
     * @throws IllegalArgumentException if the encoded name is not shorter than 0x80 octets or [value] is
     * longer than 0xFFFF octets.
     */
    suspend fun setParam(param: String, value: ByteArray) {
        val name = param.encodeToByteArray()
        require(name.size < PARAM_NAME_SIZE) { "parameter name of ${name.size} octets must be shorter than $PARAM_NAME_SIZE" }

        log.d { "setParam(): sending -> param=$param valueSize=${value.size}" }
        val request = padTo(name, PARAM_NAME_SIZE) + padTo(u16BigEndian(value.size), PARAM_LENGTH_SIZE) + value
        rcd.invoke(SERVICE_ID, CMD_SET_PARAM, request)
    }

    /**
     * Tells the kart where to send telemetry and video (the `connection_info` parameter).
     *
     * The value is the little-endian struct `<HHHHQ`: [unk], [telemetryPort], [videoControlPort],
     * [videoStreamPort] and [timestamp], sent through [setParam]. The kart uses the timestamp to set its
     * clock. This client never reads a clock itself, so the caller supplies the time.
     *
     * @param telemetryPort UDP port on the app that telemetry is sent to.
     * @param videoControlPort TCP port on the app the kart connects to for video control.
     * @param videoStreamPort UDP port on the app that the video stream is sent to.
     * @param timestamp the current Unix time in seconds, as supplied by the caller (the reference
     * defaults it to the wall clock at call time).
     * @param unk an unknown 16-bit field; the reference sends 1.
     * @throws IllegalArgumentException if a port or [unk] is outside 0..0xFFFF or [timestamp] is negative.
     */
    suspend fun setConnectionInfo(
        telemetryPort: Int,
        videoControlPort: Int,
        videoStreamPort: Int,
        timestamp: Long,
        unk: Int = 1,
    ) {
        val value = ByteArray(CONNECTION_INFO_SIZE)
        value.setU16LittleEndian(0, unk)
        value.setU16LittleEndian(2, telemetryPort)
        value.setU16LittleEndian(4, videoControlPort)
        value.setU16LittleEndian(6, videoStreamPort)
        value.setU64LittleEndian(8, timestamp)

        log.d {
            "setConnectionInfo(): sending -> telemetryPort=$telemetryPort videoControlPort=$videoControlPort " +
                "videoStreamPort=$videoStreamPort unk=$unk timestamp=$timestamp"
        }
        setParam(PARAM_CONNECTION_INFO, value)
    }

    /**
     * Reads the named kart parameter (command 3) and returns its value.
     *
     * The request is the name zero-padded to 0x80 octets. The response starts with the value length as
     * a 2-octet big-endian number (zero-padded to 0x10 octets) followed by that many value octets. As in
     * the reference, a response that carries fewer octets than it declares yields just those present.
     *
     * @throws IllegalArgumentException if the encoded name is not shorter than 0x80 octets.
     */
    suspend fun getParam(param: String): ByteArray {
        val name = param.encodeToByteArray()
        require(name.size < PARAM_NAME_SIZE) { "parameter name of ${name.size} octets must be shorter than $PARAM_NAME_SIZE" }

        log.d { "getParam(): requesting -> param=$param" }
        val response = rcd.invoke(SERVICE_ID, CMD_GET_PARAM, padTo(name, PARAM_NAME_SIZE))

        // Python's int.from_bytes(resp[:2], 'big') and resp[0x10:][:x] both tolerate a short response.
        val declared = response.take(2).fold(0) { acc, octet -> (acc shl 8) or (octet.toInt() and 0xFF) }
        val start = minOf(PARAM_LENGTH_SIZE, response.size)
        val available = response.size - start
        if (declared > available) {
            log.w { "getParam(): response shorter than declared -> param=$param declared=$declared available=$available" }
        }
        return response.copyOfRange(start, start + minOf(declared, available))
    }

    /** Reads and decodes the kart's `product_code` parameter (a special case of [getParam]). */
    suspend fun getProductCode(): FujiProductCode {
        val value = getParam(PARAM_PRODUCT_CODE)
        // FujiProductCode has no logger, so log the too-short-payload rejection at this call site.
        val code = try {
            FujiProductCode.decode(value)
        } catch (malformed: RcdProtocolException) {
            log.w { "getProductCode(): kart returned a too-short payload -> size=${value.size} error=${malformed.message}" }
            throw malformed
        }
        log.d { "getProductCode(): received -> character=${code.character} serial=${code.serial}" }
        return code
    }

    /**
     * Sets the kart's run state (command 4): [STATE_DRIVE] (1) to accept teleoperation, [STATE_SLEEP] (0)
     * to park. The request is the state octet zero-padded to 0x10 octets.
     *
     * @throws IllegalArgumentException if [state] does not fit in one octet.
     */
    suspend fun setState(state: Int) {
        require(state in 0..0xFF) { "state $state does not fit in one octet" }

        log.d { "setState(): sending -> state=$state" }
        rcd.invoke(SERVICE_ID, CMD_SET_STATE, padTo(byteArrayOf(state.toByte()), STATE_REQUEST_SIZE))
    }

    /** Powers the kart off (command 9, no payload). */
    suspend fun shutdown() {
        log.i { "shutdown(): requesting kart power-off" }
        rcd.invoke(SERVICE_ID, CMD_SHUTDOWN)
    }

    /**
     * Reads an application data blob stored on the kart (command 0x12) and returns it.
     *
     * Adapted from mk-live https://github.com/olus85/mk-live, patches/openkartd/fuji.py (MIT).
     *
     * The request is the ASCII [applicationId] zero-padded to 0x10 octets, then [maxSize] as a 4-octet
     * big-endian number, the whole zero-padded to 0x20 octets. Used for diagnostics only; use
     * [APPLICATION_ID] for the kart's own companion-application record.
     *
     * @param maxSize the largest number of octets the kart may return.
     * @throws IllegalArgumentException if [applicationId] is not ASCII or is longer than 0x10 octets, or
     * [maxSize] is negative.
     */
    suspend fun readApplicationData(applicationId: String, maxSize: Int = DEFAULT_APPLICATION_DATA_SIZE): ByteArray {
        require(applicationId.all { it.code < 0x80 }) { "application id must be ASCII" }
        val id = applicationId.encodeToByteArray()
        require(id.size <= APPLICATION_ID_SIZE) { "application id of ${id.size} octets exceeds $APPLICATION_ID_SIZE" }

        log.d { "readApplicationData(): requesting -> applicationId=$applicationId maxSize=$maxSize" }
        val request = padTo(padTo(id, APPLICATION_ID_SIZE) + u32BigEndian(maxSize), APPLICATION_DATA_REQUEST_SIZE)
        return rcd.invoke(SERVICE_ID, CMD_READ_APPLICATION_DATA, request)
    }

    companion object {
        /** The RCD service id of Fuji Control. */
        const val SERVICE_ID = 0x100

        /** [setState] value that puts the kart in drive mode (accepts teleoperation). */
        const val STATE_DRIVE = 1

        /** [setState] value that puts the kart to sleep (parked). */
        const val STATE_SLEEP = 0

        /** The application id of the kart's own companion-application record, for [readApplicationData]. */
        const val APPLICATION_ID = "YVCOQ00000000XFB"

        private const val TAG = "FujiControlClient"

        private const val CMD_GET_SYSTEM_INFO = 1
        private const val CMD_SET_PARAM = 2
        private const val CMD_GET_PARAM = 3
        private const val CMD_SET_STATE = 4
        private const val CMD_SHUTDOWN = 9
        private const val CMD_READ_APPLICATION_DATA = 0x12

        private const val PARAM_CONNECTION_INFO = "connection_info"
        private const val PARAM_PRODUCT_CODE = "product_code"

        private const val PARAM_NAME_SIZE = 0x80
        private const val PARAM_LENGTH_SIZE = 0x10
        private const val STATE_REQUEST_SIZE = 0x10
        private const val CONNECTION_INFO_SIZE = 16
        private const val APPLICATION_ID_SIZE = 0x10
        private const val APPLICATION_DATA_REQUEST_SIZE = 0x20
        private const val DEFAULT_APPLICATION_DATA_SIZE = 0x800
    }
}
