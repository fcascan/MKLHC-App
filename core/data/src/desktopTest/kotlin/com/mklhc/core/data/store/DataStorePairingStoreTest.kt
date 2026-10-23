package com.mklhc.core.data.store

import androidx.datastore.preferences.core.edit
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.CapturingLogWriter
import com.mklhc.core.data.capturingLogger
import com.mklhc.core.data.hex
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.PairedKart
import com.mklhc.core.domain.UsbId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the [PairingStoreContract] against [DataStorePairingStore] over a real DataStore file in a
 * temporary directory, and checks what only a persistent store has: values surviving a reopen, bad
 * stored values being repaired with a warning, and a corrupt file being replaced.
 *
 * Desktop target only: the Android flavour of DataStore replaces its file with `File.renameTo`, which
 * fails on a Windows host when the target exists, so its host-JVM unit tests cannot rewrite the file
 * there (on a device, which is Linux, it works). The desktop flavour uses an atomic move and is the
 * one exercised on every host.
 */
class DataStorePairingStoreTest : PairingStoreContract() {

    private val directories = mutableListOf<File>()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun cleanUp() {
        scopes.forEach { it.coroutineContext[Job]?.let { job -> job.cancel() } }
        directories.forEach { it.deleteRecursively() }
    }

    private fun newFile(): File {
        val directory = Files.createTempDirectory("mklhc-pairing-test").toFile()
        directories += directory
        return File(directory, "pairing.preferences_pb")
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }

    private fun open(file: File, log: Logger, scope: CoroutineScope = newScope()) =
        createPairingDataStore(file.path, scope, log)

    override fun newStore(log: Logger): PairingStore =
        DataStorePairingStore(open(newFile(), log), CryptoPrimitives(), log)

    @Test
    fun everythingSurvivesReopeningTheFile() = runTest {
        val file = newFile()
        val log = Logger.withTag("DataStorePairingStoreTest")
        val firstScope = newScope()
        val first = DataStorePairingStore(open(file, log, firstScope), CryptoPrimitives(), log)

        val secret = first.getMasterSecret()
        val bssid = first.getApBssid()
        val channel = first.getChannel()
        val kart = PairedKart("KART-9", ByteArray(16) { (it * 3).toByte() })
        val settings = KartSettings(maxThrottlePercent = 77, steeringExpoPercent = 12, steeringTrimPercent = 4, shutdownOnExit = false)
        first.setPairedKart(kart)
        first.setSettings(settings)

        // A new process opens the same file: the first DataStore must release it before a second may use it.
        firstScope.coroutineContext[Job]!!.cancelAndJoin()
        val second = DataStorePairingStore(open(file, log), CryptoPrimitives(), log)

        assertContentEquals(secret, second.getMasterSecret())
        assertContentEquals(bssid, second.getApBssid())
        assertEquals(channel, second.getChannel())
        assertEquals(kart, second.getPairedKart())
        assertEquals(settings, second.getSettings())
    }

    @Test
    fun clearingThePairedKartSurvivesReopening() = runTest {
        val file = newFile()
        val log = Logger.withTag("DataStorePairingStoreTest")
        val firstScope = newScope()
        val first = DataStorePairingStore(open(file, log, firstScope), CryptoPrimitives(), log)
        first.setPairedKart(PairedKart("KART-9", ByteArray(16)))
        first.setPairedKart(null)

        firstScope.coroutineContext[Job]!!.cancelAndJoin()
        val second = DataStorePairingStore(open(file, log), CryptoPrimitives(), log)
        assertNull(second.getPairedKart())
    }

    @Test
    fun invalidStoredIdentityValuesAreRegeneratedWithAWarning() = runTest {
        val writer = CapturingLogWriter()
        val log = capturingLogger(writer)
        val dataStore = open(newFile(), log)
        dataStore.edit {
            it[PairingPreferenceKeys.MASTER_SECRET] = ByteArray(5) { 1 }
            it[PairingPreferenceKeys.AP_BSSID] = byteArrayOf(0x03, 1, 2, 3, 4, 5)
            it[PairingPreferenceKeys.CHANNEL] = 7
        }
        val store = DataStorePairingStore(dataStore, CryptoPrimitives(), log)

        assertEquals(32, store.getMasterSecret().size)
        assertTrue(FirstRunGenerator.isValidBssid(store.getApBssid()))
        assertTrue(store.getChannel() in PairingStore.ALLOWED_CHANNELS)
        assertTrue(writer.logged(Severity.Warn) || writer.logged(Severity.Error), "repairing invalid data must be logged")

        // The repaired values are persisted, not regenerated on every read.
        val secret = store.getMasterSecret()
        assertContentEquals(secret, store.getMasterSecret())
        assertFalse(writer.allText().contains(hex(secret)), "the secret must not be logged")
    }

    @Test
    fun outOfRangeStoredSettingsAreClampedWithAWarning() = runTest {
        val writer = CapturingLogWriter()
        val log = capturingLogger(writer)
        val dataStore = open(newFile(), log)
        dataStore.edit {
            it[PairingPreferenceKeys.MAX_THROTTLE_PERCENT] = 500
            it[PairingPreferenceKeys.STEERING_EXPO_PERCENT] = -3
            it[PairingPreferenceKeys.STEERING_TRIM_PERCENT] = 99
            it[PairingPreferenceKeys.SHUTDOWN_ON_EXIT] = false
        }
        val store = DataStorePairingStore(dataStore, CryptoPrimitives(), log)

        assertEquals(KartSettings(maxThrottlePercent = 100, steeringExpoPercent = 0, steeringTrimPercent = 50, shutdownOnExit = false), store.getSettings())
        assertTrue(writer.logged(Severity.Warn), "clamping stored settings must be logged")
    }

    @Test
    fun theAdapterSelectionSurvivesReopeningTheFile() = runTest {
        val file = newFile()
        val log = Logger.withTag("DataStorePairingStoreTest")
        val firstScope = newScope()
        val first = DataStorePairingStore(open(file, log, firstScope), CryptoPrimitives(), log)
        val manual = AdapterSelection.Manual(UsbId(0x2357, 0x012D))
        first.setAdapterSelection(manual)

        firstScope.coroutineContext[Job]!!.cancelAndJoin()
        val second = DataStorePairingStore(open(file, log), CryptoPrimitives(), log)
        assertEquals(manual, second.getAdapterSelection())

        second.setAdapterSelection(AdapterSelection.Automatic)
        assertEquals(AdapterSelection.Automatic, second.getAdapterSelection())
    }

    @Test
    fun anUnreadableStoredAdapterSelectionReadsAsAutomaticWithAWarning() = runTest {
        val badValues = listOf("", "manual", "2357", "2357:012", "zzzz:yyyy", "2357:012d:1", "0x2357:0x012d", " 2357:012d")
        badValues.forEach { bad ->
            val writer = CapturingLogWriter()
            val log = capturingLogger(writer)
            val dataStore = open(newFile(), log)
            dataStore.edit { it[PairingPreferenceKeys.ADAPTER_SELECTION] = bad }
            val store = DataStorePairingStore(dataStore, CryptoPrimitives(), log)

            assertEquals(AdapterSelection.Automatic, store.getAdapterSelection(), "stored='$bad'")
            assertTrue(writer.logged(Severity.Warn), "an unreadable adapter selection must be logged -> stored='$bad'")
        }
    }

    @Test
    fun anUnreadableStoredAdapterSelectionIsReportedOnceNotOnEveryUnrelatedEdit() = runTest {
        val writer = CapturingLogWriter()
        val log = capturingLogger(writer)
        val dataStore = open(newFile(), log)
        dataStore.edit { it[PairingPreferenceKeys.ADAPTER_SELECTION] = "manual" }
        val store = DataStorePairingStore(dataStore, CryptoPrimitives(), log)
        val seen = Channel<AdapterSelection>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) { store.observeAdapterSelection().collect { seen.send(it) } }
        assertEquals(AdapterSelection.Automatic, seen.receive())

        // Straight to the file, so nothing but the collector logs while it runs.
        repeat(UNRELATED_EDITS) { index -> dataStore.edit { it[PairingPreferenceKeys.MAX_THROTTLE_PERCENT] = 50 + index } }
        // Real time, off the test scheduler: the collector runs on the IO dispatcher and sees every edit.
        withContext(Dispatchers.IO) { delay(SETTLE_MS) }
        collector.cancelAndJoin()

        assertTrue(seen.tryReceive().isFailure, "the selection did not change, so nothing more is emitted")
        val warnings = writer.entries.count { it.severity == Severity.Warn && "unreadable stored adapter selection" in it.message }
        assertEquals(1, warnings, writer.allText())
    }

    @Test
    fun aStoredAdapterSelectionIsReadBackFromItsTextForm() = runTest {
        val log = Logger.withTag("DataStorePairingStoreTest")
        val dataStore = open(newFile(), log)
        val store = DataStorePairingStore(dataStore, CryptoPrimitives(), log)

        dataStore.edit { it[PairingPreferenceKeys.ADAPTER_SELECTION] = "0BDA:C812" }
        assertEquals(AdapterSelection.Manual(UsbId(0x0BDA, 0xC812)), store.getAdapterSelection())

        dataStore.edit { it[PairingPreferenceKeys.ADAPTER_SELECTION] = "auto" }
        assertEquals(AdapterSelection.Automatic, store.getAdapterSelection())
    }

    @Test
    fun anIncompleteOrMalformedPairedKartReadsAsNone() = runTest {
        val writer = CapturingLogWriter()
        val log = capturingLogger(writer)
        val dataStore = open(newFile(), log)
        val store = DataStorePairingStore(dataStore, CryptoPrimitives(), log)

        dataStore.edit { it[PairingPreferenceKeys.PAIRED_SERIAL] = "KART-1" } // ident missing
        assertNull(store.getPairedKart())

        dataStore.edit { it[PairingPreferenceKeys.PAIRED_IDENT] = ByteArray(3) } // ident of the wrong size
        assertNull(store.getPairedKart())
        assertTrue(writer.logged(Severity.Warn), "an unusable paired-kart record must be logged")
    }

    @Test
    fun aCorruptFileIsReplacedAndTheCorruptionIsLogged() = runTest {
        val writer = CapturingLogWriter()
        val log = capturingLogger(writer)
        val file = newFile()
        file.writeBytes(ByteArray(64) { 0xFF.toByte() })

        val store = DataStorePairingStore(open(file, log), CryptoPrimitives(), log)

        assertEquals(32, store.getMasterSecret().size, "a fresh identity is generated over the corrupt file")
        assertTrue(writer.logged(Severity.Error), "file corruption must be logged at error level")
    }

    private companion object {
        const val UNRELATED_EDITS = 3
        const val SETTLE_MS = 300L
    }
}
