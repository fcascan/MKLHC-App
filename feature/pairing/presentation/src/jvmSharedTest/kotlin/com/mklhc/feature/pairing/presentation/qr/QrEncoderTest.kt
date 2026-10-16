package com.mklhc.feature.pairing.presentation.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.Decoder
import com.mklhc.core.kart_protocol.pairing.PAIRING_PAYLOAD_SIZE
import com.mklhc.core.kart_protocol.pairing.encodePairingPayload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [encodeQrMatrix]: the 62 raw pairing octets must become a byte-mode, error-correction-level-M QR code that a
 * scanner decodes back to exactly those octets. The decode round trips use ZXing's own decoder, so they pin the
 * properties the kart's scanner relies on (raw octets in a single byte segment, level M, no ECI header that would
 * push the code past version 4).
 */
class QrEncoderTest {

    /** A realistic payload: random-looking seed, SSID, channel and the zero padding of the pairing layout. */
    private fun payload(seedByte: Int = 0x5A, ssid: String = "kart-pair-01", channel: Int = 11): ByteArray {
        val seed = ByteArray(16) { (seedByte + it * 31).toByte() }
        return encodePairingPayload(seed, ssid, channel)
    }

    private fun QrMatrix.toBitMatrix(): BitMatrix {
        val bits = BitMatrix(size)
        for (y in 0 until size) for (x in 0 until size) if (this[x, y]) bits.set(x, y)
        return bits
    }

    // ---- shape ------------------------------------------------------------------------------

    @Test
    fun sixtyTwoOctetsFitVersionFourSoTheMatrixIs33Square() {
        val matrix = encodeQrMatrix(payload())
        // Version 4 is 17 + 4 * 4 = 33 modules a side; 62 octets is exactly the byte-mode capacity of 4-M.
        assertEquals(PAIRING_PAYLOAD_SIZE, 62)
        assertEquals(33, matrix.size)
    }

    @Test
    fun matrixIsNonEmptyWithBothDarkAndLightModules() {
        val matrix = encodeQrMatrix(payload())
        var dark = 0
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) if (matrix[x, y]) dark++
        val total = matrix.size * matrix.size
        assertTrue(dark > total / 4 && dark < total * 3 / 4, "dark modules $dark of $total")
    }

    @Test
    fun threeFinderPatternsAreInTheCorners() {
        val matrix = encodeQrMatrix(payload())
        val last = matrix.size - 1
        // A finder pattern: dark 7x7 ring, light ring, dark 3x3 core.
        for ((originX, originY) in listOf(0 to 0, last - 6 to 0, 0 to last - 6)) {
            assertTrue(matrix[originX, originY], "outer ring corner at $originX,$originY")
            assertTrue(matrix[originX + 6, originY + 6], "outer ring opposite corner at $originX,$originY")
            assertTrue(!matrix[originX + 1, originY + 1], "light ring at $originX,$originY")
            assertTrue(matrix[originX + 3, originY + 3], "core at $originX,$originY")
        }
    }

    // ---- determinism -------------------------------------------------------------------------

    @Test
    fun sameOctetsGiveTheSameMatrix() {
        assertEquals(encodeQrMatrix(payload()), encodeQrMatrix(payload()))
    }

    @Test
    fun differentOctetsGiveADifferentMatrix() {
        assertNotEquals(encodeQrMatrix(payload(seedByte = 0x11)), encodeQrMatrix(payload(seedByte = 0x77)))
    }

    // ---- decode round trips ------------------------------------------------------------------

    @Test
    fun zxingDecodesTheMatrixBackToTheExactOctetsInOneByteSegmentAtLevelM() {
        val input = payload()
        val result = Decoder().decode(encodeQrMatrix(input).toBitMatrix())

        // Byte mode: the octets come back as a byte segment, not as numeric / alphanumeric text.
        assertEquals(1, result.byteSegments.size)
        assertContentEquals(input, result.byteSegments.single())
        assertEquals("M", result.ecLevel)
    }

    @Test
    fun everyOctetValueSurvivesTheRoundTrip() {
        // Raw octets, including the ones above 0x7F and the zero byte, must not be mangled by a charset.
        val input = ByteArray(PAIRING_PAYLOAD_SIZE) { (it * 7 + 0x80).toByte() }
        input[0] = 0x00
        input[1] = 0xFF.toByte()
        val result = Decoder().decode(encodeQrMatrix(input).toBitMatrix())
        assertContentEquals(input, result.byteSegments.single())
    }

    @Test
    fun aRenderedImageWithQuietZoneScansBackToTheOctets() {
        val input = payload()
        val matrix = encodeQrMatrix(input)
        val moduleSize = 6
        val quiet = 4
        val side = (matrix.size + 2 * quiet) * moduleSize
        val pixels = IntArray(side * side) { WHITE }
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (!matrix[x, y]) continue
            for (dy in 0 until moduleSize) for (dx in 0 until moduleSize) {
                pixels[((y + quiet) * moduleSize + dy) * side + (x + quiet) * moduleSize + dx] = BLACK
            }
        }

        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        val scanned = QRCodeReader().decode(bitmap)

        @Suppress("UNCHECKED_CAST")
        val segments = scanned.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        assertContentEquals(input, segments.single())
    }

    // ---- input validation --------------------------------------------------------------------

    @Test
    fun emptyPayloadIsRejected() {
        assertFailsWith<IllegalArgumentException> { encodeQrMatrix(ByteArray(0)) }
    }

    @Test
    fun aPayloadThatWouldNotBeEncodedInByteModeIsRejected() {
        // Digits only would be silently encoded in numeric mode; the kart needs byte mode, so refuse instead.
        assertFailsWith<IllegalArgumentException> { encodeQrMatrix("0123456789".encodeToByteArray()) }
    }

    @Test
    fun aPayloadTooLargeForTheCodeIsRejected() {
        assertFailsWith<IllegalArgumentException> { encodeQrMatrix(ByteArray(4000) { 0x00 }) }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
