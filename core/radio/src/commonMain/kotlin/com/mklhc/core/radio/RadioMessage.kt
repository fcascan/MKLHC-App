package com.mklhc.core.radio

/**
 * Typed model of every message exchanged with the `radiod` helper over its local socket.
 *
 * Business logic: radiod is a dumb radio. The app tells it what to do ([AppToRadiod]: open the
 * adapter, set channel/identity, manage the hardware beacon, transmit raw 802.11 frames) and radiod
 * reports back ([RadiodToApp]: readiness, received frames, counters, logs, errors). [IpcCodec]
 * turns these types into wire bytes and back.
 *
 * Numeric field conventions: unsigned 8 and 16 bit wire fields are modelled as [Int] and range
 * checked on construction, so an out-of-range value fails fast instead of being silently truncated
 * on the wire; unsigned 32 and 64 bit fields are modelled as [UInt] and [ULong]; the one signed
 * wire field (RSSI, i16) is an [Int] in the `Short` range.
 *
 * Messages that carry a [ByteArray] implement `equals`/`hashCode` by content, so two messages with
 * equal bytes compare equal (this is what the codec tests rely on). Their `toString` prints only
 * the byte count, never the bytes.
 */
sealed interface RadioMessage {

    /** Commands sent from the app to radiod. */
    sealed interface AppToRadiod : RadioMessage

    /** Notifications sent from radiod to the app. */
    sealed interface RadiodToApp : RadioMessage

    // ---------------------------------------------------------------------------------------
    // app -> radiod
    // ---------------------------------------------------------------------------------------

    /** First message of a session: protocol version plus the session token passed on radiod's argv. */
    data class Hello(val protocolVersion: Int, val token: String) : AppToRadiod {
        init {
            requireU16("protocolVersion", protocolVersion)
        }
    }

    /** Desktop form of "open the adapter": the USB vendor and product id (radiod opens it itself). */
    data class OpenDevice(val vid: Int, val pid: Int) : AppToRadiod {
        init {
            requireU16("vid", vid)
            requireU16("pid", pid)
        }
    }

    /** Brings the radio up on [channel] with the given bandwidth and TX power index. */
    data class Init(val channel: Int, val bandwidthMhz: Int, val txPowerIndex: Int) : AppToRadiod {
        init {
            requireU8("channel", channel)
            requireU8("bandwidthMhz", bandwidthMhz)
            requireU8("txPowerIndex", txPowerIndex)
        }
    }

    /** Sets the BSSID / own MAC used by the hardware ACK engine. [mac] must be exactly 6 bytes. */
    class SetIdentity(val mac: ByteArray) : AppToRadiod {
        init {
            require(mac.size == MAC_SIZE) { "mac must be $MAC_SIZE bytes, was ${mac.size}" }
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is SetIdentity && mac.contentEquals(other.mac))

        override fun hashCode(): Int = mac.contentHashCode()

        override fun toString(): String = "SetIdentity(mac=${formatMac(mac)})"

        companion object {
            const val MAC_SIZE = 6
        }
    }

    /** Starts the hardware beacon: [intervalTu] time units (1 TU = 1024 us) and the full beacon frame. */
    class StartBeacon(val intervalTu: Int, val beacon: ByteArray) : AppToRadiod {
        init {
            requireU16("intervalTu", intervalTu)
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is StartBeacon && intervalTu == other.intervalTu && beacon.contentEquals(other.beacon))

        override fun hashCode(): Int = 31 * intervalTu + beacon.contentHashCode()

        override fun toString(): String = "StartBeacon(intervalTu=$intervalTu, beacon=${beacon.size} bytes)"
    }

    /** Replaces the running hardware beacon with a new full beacon frame. */
    class UpdateBeacon(val beacon: ByteArray) : AppToRadiod {
        override fun equals(other: Any?): Boolean =
            this === other || (other is UpdateBeacon && beacon.contentEquals(other.beacon))

        override fun hashCode(): Int = beacon.contentHashCode()

        override fun toString(): String = "UpdateBeacon(beacon=${beacon.size} bytes)"
    }

    /** Stops the hardware beacon. */
    data object StopBeacon : AppToRadiod

    /**
     * Transmits one raw 802.11 [frame] at [rate] (for example `"6M"`), with a hardware retry limit
     * and a TX flags byte whose bits radiod defines.
     */
    class TxFrame(
        val rate: String,
        val retryLimit: Int,
        val flags: Int,
        val frame: ByteArray,
    ) : AppToRadiod {
        init {
            requireU8("retryLimit", retryLimit)
            requireU8("flags", flags)
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is TxFrame &&
                        rate == other.rate &&
                        retryLimit == other.retryLimit &&
                        flags == other.flags &&
                        frame.contentEquals(other.frame)
                    )

        override fun hashCode(): Int {
            var result = rate.hashCode()
            result = 31 * result + retryLimit
            result = 31 * result + flags
            result = 31 * result + frame.contentHashCode()
            return result
        }

        override fun toString(): String =
            "TxFrame(rate=$rate, retryLimit=$retryLimit, flags=$flags, frame=${frame.size} bytes)"
    }

    /** Asks radiod to stop the radio and exit. */
    data object Shutdown : AppToRadiod

    // ---------------------------------------------------------------------------------------
    // radiod -> app
    // ---------------------------------------------------------------------------------------

    /** The adapter is open and the radio is up; carries identification strings for diagnostics. */
    data class Ready(
        val chipId: String,
        val firmware: String,
        val devourerVersion: String,
    ) : RadiodToApp

    /**
     * One received 802.11 frame (FCS already stripped by radiod) with its [rssiDbm] (signed 16 bit),
     * PHY [rate] code (u8) and hardware [tsf] timestamp.
     */
    class RxFrame(
        val rssiDbm: Int,
        val rate: Int,
        val tsf: ULong,
        val frame: ByteArray,
    ) : RadiodToApp {
        init {
            require(rssiDbm in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) { "rssiDbm must fit in i16, was $rssiDbm" }
            requireU8("rate", rate)
        }

        /** Converts to the transport-neutral [RadioFrame] handed to app code. */
        fun toRadioFrame(): RadioFrame = RadioFrame(rssiDbm = rssiDbm, rate = rate, tsf = tsf, frame = frame)

        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is RxFrame &&
                        rssiDbm == other.rssiDbm &&
                        rate == other.rate &&
                        tsf == other.tsf &&
                        frame.contentEquals(other.frame)
                    )

        override fun hashCode(): Int {
            var result = rssiDbm
            result = 31 * result + rate
            result = 31 * result + tsf.hashCode()
            result = 31 * result + frame.contentHashCode()
            return result
        }

        override fun toString(): String =
            "RxFrame(rssiDbm=$rssiDbm, rate=$rate, tsf=$tsf, frame=${frame.size} bytes)"
    }

    /** Periodic radiod counters: frames received, frames sent, transmit errors and beacons sent. */
    data class Status(
        val rx: UInt,
        val tx: UInt,
        val txErrors: UInt,
        val beacons: UInt,
    ) : RadiodToApp

    /**
     * A log line forwarded from radiod (the app re-logs it under tag `radiod`). [level] is one of
     * [LEVEL_DEBUG], [LEVEL_INFO], [LEVEL_WARN] or [LEVEL_ERROR]; a larger value is treated as error.
     */
    data class Log(val level: Int, val text: String) : RadiodToApp {
        init {
            requireU8("level", level)
        }

        companion object {
            const val LEVEL_DEBUG = 0
            const val LEVEL_INFO = 1
            const val LEVEL_WARN = 2
            const val LEVEL_ERROR = 3
        }
    }

    /** An error reported by radiod; fatal errors end the radiod process. */
    data class Error(val code: UInt, val text: String) : RadiodToApp
}

private fun requireU8(name: String, value: Int) {
    require(value in 0..0xFF) { "$name must fit in u8, was $value" }
}

private fun requireU16(name: String, value: Int) {
    require(value in 0..0xFFFF) { "$name must fit in u16, was $value" }
}

private fun formatMac(bytes: ByteArray): String =
    bytes.joinToString(separator = ":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
