package com.mklhc.core.data.identity

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.hex
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [deriveKartIdentity] against the golden vectors generated from the reference implementation: the
 * pre-shared key, RCD identity and RCD master key must match byte for byte, and the SSID must be the
 * app's own prefix followed by the reference's base64 tail.
 */
class KartIdentityTest {

    private val crypto = CryptoPrimitives()
    private val vectors = IdentityVectors.load().vectors

    @Test
    fun vectorFileCoversStringAndRawSecrets() {
        assertTrue(vectors.size >= 3, "expected at least three vectors, got ${vectors.size}")
        assertTrue(vectors.any { it.secretText == null }, "a raw-octet secret vector is required")
        assertTrue(vectors.any { it.secretText != null }, "a string secret vector is required")
    }

    @Test
    fun pskIdentAndMasterKeyMatchTheReferenceByteForByte() {
        for (v in vectors) {
            val identity = deriveKartIdentity(v.secret, crypto)
            assertContentEquals(v.psk, identity.gamePsk, "psk for '${v.name}'")
            assertContentEquals(v.rcdIdent, identity.rcdIdent, "rcd ident for '${v.name}'")
            assertContentEquals(v.rcdMasterKey, identity.rcdMasterKey, "rcd master key for '${v.name}'")
        }
    }

    @Test
    fun derivedLengthsAreThoseOfTheReference() {
        val identity = deriveKartIdentity(vectors.first().secret, crypto)
        assertEquals(0x20, identity.gamePsk.size)
        assertEquals(0x10, identity.rcdIdent.size)
        assertEquals(0x40, identity.rcdMasterKey.size)
    }

    @Test
    fun ssidIsTheMklhcPrefixPlusTheReferenceBase64Tail() {
        for (v in vectors) {
            val ssid = deriveKartIdentity(v.secret, crypto).gameSsid
            assertTrue(ssid.startsWith("mklhc_"), "ssid '$ssid' must carry the app prefix")
            assertEquals(v.ssidTailBase64, ssid.removePrefix("mklhc_"), "base64 tail for '${v.name}'")
            // The tail really is the base64 of the 12 derived octets (guards the vector and the encoder).
            assertEquals(v.ssidTailBase64, Base64.Default.encode(v.ssidRandom), "vector tail for '${v.name}'")
            assertEquals(GAME_SSID_PREFIX + v.ssidTailBase64, ssid)
        }
    }

    @Test
    fun ssidFitsTheKartSsidField() {
        // The group-info field holds fewer than 0x20 octets (see FujiPairingClient.setGroupInfo).
        val ssid = deriveKartIdentity(vectors.first().secret, crypto).gameSsid
        assertTrue(ssid.encodeToByteArray().size < 0x20, "ssid '$ssid' is too long")
    }

    @Test
    fun stringSecretIsDerivedFromItsUtf8Bytes() {
        val stringVectors = vectors.filter { it.secretText != null }
        for (v in stringVectors) {
            val fromText = deriveKartIdentity(v.secretText!!, crypto)
            val fromBytes = deriveKartIdentity(v.secret, crypto)
            assertContentEquals(v.secretText.encodeToByteArray(), v.secret, "vector '${v.name}' is consistent")
            assertEquals(fromBytes, fromText)
            assertContentEquals(v.psk, fromText.gamePsk)
        }
    }

    @Test
    fun emptySecretIsRejected() {
        assertFailsWith<IllegalArgumentException> { deriveKartIdentity(ByteArray(0), crypto) }
        assertFailsWith<IllegalArgumentException> { deriveKartIdentity("", crypto) }
    }

    @Test
    fun derivationIsDeterministicAndSecretDependent() {
        val a = vectors[0].secret
        val b = vectors[1].secret
        assertEquals(deriveKartIdentity(a, crypto), deriveKartIdentity(a, crypto))
        assertNotEquals(deriveKartIdentity(a, crypto), deriveKartIdentity(b, crypto))
    }

    @Test
    fun eachDerivedValueUsesItsOwnLabel() {
        // psk / ident / master key come from different HMAC messages, so no one is a prefix of another.
        val identity = deriveKartIdentity(vectors.first().secret, crypto)
        assertFalse(identity.rcdMasterKey.copyOf(0x20).contentEquals(identity.gamePsk))
        assertFalse(identity.rcdMasterKey.copyOf(0x10).contentEquals(identity.rcdIdent))
        assertFalse(identity.gamePsk.copyOf(0x10).contentEquals(identity.rcdIdent))
    }

    @Test
    fun identityRejectsValuesOfTheWrongSize() {
        val ok = deriveKartIdentity(vectors.first().secret, crypto)
        assertFailsWith<IllegalArgumentException> { KartIdentity(ok.gameSsid, ByteArray(31), ok.rcdIdent, ok.rcdMasterKey) }
        assertFailsWith<IllegalArgumentException> { KartIdentity(ok.gameSsid, ok.gamePsk, ByteArray(15), ok.rcdMasterKey) }
        assertFailsWith<IllegalArgumentException> { KartIdentity(ok.gameSsid, ok.gamePsk, ok.rcdIdent, ByteArray(63)) }
        assertFailsWith<IllegalArgumentException> { KartIdentity("", ok.gamePsk, ok.rcdIdent, ok.rcdMasterKey) }
    }

    @Test
    fun toStringNeverRevealsKeyMaterial() {
        val identity = deriveKartIdentity(vectors.first().secret, crypto)
        val text = identity.toString()
        assertTrue(text.contains(identity.gameSsid), "the ssid is public and may be shown")
        assertFalse(text.contains(hex(identity.gamePsk)))
        assertFalse(text.contains(hex(identity.rcdIdent)))
        assertFalse(text.contains(hex(identity.rcdMasterKey)))
        assertFalse(text.contains(identity.gamePsk.contentToString()))
    }

    @Test
    fun constructorCopiesItsArrays() {
        val ok = deriveKartIdentity(vectors.first().secret, crypto)
        val psk = ok.gamePsk.copyOf()
        val identity = KartIdentity(ok.gameSsid, psk, ok.rcdIdent, ok.rcdMasterKey)
        psk[0] = (psk[0] + 1).toByte()
        assertContentEquals(ok.gamePsk, identity.gamePsk)
    }

    @Test
    fun rcdServerInfoCarriesTheDerivedIdentAndMasterKeyUnderTheAppName() {
        val v = vectors.first()
        val info = deriveKartIdentity(v.secret, crypto).toRcdServerInfo(crypto)
        assertEquals("MKLHC", info.name)
        assertEquals(RCD_SERVER_NAME, info.name)
        assertContentEquals(v.rcdIdent, info.ident)
        assertContentEquals(v.rcdMasterKey, info.masterKey)
    }
}
