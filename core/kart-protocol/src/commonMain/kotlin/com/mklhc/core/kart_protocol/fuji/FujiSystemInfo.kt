// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/fuji.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.fuji

import com.mklhc.core.kart_protocol.rcd.RcdProtocolException

/**
 * The kart's firmware identity, answered to `GetSystemInfo`.
 *
 * Business logic: the payload is the boot loader version (major, minor), the system firmware version
 * (major, minor) and then the firmware's SHA-1 as a NUL-terminated latin1 string. The app only
 * records it (and shows it as kart information); nothing in the session depends on its value.
 *
 * @property bootVersion (major, minor) of the boot loader, each 0..255.
 * @property systemVersion (major, minor) of the system firmware, each 0..255.
 * @property sha1 the firmware SHA-1 text as the kart reports it.
 */
data class FujiSystemInfo(
    val bootVersion: Pair<Int, Int>,
    val systemVersion: Pair<Int, Int>,
    val sha1: String,
) {
    companion object {
        /** Octets of the two version pairs that precede the SHA-1 text. */
        private const val VERSION_HEADER_SIZE = 4

        /**
         * Decodes a `GetSystemInfo` response payload.
         *
         * @throws RcdProtocolException if [data] is too short to hold the two version pairs.
         */
        fun decode(data: ByteArray): FujiSystemInfo {
            if (data.size < VERSION_HEADER_SIZE) {
                throw RcdProtocolException("system info of ${data.size} octets is shorter than $VERSION_HEADER_SIZE")
            }
            return FujiSystemInfo(
                bootVersion = data.u8At(0) to data.u8At(1),
                systemVersion = data.u8At(2) to data.u8At(3),
                sha1 = data.latin1UntilNul(from = VERSION_HEADER_SIZE),
            )
        }
    }
}
