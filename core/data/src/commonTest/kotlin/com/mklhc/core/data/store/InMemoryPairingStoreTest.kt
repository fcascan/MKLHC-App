package com.mklhc.core.data.store

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.PairedKart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs the [PairingStoreContract] against [InMemoryPairingStore] and checks its seeding. */
class InMemoryPairingStoreTest : PairingStoreContract() {

    override fun newStore(log: Logger): PairingStore = InMemoryPairingStore(log = log)

    @Test
    fun seededValuesAreReturnedInsteadOfGeneratedOnes() = runTest {
        val secret = ByteArray(32) { it.toByte() }
        val bssid = byteArrayOf(0x02, 1, 2, 3, 4, 5)
        val kart = PairedKart("KART-7", ByteArray(16) { 7 })
        val settings = KartSettings(maxThrottlePercent = 90)

        val store = InMemoryPairingStore(
            crypto = CryptoPrimitives(),
            masterSecret = secret,
            apBssid = bssid,
            channel = 11,
            pairedKart = kart,
            settings = settings,
        )

        assertContentEquals(secret, store.getMasterSecret())
        assertContentEquals(bssid, store.getApBssid())
        assertEquals(11, store.getChannel())
        assertEquals(kart, store.getPairedKart())
        assertEquals(settings, store.getSettings())
    }

    @Test
    fun seedsAreValidated() {
        assertFailsWith<IllegalArgumentException> { InMemoryPairingStore(masterSecret = ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { InMemoryPairingStore(apBssid = byteArrayOf(0x03, 1, 2, 3, 4, 5)) }
        assertFailsWith<IllegalArgumentException> { InMemoryPairingStore(apBssid = byteArrayOf(0x02, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { InMemoryPairingStore(channel = 7) }
    }
}
