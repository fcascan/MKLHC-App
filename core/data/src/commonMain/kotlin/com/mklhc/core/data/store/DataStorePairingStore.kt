package com.mklhc.core.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.PairedKart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.IOException
import okio.Path.Companion.toPath

/** File name of the pairing preferences file on every platform. */
const val PAIRING_STORE_FILE_NAME = "pairing.preferences_pb"

/** The keys under which [DataStorePairingStore] keeps its values. Internal so tests can plant bad data. */
internal object PairingPreferenceKeys {
    val MASTER_SECRET = byteArrayPreferencesKey("master_secret")
    val AP_BSSID = byteArrayPreferencesKey("ap_bssid")
    val CHANNEL = intPreferencesKey("channel")
    val PAIRED_SERIAL = stringPreferencesKey("paired_serial")
    val PAIRED_IDENT = byteArrayPreferencesKey("paired_ident")
    val MAX_THROTTLE_PERCENT = intPreferencesKey("max_throttle_percent")
    val STEERING_EXPO_PERCENT = intPreferencesKey("steering_expo_percent")
    val STEERING_TRIM_PERCENT = intPreferencesKey("steering_trim_percent")
    val SHUTDOWN_ON_EXIT = booleanPreferencesKey("shutdown_on_exit")

    /** `auto`, or the chosen adapter's `vvvv:pppp`. */
    val ADAPTER_SELECTION = stringPreferencesKey("adapter_selection")
}

/**
 * A [PairingStore] that persists to an AndroidX Preferences DataStore file.
 *
 * Business logic: the three generated-once values (master secret, BSSID, channel) are created inside
 * a DataStore `edit`, which is atomic, so concurrent first calls (and a crash half-way) can never
 * leave two different values behind; later calls, in this or a later process, read the same ones. The
 * file is the installation's whole identity, so a stored value that is present but malformed (a master
 * secret of the wrong size, a multicast BSSID, an unknown channel) is logged and regenerated rather
 * than trusted, and out-of-range settings are clamped to the nearest valid ones. The adapter selection is
 * kept as the text `auto` or `vvvv:pppp`; an unreadable text reads as automatic, with a warning, and never
 * fails the read. An unreadable file
 * (corruption) is handled by [createPairingDataStore]: it is replaced and the corruption logged.
 *
 * Secrets: the master secret is stored in the clear in the app's private storage (see the platform
 * path helpers) and is never logged. Obtain the [DataStore] from [createPairingDataStore]; there must
 * be only one live DataStore per file per process.
 *
 * @param dataStore the preferences store to persist in.
 * @param crypto the secure random source for first-run generation.
 * @param log where generation, repairs and I/O errors are logged (never values of secrets).
 */
class DataStorePairingStore(
    private val dataStore: DataStore<Preferences>,
    crypto: CryptoPrimitives = CryptoPrimitives(),
    private val log: Logger = Logger.withTag(TAG),
) : PairingStore {

    private val generator = FirstRunGenerator(crypto::secureRandomBytes)

    /** The stored preferences; a read I/O failure is logged and degrades to "nothing stored" for this read. */
    private val preferences: Flow<Preferences> = dataStore.data.catch { failure ->
        if (failure is IOException) {
            log.e(failure) { "preferences: could not read the pairing store -> treating as empty for this read" }
            emit(emptyPreferences())
        } else {
            throw failure
        }
    }

    override suspend fun getMasterSecret(): ByteArray = getOrCreate(
        key = PairingPreferenceKeys.MASTER_SECRET,
        name = "master secret",
        isValid = { it.size == PairingStore.MASTER_SECRET_SIZE },
        create = generator::masterSecret,
    ).copyOf()

    override suspend fun getApBssid(): ByteArray = getOrCreate(
        key = PairingPreferenceKeys.AP_BSSID,
        name = "AP BSSID",
        isValid = FirstRunGenerator::isValidBssid,
        create = generator::apBssid,
    ).copyOf()

    override suspend fun getChannel(): Int = getOrCreate(
        key = PairingPreferenceKeys.CHANNEL,
        name = "channel",
        isValid = { it in PairingStore.ALLOWED_CHANNELS },
        create = generator::channel,
    )

    override suspend fun getPairedKart(): PairedKart? {
        val stored = preferences.first()
        val serial = stored[PairingPreferenceKeys.PAIRED_SERIAL]
        val ident = stored[PairingPreferenceKeys.PAIRED_IDENT]
        if (serial == null && ident == null) return null
        if (serial == null || ident == null || ident.size != PairedKart.IDENT_SIZE) {
            log.w { "getPairedKart(): unusable paired-kart record, treating as unpaired -> hasSerial=${serial != null} identSize=${ident?.size}" }
            return null
        }
        return PairedKart(serial, ident)
    }

    override suspend fun setPairedKart(kart: PairedKart?) {
        dataStore.edit { prefs ->
            if (kart == null) {
                prefs.remove(PairingPreferenceKeys.PAIRED_SERIAL)
                prefs.remove(PairingPreferenceKeys.PAIRED_IDENT)
            } else {
                prefs[PairingPreferenceKeys.PAIRED_SERIAL] = kart.serial
                prefs[PairingPreferenceKeys.PAIRED_IDENT] = kart.ident
            }
        }
        log.i { "setPairedKart(): updated -> serial=${kart?.serial}" }
    }

    override suspend fun getSettings(): KartSettings = observeSettings().first()

    override suspend fun setSettings(settings: KartSettings) {
        dataStore.edit { prefs ->
            prefs[PairingPreferenceKeys.MAX_THROTTLE_PERCENT] = settings.maxThrottlePercent
            prefs[PairingPreferenceKeys.STEERING_EXPO_PERCENT] = settings.steeringExpoPercent
            prefs[PairingPreferenceKeys.STEERING_TRIM_PERCENT] = settings.steeringTrimPercent
            prefs[PairingPreferenceKeys.SHUTDOWN_ON_EXIT] = settings.shutdownOnExit
        }
    }

    override fun observeSettings(): Flow<KartSettings> = preferences.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun getAdapterSelection(): AdapterSelection = observeAdapterSelection().first()

    override suspend fun setAdapterSelection(selection: AdapterSelection) {
        dataStore.edit { prefs -> prefs[PairingPreferenceKeys.ADAPTER_SELECTION] = selection.toStoredText() }
        log.i { "setAdapterSelection(): updated -> selection=${selection.toStoredText()}" }
    }

    // Deduplicated on the stored text first, so an unreadable value is reported once per distinct value, not on every
    // edit of an unrelated preference.
    override fun observeAdapterSelection(): Flow<AdapterSelection> =
        preferences.map { it[PairingPreferenceKeys.ADAPTER_SELECTION] }
            .distinctUntilChanged()
            .map { stored -> storedTextToAdapterSelection(stored) }
            .distinctUntilChanged()

    /**
     * Returns the value stored under [key] when it is present and [isValid]; otherwise creates one
     * with [create], persists it and returns it. The check-and-create runs inside one atomic `edit`.
     */
    private suspend fun <T : Any> getOrCreate(
        key: Preferences.Key<T>,
        name: String,
        isValid: (T) -> Boolean,
        create: () -> T,
    ): T {
        preferences.first()[key]?.takeIf(isValid)?.let { return it }

        var result: T? = null
        dataStore.edit { prefs ->
            val current = prefs[key]
            if (current != null && isValid(current)) {
                result = current
            } else {
                if (current != null) {
                    log.w { "getOrCreate(): stored $name is invalid, regenerating it" }
                }
                val created = create()
                prefs[key] = created
                result = created
                log.i { "getOrCreate(): first run, generated $name" }
            }
        }
        return checkNotNull(result) { "the $name edit did not produce a value" }
    }

    private fun Preferences.toSettings(): KartSettings {
        val defaults = KartSettings.DEFAULT
        val maxThrottle = this[PairingPreferenceKeys.MAX_THROTTLE_PERCENT] ?: defaults.maxThrottlePercent
        val expo = this[PairingPreferenceKeys.STEERING_EXPO_PERCENT] ?: defaults.steeringExpoPercent
        val trim = this[PairingPreferenceKeys.STEERING_TRIM_PERCENT] ?: defaults.steeringTrimPercent
        val shutdown = this[PairingPreferenceKeys.SHUTDOWN_ON_EXIT] ?: defaults.shutdownOnExit
        val coerced = KartSettings.coerced(maxThrottle, expo, trim, shutdown)
        if (coerced.maxThrottlePercent != maxThrottle || coerced.steeringExpoPercent != expo || coerced.steeringTrimPercent != trim) {
            log.w { "toSettings(): stored settings out of range, clamped -> maxThrottle=$maxThrottle expo=$expo trim=$trim" }
        }
        return coerced
    }

    /** The [stored] selection text; absent reads as automatic, an unreadable value reads as automatic with a warning. */
    private fun storedTextToAdapterSelection(stored: String?): AdapterSelection {
        if (stored == null) return AdapterSelection.Automatic
        return stored.toAdapterSelectionOrNull() ?: run {
            log.w { "storedTextToAdapterSelection(): unreadable stored adapter selection, treating as automatic -> stored=$stored" }
            AdapterSelection.Automatic
        }
    }

    private companion object {
        const val TAG = "DataStorePairingStore"
    }
}

/**
 * Opens the pairing DataStore file at [path].
 *
 * Business logic: the file holds the installation's identity, so a corrupt file (a partial write, a
 * bad restore) must not crash the app on every launch: it is replaced by an empty store and the
 * corruption is logged at error level, after which the first-run generation creates a new identity
 * and the kart has to be paired again. There must be only one live DataStore per file per process;
 * create it once and share it. Parent directories are created as needed.
 *
 * Platform path helpers: `androidPairingStorePath(context)` on Android and `desktopPairingStorePath()`
 * on the desktop build. The default [scope] runs file I/O on the IO dispatcher; cancel it to release
 * the file.
 *
 * @param path absolute path of the preferences file.
 * @param scope the scope the DataStore runs in.
 * @param log where a corrupt file is reported.
 */
fun createPairingDataStore(
    path: String,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    log: Logger = Logger.withTag("PairingDataStore"),
): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
    corruptionHandler = ReplaceFileCorruptionHandler { failure ->
        log.e(failure) { "createPairingDataStore(): pairing file is corrupt, replacing it with an empty one -> path=$path" }
        emptyPreferences()
    },
    scope = scope,
    produceFile = { path.toPath() },
)
