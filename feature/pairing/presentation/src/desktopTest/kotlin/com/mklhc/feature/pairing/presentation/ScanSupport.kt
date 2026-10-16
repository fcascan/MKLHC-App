package com.mklhc.feature.pairing.presentation

import androidx.compose.ui.graphics.ImageBitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.mklhc.core.kart_protocol.pairing.encodePairingPayload

/**
 * Test support: scans a rendered image the way a camera app would, with ZXing's QR reader, and returns the raw
 * octets of the single byte-mode segment it carries. Failing to find or decode a code throws, which fails the test.
 */
internal fun ImageBitmap.scanQrOctets(): ByteArray {
    val pixels = IntArray(width * height)
    readPixels(pixels)
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
    val scanned = QRCodeReader().decode(bitmap)

    @Suppress("UNCHECKED_CAST")
    val segments = scanned.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
    return segments.single()
}

/** A realistic pairing payload (seed, SSID, channel, zero padding) for the rendering tests. */
internal fun samplePairingPayload(seedByte: Int = 0x21): ByteArray =
    encodePairingPayload(seed = ByteArray(16) { (seedByte + it * 13).toByte() }, pairingSsid = "kart-pair-01", channel = 6)
