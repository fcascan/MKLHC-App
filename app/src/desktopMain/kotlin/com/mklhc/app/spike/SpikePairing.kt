package com.mklhc.app.spike

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.kart_protocol.pairing.PAIRING_SEED_SIZE
import com.mklhc.core.kart_protocol.pairing.encodePairingPayload
import com.mklhc.core.wifi_ap.ap.ApConfig
import com.mklhc.core.wifi_ap.lp2p.Lp2pBeacon
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.feature.pairing.presentation.qr.QrMatrix
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Pairing network set-up for the radio hardware spike (Task 1.8).
 *
 * Original code. It only combines modules that already exist: the pairing payload layout comes from
 * :core:kart-protocol, the LP2P network from :core:wifi-ap (ported from OpenKart, BSD-3-Clause, see those files),
 * and the pairing SSID / PSK rules from the design (section 8.5).
 */

/**
 * One pairing network ready to be beaconed, together with the QR payload that describes it.
 *
 * Business logic: to pair, the kart scans a QR code carrying a random seed, the pairing SSID and the channel, and
 * then joins the network the app advertises with exactly those values. The spike builds the network and the payload
 * together ([createSpikePairing]) so they cannot disagree. The seed is secret key material: it exists only inside
 * [qrPayload] (what the QR code itself shows, so printing the payload exposes nothing the screen would not) and as
 * the keys derived from it inside [apConfig]. [toString] never prints either.
 *
 * @property apConfig the access point description: BSSID, SSID, channel, beacon interval and the LP2P network.
 * @param qrPayload the 62-octet pairing payload (seed, SSID, channel); a copy is kept.
 */
class SpikePairing(val apConfig: ApConfig, qrPayload: ByteArray) {
    private val payload: ByteArray = qrPayload.copyOf()

    /** The 62-octet pairing payload the QR code encodes, a copy. */
    val qrPayload: ByteArray get() = payload.copyOf()

    override fun toString(): String =
        "SpikePairing(ssid=${apConfig.ssid}, bssid=${apConfig.bssid}, channel=${apConfig.channel}, payload=${payload.size} bytes)"
}

/**
 * Creates a fresh pairing network: a random seed, the pairing SSID, a random locally administered BSSID, the LP2P
 * network keyed by `PSK = SHA-256(seed)` and the matching QR payload.
 *
 * Business logic: this mirrors the design's pairing network (section 8.5): SSID `mklhc-pair_<base64 of 12 random
 * octets>`, PSK = SHA-256 of a 16-octet random seed, and a purely random LP2P nonce (the temporary-network path of
 * [NonceTable]). All randomness comes from [primitives] (the platform secure RNG), so the seed, the SSID tail, the
 * BSSID and the LP2P random draws are unpredictable.
 *
 * @param primitives the crypto provider (SHA-256 and the secure random source).
 * @param nonceTable the pre-encrypted LP2P nonce table; the default loads the bundled resource.
 * @param channel 1, 6 or 11.
 * @param beaconIntervalTu the beacon interval in time units; a spike tunable.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun createSpikePairing(
    primitives: CryptoPrimitives,
    nonceTable: NonceTable,
    channel: Int,
    beaconIntervalTu: Int = ApConfig.DEFAULT_BEACON_INTERVAL_TU,
): SpikePairing {
    val seed = primitives.secureRandomBytes(PAIRING_SEED_SIZE)
    val ssid = PAIRING_SSID_PREFIX + Base64.Default.encode(primitives.secureRandomBytes(SSID_RANDOM_BYTES))
    val network = Lp2pBeacon(primitives).encryptNetwork(
        psk = primitives.sha256(seed),
        nonceTable = nonceTable,
        randomness = { count -> primitives.secureRandomBytes(count) },
        temporary = true,
    )
    val apConfig = ApConfig(
        bssid = randomBssid(primitives),
        ssid = ssid,
        channel = channel,
        beaconIntervalTu = beaconIntervalTu,
        network = network,
    )
    return SpikePairing(apConfig, encodePairingPayload(seed, ssid, channel))
}

/**
 * A random BSSID that is unicast (group bit clear) and locally administered (bit 1 of the first octet set); the
 * radio's hardware ACK engine needs a unicast BSSID.
 */
private fun randomBssid(primitives: CryptoPrimitives): MacAddress {
    val octets = primitives.secureRandomBytes(MacAddress.SIZE)
    octets[0] = ((octets[0].toInt() and GROUP_BIT_CLEAR_MASK) or LOCALLY_ADMINISTERED_BIT).toByte()
    return MacAddress.fromBytes(octets)
}

/**
 * Draws [matrix] as text for a terminal, two module rows per text line using the half-block characters.
 *
 * Business logic: the kart pairs by scanning the QR code with its camera, and the spike has no window, so the code
 * is drawn in the console. A terminal's text is light on a dark background, so by default the light modules (and the
 * quiet zone) are the painted blocks and the dark modules are the empty background, which gives a normal dark-on-light
 * code on a dark terminal. Pass `darkTerminal = false` on a light terminal to paint the dark modules instead.
 *
 * @param matrix the code without a quiet zone.
 * @param darkTerminal true for light text on a dark background (the common case).
 * @param quietZone the blank border, in modules, drawn around the code; the QR standard asks for 4.
 */
internal fun renderQrForConsole(matrix: QrMatrix, darkTerminal: Boolean = true, quietZone: Int = QR_QUIET_ZONE_MODULES): String {
    require(quietZone >= 0) { "the quiet zone cannot be negative: quietZone=$quietZone" }
    val total = matrix.size + 2 * quietZone

    // Whether the character cell half at (x, y) is drawn as a filled block. Outside the code is the quiet zone.
    fun painted(x: Int, y: Int): Boolean {
        val moduleX = x - quietZone
        val moduleY = y - quietZone
        val inside = moduleX in 0 until matrix.size && moduleY in 0 until matrix.size
        val dark = inside && matrix[moduleX, moduleY]
        return if (darkTerminal) !dark else dark
    }

    val text = StringBuilder()
    var y = 0
    while (y < total) {
        for (x in 0 until total) {
            val top = painted(x, y)
            val bottom = painted(x, y + 1)
            text.append(
                when {
                    top && bottom -> FULL_BLOCK
                    top -> UPPER_HALF_BLOCK
                    bottom -> LOWER_HALF_BLOCK
                    else -> ' '
                },
            )
        }
        text.append('\n')
        y += 2
    }
    return text.toString()
}

/**
 * Draws [matrix] as an SVG image: black modules on a white background, quiet zone included.
 *
 * Business logic: the console drawing needs a terminal that can print the half-block characters, and under Gradle on
 * Windows the console uses the ANSI code page, which prints `?` instead. So the spike also saves the code as an image
 * file next to the capture, to show to the kart from any browser or image viewer on the same computer: plain shapes,
 * no script and no network. The polarity is always dark on light, as the QR standard draws it. Each run of dark
 * modules in a row becomes one rectangle of a single path, in module units; the view box scales it to
 * [modulePixels] per module and `crispEdges` keeps the module edges sharp.
 *
 * @param matrix the code without a quiet zone.
 * @param quietZone the light border, in modules, drawn around the code; the QR standard asks for 4.
 * @param modulePixels the displayed size of one module, in pixels.
 */
internal fun renderQrAsSvg(
    matrix: QrMatrix,
    quietZone: Int = QR_QUIET_ZONE_MODULES,
    modulePixels: Int = SVG_MODULE_PIXELS,
): String {
    require(quietZone >= 0) { "the quiet zone cannot be negative: quietZone=$quietZone" }
    require(modulePixels > 0) { "a module must be at least one pixel: modulePixels=$modulePixels" }
    val total = matrix.size + 2 * quietZone
    val pixels = total * modulePixels

    val path = StringBuilder()
    for (y in 0 until matrix.size) {
        var x = 0
        while (x < matrix.size) {
            if (!matrix[x, y]) {
                x++
                continue
            }
            val start = x
            while (x < matrix.size && matrix[x, y]) x++
            val run = x - start
            path.append("M${start + quietZone} ${y + quietZone}h${run}v1h-${run}z")
        }
    }

    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$pixels\" height=\"$pixels\" ")
        append("viewBox=\"0 0 $total $total\" shape-rendering=\"crispEdges\">\n")
        append("<rect width=\"$total\" height=\"$total\" fill=\"#ffffff\"/>\n")
        append("<path fill=\"#000000\" d=\"$path\"/>\n")
        append("</svg>\n")
    }
}

// TODO(KartRadioAp): these duplicate the production pairing-SSID rule in KartSessionRepository (design 8.5). When the
// real AP is implemented, promote the prefix/size (and the PairingNetwork -> ApConfig derivation) to one shared place
// (next to encodePairingPayload) so the spike and the real session cannot drift.
private const val PAIRING_SSID_PREFIX = "mklhc-pair_"
private const val SSID_RANDOM_BYTES = 12
private const val GROUP_BIT_CLEAR_MASK = 0xFE
private const val LOCALLY_ADMINISTERED_BIT = 0x02
private const val QR_QUIET_ZONE_MODULES = 4
private const val SVG_MODULE_PIXELS = 10
private const val FULL_BLOCK = '\u2588'
private const val UPPER_HALF_BLOCK = '\u2580'
private const val LOWER_HALF_BLOCK = '\u2584'
