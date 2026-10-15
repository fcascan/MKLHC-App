package com.mklhc.core.wifi_ap.crypto

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.wifi_ap.lp2p.MiniJson
import com.mklhc.core.wifi_ap.lp2p.readTestResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden tests for CCMP over QoS (802.11n) data frames, the shape the live RX path from the kart
 * actually carries.
 *
 * The vectors in `ccmp_qos_vectors.json` are produced by `tools/gen_ccmp_qos_vectors.py`, which
 * builds the 802.11 header, the AAD and the CCM nonce itself per IEEE Std 802.11 clause 12.5.3.3.3
 * and delegates only the CCM core to pyca/cryptography's AESCCM. That generator shares no code with
 * the hand-rolled [Ccmp], so these are an independent check of the AAD masking for QoS and +HTC
 * frames (Frame Control subtype/Order masking and the QoS Control TID masking in particular).
 *
 * Case 1 is a 3-address QoS data frame (TID 0); case 2 is a QoS data + HT Control frame (Order set,
 * four HT Control octets that are part of the header but NOT of the AAD, TID 6).
 */
class CcmpQosTest {

    private val primitives = CryptoPrimitives()
    private val cases = loadCases()

    @Test
    fun `aad nonce and ccmp header match the recorded QoS intermediates`() {
        for (case in cases) {
            val ccmp = Ccmp(primitives)
            assertEquals(case.aad.toHex(), ccmp.aad(case.header).toHex(), "${case.name}: AAD")
            assertEquals(case.nonce.toHex(), ccmp.nonce(case.header, case.pn).toHex(), "${case.name}: nonce")
            assertEquals(
                case.ccmpHeader.toHex(),
                ccmp.ccmpHeader(case.pn, case.keyId).toHex(),
                "${case.name}: CCMP header",
            )
        }
    }

    @Test
    fun `encrypt matches the independent QoS golden MPDU`() {
        for (case in cases) {
            val ccmp = Ccmp(primitives)
            val mpdu = ccmp.encrypt(case.tk, case.header, case.data, case.pn, case.keyId)
            assertEquals(case.mpduEncrypted.toHex(), mpdu.toHex(), "${case.name}: encrypted MPDU")
        }
    }

    @Test
    fun `decrypt recovers the QoS plaintext body`() {
        for (case in cases) {
            val ccmp = Ccmp(primitives)
            val recovered = ccmp.decrypt(case.tk, case.mpduEncrypted, case.keyId)
            assertEquals(case.data.toHex(), recovered.toHex(), "${case.name}: decrypted body")
        }
    }

    private class QosCase(
        val name: String,
        val tk: ByteArray,
        val header: ByteArray,
        val data: ByteArray,
        val pn: ByteArray,
        val keyId: Int,
        val aad: ByteArray,
        val nonce: ByteArray,
        val ccmpHeader: ByteArray,
        val mpduEncrypted: ByteArray,
    )

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun loadCases(): List<QosCase> {
            val root = MiniJson.parse(readTestResource("ccmp_qos_vectors.json")) as Map<String, Any?>
            val entries = root["cases"] as List<Any?>
            return entries.map { entry ->
                val o = entry as Map<String, Any?>
                QosCase(
                    name = o["name"] as String,
                    tk = (o["tk"] as String).hex(),
                    header = (o["header"] as String).hex(),
                    data = (o["data"] as String).hex(),
                    pn = (o["pn"] as String).hex(),
                    keyId = (o["keyId"] as Double).toInt(),
                    aad = (o["aad"] as String).hex(),
                    nonce = (o["nonce"] as String).hex(),
                    ccmpHeader = (o["ccmpHeader"] as String).hex(),
                    mpduEncrypted = (o["mpduEncrypted"] as String).hex(),
                )
            }
        }

        fun String.hex(): ByteArray {
            require(length % 2 == 0) { "Hex string must have an even length" }
            return ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        fun ByteArray.toHex(): String = joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
