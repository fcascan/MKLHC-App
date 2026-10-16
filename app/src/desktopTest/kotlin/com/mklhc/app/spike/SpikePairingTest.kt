package com.mklhc.app.spike

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.kart_protocol.pairing.PAIRING_PAYLOAD_SIZE
import com.mklhc.core.kart_protocol.pairing.PAIRING_SEED_SIZE
import com.mklhc.core.wifi_ap.lp2p.Lp2pKeys
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.feature.pairing.presentation.qr.QrMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [createSpikePairing] and [renderQrForConsole]: the pairing network the spike beacons must be exactly the one its
 * QR payload describes (seed, SSID, channel, and a CCMP key derived from `SHA-256(seed)`), and the console QR must
 * draw the module grid with the half-block characters in the right polarity.
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
}
