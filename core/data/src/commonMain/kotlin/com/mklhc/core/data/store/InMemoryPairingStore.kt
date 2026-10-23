package com.mklhc.core.data.store

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.PairedKart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [PairingStore] that keeps everything in memory.
 *
 * Business logic: it has the full first-run semantics of the contract (generate once, then stable,
 * concurrent first calls agree) but nothing survives the process. It serves as the store for tests and
 * as a default where persistence is not wanted. Values can be seeded through the constructor to pin an
 * installation's identity in a test; seeds are validated like generated values.
 *
 * Generation is serialized by a mutex, so concurrent first calls observe one value. The master secret
 * is never logged; generation is logged at info level without any value.
 *
 * @param crypto the secure random source for first-run generation.
 * @param masterSecret optional seed for the master secret (32 octets).
 * @param apBssid optional seed for the BSSID (6 octets, locally administered unicast).
 * @param channel optional seed for the channel (one of [PairingStore.ALLOWED_CHANNELS]).
 * @param pairedKart optional seed for the paired kart.
 * @param settings the initial settings.
 * @param adapterSelection the initial USB adapter selection.
 * @param log where first-run generation is logged.
 * @throws IllegalArgumentException if a seed is malformed.
 */
class InMemoryPairingStore(
    crypto: CryptoPrimitives = CryptoPrimitives(),
    masterSecret: ByteArray? = null,
    apBssid: ByteArray? = null,
    channel: Int? = null,
    pairedKart: PairedKart? = null,
    settings: KartSettings = KartSettings.DEFAULT,
    adapterSelection: AdapterSelection = AdapterSelection.Automatic,
    private val log: Logger = Logger.withTag(TAG),
) : PairingStore {

    private val generator = FirstRunGenerator(crypto::secureRandomBytes)
    private val lock = Mutex()

    private var masterSecretBytes: ByteArray? = masterSecret?.also {
        require(it.size == PairingStore.MASTER_SECRET_SIZE) { "masterSecret must be ${PairingStore.MASTER_SECRET_SIZE} octets, got ${it.size}" }
    }?.copyOf()
    private var apBssidBytes: ByteArray? = apBssid?.also {
        require(FirstRunGenerator.isValidBssid(it)) { "apBssid must be a 6-octet locally administered unicast address" }
    }?.copyOf()
    private var channelValue: Int? = channel?.also {
        require(it in PairingStore.ALLOWED_CHANNELS) { "channel $it is not one of ${PairingStore.ALLOWED_CHANNELS}" }
    }
    private var pairedKartValue: PairedKart? = pairedKart
    private val settingsState = MutableStateFlow(settings)
    private val adapterSelectionState = MutableStateFlow(adapterSelection)

    override suspend fun getMasterSecret(): ByteArray = lock.withLock {
        val existing = masterSecretBytes
        if (existing != null) return@withLock existing.copyOf()
        val created = generator.masterSecret()
        masterSecretBytes = created
        log.i { "getMasterSecret(): first run, generated master secret -> size=${created.size}" }
        created.copyOf()
    }

    override suspend fun getApBssid(): ByteArray = lock.withLock {
        val existing = apBssidBytes
        if (existing != null) return@withLock existing.copyOf()
        val created = generator.apBssid()
        apBssidBytes = created
        log.i { "getApBssid(): first run, generated AP BSSID" }
        created.copyOf()
    }

    override suspend fun getChannel(): Int = lock.withLock {
        channelValue ?: generator.channel().also {
            channelValue = it
            log.i { "getChannel(): first run, chose channel -> channel=$it" }
        }
    }

    override suspend fun getPairedKart(): PairedKart? = lock.withLock { pairedKartValue }

    override suspend fun setPairedKart(kart: PairedKart?) {
        lock.withLock { pairedKartValue = kart }
        log.i { "setPairedKart(): updated -> serial=${kart?.serial}" }
    }

    override suspend fun getSettings(): KartSettings = settingsState.value

    override suspend fun setSettings(settings: KartSettings) {
        settingsState.value = settings
    }

    override fun observeSettings(): Flow<KartSettings> = settingsState.asStateFlow()

    override suspend fun getAdapterSelection(): AdapterSelection = adapterSelectionState.value

    override suspend fun setAdapterSelection(selection: AdapterSelection) {
        adapterSelectionState.value = selection
        log.i { "setAdapterSelection(): updated -> selection=${selection.toStoredText()}" }
    }

    override fun observeAdapterSelection(): Flow<AdapterSelection> = adapterSelectionState.asStateFlow()

    private companion object {
        const val TAG = "InMemoryPairingStore"
    }
}
