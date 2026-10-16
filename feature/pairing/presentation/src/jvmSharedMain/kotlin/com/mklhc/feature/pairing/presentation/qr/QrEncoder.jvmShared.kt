package com.mklhc.feature.pairing.presentation.qr

import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Mode
import com.google.zxing.qrcode.encoder.Encoder

// Uses ZXing (com.google.zxing:core, Apache License 2.0, https://github.com/zxing/zxing) as an unmodified
// library dependency; no ZXing source is copied into this repository.

/**
 * ZXing-backed implementation of [encodeQrMatrix], shared by the Android and desktop JVM targets.
 *
 * Business logic: the octets are handed to ZXing's low-level [Encoder] as an ISO-8859-1 string, which maps each
 * octet 0x00..0xFF to the character with the same code and back, so the encoder sees exactly the payload octets
 * and writes them as one byte-mode segment. Two details matter for the kart's scanner:
 * - No `CHARACTER_SET` hint is passed. ISO-8859-1 is ZXing's default for byte mode, and passing the hint makes it
 *   prepend an ECI header, which costs 12 bits and would move the 62-octet pairing payload from version 4 to
 *   version 5 (and give the scanner an ECI marker it does not expect).
 * - The error-correction level is fixed at M.
 * ZXing chooses the mode from the content on its own, so after encoding the result is checked to really be byte
 * mode; a payload that ZXing packed as numeric or alphanumeric is rejected instead of being returned.
 */
actual fun encodeQrMatrix(payload: ByteArray): QrMatrix {
    require(payload.isNotEmpty()) { "QR payload must not be empty" }

    val content = String(payload, Charsets.ISO_8859_1)
    val code = try {
        Encoder.encode(content, ErrorCorrectionLevel.M)
    } catch (tooBig: WriterException) {
        throw IllegalArgumentException("a ${payload.size}-octet payload does not fit in a QR code at level M", tooBig)
    }
    require(code.mode == Mode.BYTE) {
        "the ${payload.size}-octet payload would be encoded in ${code.mode} mode, the kart needs byte mode"
    }

    val matrix = code.matrix
    val size = matrix.width
    val modules = BooleanArray(size * size) { index -> matrix.get(index % size, index / size).toInt() == DARK }
    return QrMatrix(size = size, modules = modules)
}

private const val DARK = 1
