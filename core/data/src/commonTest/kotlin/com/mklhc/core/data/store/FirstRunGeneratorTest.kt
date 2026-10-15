package com.mklhc.core.data.store

import com.mklhc.core.crypto.CryptoPrimitives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [FirstRunGenerator]: the shapes of the values created on first run, with a pinned random source. */
class FirstRunGeneratorTest {

    private fun generatorReturning(bytes: ByteArray) = FirstRunGenerator { count ->
        ByteArray(count) { bytes[it % bytes.size] }
    }

    @Test
    fun masterSecretIsThirtyTwoRandomOctets() {
        val secret = generatorReturning(byteArrayOf(0x11, 0x22)).masterSecret()
        assertEquals(PairingStore.MASTER_SECRET_SIZE, secret.size)
        assertEquals(32, secret.size)
        assertContentEquals(ByteArray(32) { if (it % 2 == 0) 0x11 else 0x22 }, secret)
    }

    @Test
    fun bssidIsMadeLocallyAdministeredAndUnicastWhateverTheRandomness() {
        for (first in listOf(0x00, 0x01, 0x02, 0x03, 0xFC, 0xFD, 0xFE, 0xFF, 0x55, 0xAB)) {
            val bssid = generatorReturning(byteArrayOf(first.toByte(), 1, 2, 3, 4, 5)).apBssid()
            assertEquals(6, bssid.size)
            val firstOctet = bssid[0].toInt() and 0xFF
            assertEquals(0, firstOctet and 0x01, "group bit must be clear (unicast) for input 0x${first.toString(16)}")
            assertEquals(0x02, firstOctet and 0x02, "local bit must be set for input 0x${first.toString(16)}")
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), bssid.copyOfRange(1, 6), "remaining octets are untouched")
            assertTrue(FirstRunGenerator.isValidBssid(bssid))
        }
    }

    @Test
    fun bssidValidationRejectsWrongSizeMulticastAndGloballyAdministered() {
        assertTrue(FirstRunGenerator.isValidBssid(byteArrayOf(0x02, 1, 2, 3, 4, 5)))
        assertFalse(FirstRunGenerator.isValidBssid(byteArrayOf(0x02, 1, 2, 3, 4)))
        assertFalse(FirstRunGenerator.isValidBssid(byteArrayOf(0x03, 1, 2, 3, 4, 5)), "multicast")
        assertFalse(FirstRunGenerator.isValidBssid(byteArrayOf(0x00, 1, 2, 3, 4, 5)), "globally administered")
    }

    @Test
    fun channelIsOneOfTheNonOverlappingChannels() {
        assertEquals(listOf(1, 6, 11), PairingStore.ALLOWED_CHANNELS)
        assertEquals(1, generatorReturning(byteArrayOf(0)).channel())
        assertEquals(6, generatorReturning(byteArrayOf(1)).channel())
        assertEquals(11, generatorReturning(byteArrayOf(2)).channel())
        assertEquals(1, generatorReturning(byteArrayOf(3)).channel())
        assertEquals(1, generatorReturning(byteArrayOf(0xFF.toByte())).channel(), "an unsigned octet is used, never a negative index")
    }

    @Test
    fun realRandomnessAlwaysYieldsValidValues() {
        val crypto = CryptoPrimitives()
        val generator = FirstRunGenerator(crypto::secureRandomBytes)
        repeat(200) {
            assertTrue(FirstRunGenerator.isValidBssid(generator.apBssid()))
            assertTrue(generator.channel() in PairingStore.ALLOWED_CHANNELS)
        }
        assertFalse(generator.masterSecret().contentEquals(generator.masterSecret()), "two secrets must differ")
    }
}
