package com.mklhc.feature.pairing.presentation.qr

/**
 * Encodes raw octets as a QR code in byte mode at error-correction level M.
 *
 * Business logic: the kart is paired by scanning a QR code that carries the 62 raw octets of the pairing payload
 * (a 16-octet seed, the pairing SSID and the channel). The kart's scanner expects exactly that: a single
 * byte-mode segment holding the octets as they are, at error-correction level M, with no extended-channel
 * (ECI) header. The 62 octets fill the byte capacity of a version 4-M code exactly (33 by 33 modules), and an ECI
 * header would push them over into version 5, so the encoder must not add one. Treating the payload as text, in
 * any charset, would corrupt octets above 0x7F, which is why the octets are handed over as they are.
 *
 * A payload that would not come out in byte mode (for example only digits, which QR would pack as numeric) is
 * refused rather than silently encoded in another mode, since the kart could not read it.
 *
 * The implementation lives in the `jvmShared` source set, backed by the ZXing encoder (a JVM library), which is
 * available on both the Android and the desktop target; only the finished matrix reaches common code.
 *
 * @param payload the octets to encode; must not be empty.
 * @return the module matrix without a quiet zone.
 * @throws IllegalArgumentException if [payload] is empty, would not be encoded in byte mode or does not fit in
 *   a QR code at level M.
 */
expect fun encodeQrMatrix(payload: ByteArray): QrMatrix
