package com.mklhc.app.spike

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.kart_protocol.pairing.PAIRING_PAYLOAD_SIZE
import com.mklhc.core.kart_protocol.pairing.PAIRING_SEED_SIZE
import com.mklhc.core.kart_protocol.pairing.encodePairingPayload
import com.mklhc.core.wifi_ap.lp2p.Lp2pKeys
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.feature.pairing.presentation.qr.QrMatrix
import com.mklhc.feature.pairing.presentation.qr.encodeQrMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [createSpikePairing], [renderQrForConsole] and [renderQrAsSvg]: the pairing network the spike beacons must be exactly
 * the one its QR payload describes (seed, SSID, channel, and a CCMP key derived from `SHA-256(seed)`), the console QR
 * must draw the module grid with the half-block characters in the right polarity, and the SVG image must scan back to
 * the payload.
 */
class SpikePairingTest {

    private val primitives = CryptoPrimitives()

    // An all-zero table is enough: the nonce bytes are opaque to the pairing code. randomInt = 0 pins the index.
    private fun nonceTable() = NonceTable(table = ByteArray(0x10000 * 16), nowEpochSeconds = { 0L }, randomInt = { 0 })

    private fun pairing(channel: Int = 6) = createSpikePairing(primitives, nonceTable(), channel)

    // ---- Pairing network ----------------------------------------------------------------------

    @Test
    fun thePayloadDescribesTheNetworkTheApBeacons() {
        val pairing = pairing(channel = 11)
        val payload = pairing.qrPayload
        val config = pairing.apConfig

        assertEquals(PAIRING_PAYLOAD_SIZE, payload.size)
        // 0x10..0x2F: the SSID, zero padded; 0x30..0x31: the channel, little-endian.
        val ssidField = payload.copyOfRange(0x10, 0x30)
        assertEquals(config.ssid, ssidField.takeWhile { it != 0.toByte() }.toByteArray().decodeToString())
        assertEquals(11, (payload[0x30].toInt() and 0xFF) or ((payload[0x31].toInt() and 0xFF) shl 8))
        assertEquals(11, config.channel)
        assertTrue(config.ssid.startsWith("mklhc-pair_"), config.ssid)
        assertEquals("mklhc-pair_".length + 16, config.ssid.length, "12 random octets are 16 base64 characters")
    }

    @Test
    fun theCcmpKeyIsDerivedFromTheSha256OfTheSeedInThePayload() {
        val pairing = pairing()
        val seed = pairing.qrPayload.copyOfRange(0, PAIRING_SEED_SIZE)

        // Recompute independently: PSK = SHA-256(seed); the all-zero table and randomInt = 0 select nonce index 0.
        val table = nonceTable()
        val expectedKey = Lp2pKeys(primitives).deriveCcmpKey(table.getPlaintextNonce(0), primitives.sha256(seed))

        assertContentEquals(expectedKey, pairing.apConfig.network.ccmpKey)
        assertTrue(pairing.apConfig.network.vendorElements.isNotEmpty(), "the two LP2P vendor elements are present")
    }

    @Test
    fun theBssidIsUnicastAndLocallyAdministered() {
        repeat(20) {
            val first = pairing().apConfig.bssid.toByteArray()[0].toInt() and 0xFF
            assertEquals(0, first and 0x01, "unicast")
            assertEquals(0x02, first and 0x02, "locally administered")
        }
    }

    @Test
    fun everyPairingIsFreshAndRandom() {
        val a = pairing()
        val b = pairing()

        assertFalse(a.qrPayload.contentEquals(b.qrPayload), "a new seed each time")
        assertNotEquals(a.apConfig.ssid, b.apConfig.ssid)
        assertFalse(a.apConfig.network.ccmpKey.contentEquals(b.apConfig.network.ccmpKey))
    }

    @Test
    fun theBeaconIntervalAndChannelAreCarriedAndValidated() {
        val pairing = createSpikePairing(primitives, nonceTable(), channel = 1, beaconIntervalTu = 50)

        assertEquals(50, pairing.apConfig.beaconIntervalTu)
        assertEquals(1, pairing.apConfig.channel)
        assertFailsWith<IllegalArgumentException> { createSpikePairing(primitives, nonceTable(), channel = 7) }
    }

    @Test
    fun theStringFormNeverShowsTheSeedOrTheKey() {
        val pairing = pairing()
        val text = pairing.toString()

        assertFalse(pairing.qrPayload.copyOfRange(0, PAIRING_SEED_SIZE).toHexString() in text.lowercase())
        assertFalse(pairing.apConfig.network.ccmpKey.toHexString() in text.lowercase())
        assertTrue(text.contains(pairing.apConfig.ssid))
    }

    @Test
    fun thePayloadAccessorReturnsACopy() {
        val pairing = pairing()

        pairing.qrPayload.fill(0)

        assertFalse(pairing.qrPayload.all { it == 0.toByte() })
    }

    // ---- Console QR ----------------------------------------------------------------------------

    // Top row: dark, light. Bottom row: light, dark.
    private val checker = QrMatrix(2, booleanArrayOf(true, false, false, true))

    @Test
    fun aLightTerminalPaintsTheDarkModulesWithHalfBlocks() {
        val text = renderQrForConsole(checker, darkTerminal = false, quietZone = 0)

        // Column 0: top dark, bottom light -> upper half. Column 1: top light, bottom dark -> lower half.
        assertEquals("\u2580\u2584\n", text)
    }

    @Test
    fun aDarkTerminalPaintsTheLightModulesSoTheCodeReadsDarkOnLight() {
        val text = renderQrForConsole(checker, darkTerminal = true, quietZone = 0)

        assertEquals("\u2584\u2580\n", text)
    }

    @Test
    fun theQuietZoneIsDrawnAsLightAndAnOddHeightEndsOnAHalfLine() {
        // 1x1 dark module with a 1-module quiet zone: a 3x3 grid, so two text lines (the last has only a top row).
        val single = QrMatrix(1, booleanArrayOf(true))

        val onDark = renderQrForConsole(single, darkTerminal = true, quietZone = 1)
        val onLight = renderQrForConsole(single, darkTerminal = false, quietZone = 1)

        // Dark terminal: light is painted. Line 1 = rows 0 and 1: row 0 all light; row 1 is light, dark, light.
        // Line 2 = row 2 (all light) over the edge below the grid, which is the quiet zone, so light too.
        assertEquals("\u2588\u2580\u2588\n\u2588\u2588\u2588\n", onDark)
        // Light terminal: dark is painted. The single dark module is in row 1, so a lower half in the middle of
        // line 1; line 2 is all quiet zone, so empty.
        assertEquals(" \u2584 \n   \n", onLight)
    }

    @Test
    fun aRealPayloadMatrixHasTheExpectedLineCount() {
        val matrix = QrMatrix(33, BooleanArray(33 * 33))

        val lines = renderQrForConsole(matrix).trimEnd('\n').split('\n')

        // 33 modules + 2 * 4 quiet zone = 41 rows, two per text line.
        assertEquals(21, lines.size)
        assertTrue(lines.all { it.length == 41 })
    }

    // ---- SVG QR --------------------------------------------------------------------------------

    @Test
    fun theSvgDrawsEachRunOfDarkModulesAsOneRectangleOffsetByTheQuietZone() {
        // Row 0: dark, dark, light. Row 1: all light. Row 2: dark, light, dark.
        val matrix = QrMatrix(3, booleanArrayOf(true, true, false, false, false, false, true, false, true))

        val svg = renderQrAsSvg(matrix, quietZone = 1, modulePixels = 5)

        // 3 modules + 2 * 1 quiet zone = 5 modules a side, 25 pixels.
        assertTrue(svg.contains("width=\"25\" height=\"25\" viewBox=\"0 0 5 5\""), svg)
        assertTrue(svg.contains("<rect width=\"5\" height=\"5\" fill=\"#ffffff\"/>"), svg)
        assertTrue(svg.contains("<path fill=\"#000000\" d=\"M1 1h2v1h-2zM1 3h1v1h-1zM3 3h1v1h-1z\"/>"), svg)
    }

    @Test
    fun theSvgOfARealPayloadScansBackToThePayload() {
        // A fixed payload with the real layout, so a failing scan can be reproduced.
        val payload = encodePairingPayload(ByteArray(PAIRING_SEED_SIZE) { (0x5A + it * 31).toByte() }, "mklhc-pair_AbCdEfGhIjKlMnOp", 6)
        val svg = renderQrAsSvg(encodeQrMatrix(payload))

        // 33 modules + 2 * 4 quiet zone = 41 modules at 10 pixels: one white square under one black path.
        assertEquals(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <svg xmlns="http://www.w3.org/2000/svg" width="410" height="410" viewBox="0 0 41 41" shape-rendering="crispEdges">
            <rect width="41" height="41" fill="#ffffff"/>
            <path fill="#000000" d=""/>
            </svg>
            """.trimIndent() + "\n",
            svg.replace(Regex(" d=\"[^\"]*\""), " d=\"\""),
        )
        val scanned = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(rasterize(svg, pixelsPerModule = 4))))

        @Suppress("UNCHECKED_CAST")
        val segments = scanned.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        assertContentEquals(payload, segments.single())
    }

    @Test
    fun theSvgRejectsANegativeQuietZoneOrAnEmptyModule() {
        assertFailsWith<IllegalArgumentException> { renderQrAsSvg(checker, quietZone = -1) }
        assertFailsWith<IllegalArgumentException> { renderQrAsSvg(checker, modulePixels = 0) }
    }

    /**
     * Paints the SVG the way a viewer would, from the file text alone: the view box gives the side in modules, the
     * background is white (the caller checks the rest of the document), and every `M x y h w v1 h-w z` rectangle of
     * the single path is black.
     */
    private fun rasterize(svg: String, pixelsPerModule: Int): RGBLuminanceSource {
        val modules = checkNotNull(Regex("viewBox=\"0 0 (\\d+) \\1\"").find(svg)) { "no square view box in:\n$svg" }
            .groupValues[1].toInt()
        val side = modules * pixelsPerModule
        val pixels = IntArray(side * side) { WHITE }
        assertEquals(1, Regex("<path ").findAll(svg).count(), "a single path")
        val path = checkNotNull(Regex("<path fill=\"#000000\" d=\"([^\"]*)\"/>").find(svg)) { "no path in:\n$svg" }
            .groupValues[1]
        val rectangles = Regex("M(\\d+) (\\d+)h(\\d+)v1h-\\3z").findAll(path).toList()
        assertEquals(path, rectangles.joinToString("") { it.value }, "the path holds only module-row rectangles")
        for (rectangle in rectangles) {
            val (x, y, width) = rectangle.destructured.let { (left, top, run) -> Triple(left.toInt(), top.toInt(), run.toInt()) }
            assertTrue(x + width <= modules && y < modules, "rectangle ${rectangle.value} is outside the $modules-module view box")
            for (py in y * pixelsPerModule until (y + 1) * pixelsPerModule) {
                for (px in x * pixelsPerModule until (x + width) * pixelsPerModule) {
                    pixels[py * side + px] = BLACK
                }
            }
        }
        return RGBLuminanceSource(side, side, pixels)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
