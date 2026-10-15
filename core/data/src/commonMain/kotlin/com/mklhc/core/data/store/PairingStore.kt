package com.mklhc.core.data.store

import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.PairedKart
import kotlinx.coroutines.flow.Flow

/**
 * The installation's persistent state: its generated identity, the kart it is paired with and the
 * user's driving settings.
 *
 * Business logic: three values are generated once, on first use, and then never change for the life
 * of the installation, because everything a paired kart has learned depends on them staying put:
 * - the 32-octet master secret, from which the game network credentials and the RCD server identity
 *   are derived (see `deriveKartIdentity`);
 * - the access point's BSSID, a locally administered unicast MAC address;
 * - the Wi-Fi channel (1, 6 or 11), chosen at random once so two nearby installations are unlikely
 *   to collide.
 *
 * The getters for these values are therefore "get or create": the first call generates and persists
 * the value (atomically, so concurrent first calls agree on one) and every later call, in this or a
 * later process, returns the same one. The paired kart is optional and replaceable, and the settings
 * start from [KartSettings.DEFAULT].
 *
 * Implementations never log the master secret, and arrays they return are copies the caller may
 * overwrite. Losing the store loses the identity: a fresh one is generated and the kart must be paired
 * again.
 */
interface PairingStore {

    /** The 32-octet master secret; generated and persisted on first use. */
    suspend fun getMasterSecret(): ByteArray

    /** The access point's 6-octet BSSID (locally administered, unicast); generated and persisted on first use. */
    suspend fun getApBssid(): ByteArray

    /** The Wi-Fi channel, one of [ALLOWED_CHANNELS]; chosen and persisted on first use. */
    suspend fun getChannel(): Int

    /** The kart this installation is paired with, or null when none is. */
    suspend fun getPairedKart(): PairedKart?

    /** Remembers [kart] as the paired kart, replacing any previous one; null forgets it. */
    suspend fun setPairedKart(kart: PairedKart?)

    /** The user's settings, [KartSettings.DEFAULT] until they are changed. */
    suspend fun getSettings(): KartSettings

    /** Persists [settings] and notifies [observeSettings]. */
    suspend fun setSettings(settings: KartSettings)

    /** Emits the current settings on collection and again after every change. */
    fun observeSettings(): Flow<KartSettings>

    companion object {
        /** Size of the master secret in octets. */
        const val MASTER_SECRET_SIZE = 32

        /** Size of a BSSID in octets. */
        const val BSSID_SIZE = 6

        /** The non-overlapping 2.4 GHz channels the access point may use. */
        val ALLOWED_CHANNELS: List<Int> = listOf(1, 6, 11)
    }
}

/**
 * Creates the values a [PairingStore] generates on first run.
 *
 * Business logic: kept separate from storage so both store implementations create identical shapes,
 * and so the randomness can be pinned in tests. [randomBytes] must be a cryptographically secure
 * source (in production `CryptoPrimitives.secureRandomBytes`).
 *
 * @param randomBytes returns exactly the requested number of secure random octets.
 */
internal class FirstRunGenerator(private val randomBytes: (Int) -> ByteArray) {

    /** A new 32-octet master secret. */
    fun masterSecret(): ByteArray = randomBytes(PairingStore.MASTER_SECRET_SIZE)

    /**
     * A new BSSID: six random octets with the first octet forced to a locally administered (bit 1
     * set) unicast (bit 0 clear) address, as the radio's hardware acknowledgement needs a unicast
     * BSSID and a random address must not collide with a manufacturer-assigned one.
     */
    fun apBssid(): ByteArray {
        val bssid = randomBytes(PairingStore.BSSID_SIZE)
        bssid[0] = ((bssid[0].toInt() and GROUP_AND_LOCAL_MASK_INVERTED) or LOCAL_BIT).toByte()
        return bssid
    }

    /**
     * A channel drawn from [PairingStore.ALLOWED_CHANNELS]. The octet is reduced modulo three; the
     * resulting slight bias (256 is not a multiple of three) is irrelevant for a non-secret choice.
     */
    fun channel(): Int {
        val octet = randomBytes(1)[0].toInt() and OCTET_MASK
        return PairingStore.ALLOWED_CHANNELS[octet % PairingStore.ALLOWED_CHANNELS.size]
    }

    companion object {
        private const val GROUP_BIT = 0x01
        private const val LOCAL_BIT = 0x02
        private const val GROUP_AND_LOCAL_MASK_INVERTED = (GROUP_BIT or LOCAL_BIT).inv()
        private const val OCTET_MASK = 0xFF

        /** True when [bssid] is six octets, unicast and locally administered. */
        fun isValidBssid(bssid: ByteArray): Boolean {
            if (bssid.size != PairingStore.BSSID_SIZE) return false
            val first = bssid[0].toInt()
            return (first and GROUP_BIT) == 0 && (first and LOCAL_BIT) != 0
        }
    }
}
