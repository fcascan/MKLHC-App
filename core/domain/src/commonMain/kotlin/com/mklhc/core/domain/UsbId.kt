package com.mklhc.core.domain

/**
 * The USB vendor and product id of a device.
 *
 * Business logic: adapters are recognised only by their `VID:PID` pair (product names are not a reliable
 * guide, as the same name can ship with different chips), so this pair is the app's key for the adapter
 * catalog, for the user's manual choice and for what is persisted. The text form is `vvvv:pppp`, four
 * lowercase hexadecimal digits each, the way the hardware guide and the device tools write it.
 *
 * @property vid the vendor id, 0..0xFFFF.
 * @property pid the product id, 0..0xFFFF.
 * @throws IllegalArgumentException if either value is outside 0..0xFFFF.
 */
data class UsbId(val vid: Int, val pid: Int) {
    init {
        require(vid in ID_RANGE) { "vid $vid is outside $ID_RANGE" }
        require(pid in ID_RANGE) { "pid $pid is outside $ID_RANGE" }
    }

    /** The `vvvv:pppp` form: four lowercase hexadecimal digits each. */
    override fun toString(): String = "${vid.toHex4()}:${pid.toHex4()}"

    companion object {
        /** The accepted range of the vendor id and of the product id. */
        val ID_RANGE: IntRange = 0..0xFFFF

        private const val HEX_DIGITS = 4
        private const val HEX_RADIX = 16
        private const val SEPARATOR = ':'

        /**
         * Reads the `vvvv:pppp` form back, in upper or lower case.
         *
         * Business logic: the text comes from storage, where a hand-edited or damaged value must be
         * rejected rather than crash the read, so a malformed text (wrong digit count, a sign, a prefix,
         * surrounding blanks, anything that is not exactly two groups of four hexadecimal digits) gives null.
         *
         * @return the id, or null when [text] is not in that form.
         */
        fun parse(text: String): UsbId? {
            if (text.length != HEX_DIGITS * 2 + 1 || text[HEX_DIGITS] != SEPARATOR) return null
            val vid = text.substring(0, HEX_DIGITS).hexGroupOrNull() ?: return null
            val pid = text.substring(HEX_DIGITS + 1).hexGroupOrNull() ?: return null
            return UsbId(vid, pid)
        }

        private fun String.hexGroupOrNull(): Int? =
            if (all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) toInt(HEX_RADIX) else null

        private fun Int.toHex4(): String = toString(HEX_RADIX).padStart(HEX_DIGITS, '0')
    }
}
