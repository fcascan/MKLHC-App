package com.mklhc.feature.pairing.presentation.qr

/**
 * A finished QR code as a square grid of modules.
 *
 * Business logic: the output of [encodeQrMatrix] and the input of the QrCode composable, so the drawing code in
 * commonMain never needs to know which encoder produced it. The grid holds only the code itself (finder patterns,
 * timing, data and error correction), without the quiet zone, which the drawing adds around it.
 *
 * @property size the number of modules on each side (33 for a version 4 code).
 * @param modules the modules row by row, `true` for a dark module; must hold exactly `size * size` values. The
 *   array is copied, so later changes to it do not affect the matrix.
 */
class QrMatrix(val size: Int, modules: BooleanArray) {

    private val modules: BooleanArray = modules.copyOf()

    init {
        require(size > 0) { "QR matrix size must be positive, got $size" }
        require(modules.size == size * size) { "QR matrix of size $size needs ${size * size} modules, got ${modules.size}" }
    }

    /** Whether the module in column [x] and row [y] (both 0 until [size], origin at the top left) is dark. */
    operator fun get(x: Int, y: Int): Boolean {
        require(x in 0 until size && y in 0 until size) { "module ($x, $y) is outside the $size x $size matrix" }
        return modules[y * size + x]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrMatrix) return false
        return size == other.size && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int = 31 * size + modules.contentHashCode()

    override fun toString(): String = "QrMatrix(size=$size)"
}
