package com.mklhc.core.radio

/**
 * One 802.11 frame received by the radio, as handed to app code by [RadioLink.rxFrames].
 *
 * Business logic: a transport-neutral view of radiod's `RX_FRAME` message. [frame] holds the raw
 * 802.11 MPDU with the FCS already stripped; [rssiDbm] is the signal strength in dBm, [rate] the
 * PHY rate code reported by the hardware and [tsf] the hardware timestamp in microseconds.
 *
 * Because [frame] is a [ByteArray], equality and hash code are by content (a data class would
 * otherwise compare the array by reference).
 */
data class RadioFrame(
    val rssiDbm: Int,
    val rate: Int,
    val tsf: ULong,
    val frame: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RadioFrame) return false
        return rssiDbm == other.rssiDbm &&
            rate == other.rate &&
            tsf == other.tsf &&
            frame.contentEquals(other.frame)
    }

    override fun hashCode(): Int {
        var result = rssiDbm
        result = 31 * result + rate
        result = 31 * result + tsf.hashCode()
        result = 31 * result + frame.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RadioFrame(rssiDbm=$rssiDbm, rate=$rate, tsf=$tsf, frame=${frame.size} bytes)"
}
