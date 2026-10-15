package com.mklhc.core.data.store

import app.cash.turbine.test
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.mklhc.core.data.CapturingLogWriter
import com.mklhc.core.data.capturingLogger
import com.mklhc.core.data.hex
import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.PairedKart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behaviour every [PairingStore] must have, run against each implementation by a concrete
 * subclass: first-run generation happens exactly once and is then stable, the paired kart and the
 * settings round-trip, observers see updates, returned arrays are copies, and nothing secret is
 * logged.
 */
abstract class PairingStoreContract {

    /** A fresh, empty store for the test; every call returns an independent installation. */
    protected abstract fun newStore(log: Logger): PairingStore

    protected fun newStore(): PairingStore = newStore(Logger.withTag("PairingStoreContract"))

    private fun kart(serial: String = "KART-1", seed: Int = 1) = PairedKart(serial, ByteArray(16) { (seed + it).toByte() })

    @Test
    fun masterSecretIsGeneratedOnFirstUseAndIsThenStable() = runTest {
        val store = newStore()
        val first = store.getMasterSecret()
        assertEquals(32, first.size)
        assertFalse(first.all { it == 0.toByte() }, "a generated secret is not all zeros")
        assertContentEquals(first, store.getMasterSecret())
        assertContentEquals(first, store.getMasterSecret())
    }

    @Test
    fun eachInstallationGetsItsOwnMasterSecret() = runTest {
        assertFalse(newStore().getMasterSecret().contentEquals(newStore().getMasterSecret()))
    }

    @Test
    fun concurrentFirstUseYieldsOneMasterSecret() = runTest {
        val store = newStore()
        val results = List(32) { async { store.getMasterSecret() } }.awaitAll()
        val first = results.first()
        results.forEach { assertContentEquals(first, it) }
        assertContentEquals(first, store.getMasterSecret())
    }

    @Test
    fun apBssidIsGeneratedOnceAsALocallyAdministeredUnicastAddress() = runTest {
        val store = newStore()
        val first = store.getApBssid()
        assertEquals(6, first.size)
        assertTrue(FirstRunGenerator.isValidBssid(first), "BSSID must be locally administered unicast")
        assertContentEquals(first, store.getApBssid())
    }

    @Test
    fun channelIsChosenOnceFromTheNonOverlappingChannels() = runTest {
        val store = newStore()
        val first = store.getChannel()
        assertTrue(first in PairingStore.ALLOWED_CHANNELS, "channel $first")
        assertEquals(first, store.getChannel())
    }

    @Test
    fun pairedKartStartsEmptyRoundTripsAndCanBeCleared() = runTest {
        val store = newStore()
        assertNull(store.getPairedKart())

        store.setPairedKart(kart("KART-1", seed = 1))
        assertEquals(kart("KART-1", seed = 1), store.getPairedKart())

        store.setPairedKart(kart("KART-2", seed = 9))
        assertEquals(kart("KART-2", seed = 9), store.getPairedKart())

        store.setPairedKart(null)
        assertNull(store.getPairedKart())
    }

    @Test
    fun pairingAKartDoesNotDisturbTheGeneratedIdentity() = runTest {
        val store = newStore()
        val secret = store.getMasterSecret()
        val bssid = store.getApBssid()
        val channel = store.getChannel()

        store.setPairedKart(kart())
        store.setPairedKart(null)

        assertContentEquals(secret, store.getMasterSecret())
        assertContentEquals(bssid, store.getApBssid())
        assertEquals(channel, store.getChannel())
    }

    @Test
    fun settingsDefaultThenRoundTrip() = runTest {
        val store = newStore()
        assertEquals(KartSettings.DEFAULT, store.getSettings())

        val changed = KartSettings(maxThrottlePercent = 80, steeringExpoPercent = 25, steeringTrimPercent = -7, shutdownOnExit = false)
        store.setSettings(changed)
        assertEquals(changed, store.getSettings())
    }

    @Test
    fun settingsObserverSeesTheCurrentValueAndEveryUpdate() = runTest {
        val store = newStore()
        store.observeSettings().test {
            assertEquals(KartSettings.DEFAULT, awaitItem())

            val first = KartSettings(maxThrottlePercent = 60)
            store.setSettings(first)
            assertEquals(first, awaitItem())

            val second = first.copy(shutdownOnExit = false)
            store.setSettings(second)
            assertEquals(second, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun returnedArraysAreCopies() = runTest {
        val store = newStore()
        val secret = store.getMasterSecret()
        val bssid = store.getApBssid()
        val original = secret.copyOf()
        val originalBssid = bssid.copyOf()

        secret.fill(0)
        bssid.fill(0)

        assertContentEquals(original, store.getMasterSecret())
        assertContentEquals(originalBssid, store.getApBssid())
    }

    @Test
    fun firstRunGenerationIsLoggedButNeverTheSecrets() = runTest {
        val writer = CapturingLogWriter()
        val store = newStore(capturingLogger(writer))

        val secret = store.getMasterSecret()
        val bssid = store.getApBssid()
        store.setPairedKart(kart())

        assertTrue(writer.logged(Severity.Info), "first-run generation is logged at info")
        val text = writer.allText()
        assertFalse(text.contains(hex(secret)), "the master secret must never be logged (hex)")
        assertFalse(text.contains(secret.contentToString()), "the master secret must never be logged (octets)")
        assertFalse(text.contains(hex(bssid)), "the BSSID bytes are not logged")
        assertFalse(text.contains(hex(kart().ident)), "the kart ident is not logged")
    }
}
