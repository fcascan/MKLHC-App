package com.mklhc.core.data.radio

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.data.store.PairingStore
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.netstack.FrameLink
import com.mklhc.core.wifi_ap.ap.ApConfig
import com.mklhc.core.wifi_ap.ap.RadioAccessPoint
import com.mklhc.core.wifi_ap.lp2p.Lp2pBeacon
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.core.wifi_ap.mac.MacAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/*
 * The production KartRadioAp: the orchestrator's radio seam implemented over the radio access point.
 *
 * Original code. It only wires modules that already exist (RadioAccessPoint, the pairing store, the identity
 * derivation, the LP2P beacon and nonce table); the LP2P key and beacon derivation it relies on is ported from
 * OpenKart (BSD-3-Clause) in Lp2pBeacon.kt, Lp2pKeys.kt and NonceTable.kt, which cite it.
 */

/**
 * The access point on the USB radio as the orchestrator sees it: start, stop, and which of the two networks to
 * advertise.
 *
 * Business logic: the orchestrator only names a [KartNetworkKind]; this class owns the credentials behind it.
 *
 * - [start] waits for the platform to report the radio helper reachable, subscribes the [RadioAccessPoint] to the
 *   radio and brings the radio up on the channel the [PairingStore] chose (the one channel every network of this
 *   installation uses). It is idempotent, and when the radio does not come up the access point is shut down again so
 *   a retry starts clean.
 * - [beacon] stands up one network. The game network takes its SSID and pre-shared key from the installation identity
 *   (`deriveKartIdentity` over the master secret) and a time-based LP2P nonce, because it is the persistent network.
 *   The pairing network takes the SSID the QR code shows, the key `SHA-256(seed)` and a random nonce, because it is a
 *   one-off. Both use the BSSID from the store: it is the same BSSID the orchestrator hands the network stack as its
 *   own MAC address, so ARP answers carry the address the kart associated with.
 * - Asking for the network that is already served does nothing. The orchestrator calls `beacon(GAME)` on every
 *   recovery pass; a fresh game nonce (a new CCMP key) or a station-table reset would cut off a kart that is still
 *   associated. Switching to another network serves a new configuration, which drops the associated station
 *   (switching networks resets the station table by design).
 * - [associatedStation] is the access point's own flag, published in the same step as its station, so it is exact
 *   at all times: already false when [stop] or a network switch returns.
 *
 * The DHCP lease is the orchestrator's business: the access point does not touch it, `KartSessionRepository` resets it
 * after pairing and when a session ends, and the DHCP server re-offers its single lease to any client.
 *
 * Known gap: when radiod is relaunched underneath (the desktop supervisor restarts it after a crash), nothing brings
 * the radio up and serves the network again. [start] and a repeated `beacon(GAME)` both short-circuit on the state
 * recorded here, which still says started and served, so the orchestrator's recovery pass changes nothing while the
 * new radiod sits idle. Production wiring is not done yet; the `TODO(production wiring)` on `startedChannel` says how
 * to close the gap.
 *
 * Threading: one [Mutex] serialises [start], [stop] and [beacon]. The nonce table is not thread-safe, and a beacon
 * request must never interleave with a start or a stop. Everything else is as safe as the layers underneath.
 *
 * Secrets: the pairing seed, the pre-shared keys, the CCMP keys and the master secret are never logged.
 *
 * @param accessPoint the access point over the radio link; it is the [frameLink] and is started and stopped here, so
 *   nobody else may start it.
 * @param pairingStore where the master secret, the BSSID and the channel come from.
 * @param scope the scope the access point's radio collectors run in; give it the same single-threaded scope as the
 *   orchestrator.
 * @param adapterState what the platform says about the adapter (on desktop: whether radiod is connected; on Android
 *   the USB permission, in Phase 2).
 * @param awaitRadioConnected suspends until the radio helper can be reached; [start] calls it before touching the
 *   radio. The default returns at once.
 * @param onRequestAdapterPermission asks the platform for permission to use the adapter; the default does nothing.
 * @param crypto the crypto provider for the identity, the pairing key and the LP2P beacon.
 * @param nonceTable the LP2P nonce table; share one for the life of the app so the time-based sequence continues.
 * @param beaconIntervalTu the beacon interval of both networks, in time units.
 * @param log the logger; injectable so tests can assert what is (and is not) logged.
 */
class RadioKartRadioAp(
    private val accessPoint: RadioAccessPoint,
    private val pairingStore: PairingStore,
    private val scope: CoroutineScope,
    override val adapterState: StateFlow<AdapterState>,
    private val awaitRadioConnected: suspend () -> Unit = {},
    private val onRequestAdapterPermission: () -> Unit = {},
    private val crypto: CryptoPrimitives = CryptoPrimitives(),
    private val nonceTable: NonceTable = NonceTable(),
    private val beaconIntervalTu: Int = ApConfig.DEFAULT_BEACON_INTERVAL_TU,
    private val log: Logger = Logger.withTag(TAG),
) : KartRadioAp {

    private val lp2pBeacon = Lp2pBeacon(crypto)

    /** Serialises [start], [stop] and [beacon], and guards every field below. */
    private val lifecycleLock = Mutex()

    // The channel the radio was brought up on; null while the radio is not started.
    // TODO(production wiring): recover from a radiod restart. Watch the platform's connection / adapter signal (on
    //  desktop, radiod connected again after the supervisor relaunched it; on Android, the adapter attached again) and
    //  on a reconnect, under lifecycleLock, clear startedChannel, servedKind and servedPairing and redo
    //  accessPoint.bringUp() and serve() of the network that was served; or surface the loss as a failure (for example
    //  an AdapterState change or a thrown beacon()) so the orchestrator recovers through stop(), start() and beacon().
    private var startedChannel: Int? = null

    // The network the access point serves now (null for none) and, for PAIRING, the request that described it.
    private var servedKind: KartNetworkKind? = null
    private var servedPairing: PairingNetwork? = null

    /** The one access point; it survives [stop] and [start] cycles, as the contract requires. */
    override val frameLink: FrameLink = accessPoint

    /** Passed through, not mapped: a mapped flow would still say true for one dispatch after a stop or a switch. */
    override val associatedStation: StateFlow<Boolean> = accessPoint.isStationAssociated

    override fun requestAdapterPermission() {
        log.i { "requestAdapterPermission(): asking the platform for the adapter permission" }
        try {
            onRequestAdapterPermission()
        } catch (failure: Exception) {
            log.w { "requestAdapterPermission(): the platform request failed -> message=${failure.message}" }
        }
    }

    /**
     * Brings the radio up on the store's channel, once: when already started it returns without touching the radio.
     * A failure (the helper is unreachable, radiod reports an error, READY does not arrive) shuts the access point
     * down again and is rethrown; cancellation, or any other throwable, does the same cleanup and propagates.
     */
    override suspend fun start() {
        lifecycleLock.withLock {
            val running = startedChannel
            if (running != null) {
                log.d { "start(): already started -> channel=$running" }
                return
            }
            awaitRadioConnected()
            accessPoint.start(scope)
            var started = false
            try {
                val channel = pairingStore.getChannel()
                accessPoint.bringUp(channel)
                startedChannel = channel
                started = true
                log.i { "start(): radio started -> channel=$channel" }
            } catch (failure: Throwable) {
                // Only reported here and rethrown. A cancellation is not a failure of the radio: it is cleaned up
                // below like the rest, but not reported.
                if (failure !is CancellationException) {
                    log.w { "start(): the radio did not come up, shutting the access point down again -> message=${failure.message}" }
                }
                throw failure
            } finally {
                // Whatever ended the attempt (a failure, a cancellation, an Error), a half-started access point would
                // keep its collectors and make the next start() fail with "already started".
                if (!started) stopAccessPoint("start()")
            }
        }
    }

    /**
     * Stops the beacon and the radio and forgets the served network; [associatedStation] turns false. Never throws and
     * runs to completion even when the caller is cancelled; safe to call when already stopped.
     */
    override suspend fun stop(): Unit = withContext(NonCancellable) {
        lifecycleLock.withLock {
            log.i { "stop(): stopping the radio -> wasStarted=${startedChannel != null}" }
            stopAccessPoint("stop()")
            startedChannel = null
            servedKind = null
            servedPairing = null
        }
    }

    /**
     * Advertises [network] on the started channel: the game network from the installation identity, or the pairing
     * network described by [pairing]. Asking again for the network already served is a no-op.
     *
     * @throws IllegalStateException if the radio is not started.
     * @throws IllegalArgumentException if [pairing] is missing for PAIRING or given for GAME, or names another channel
     *   than the radio was brought up on.
     */
    override suspend fun beacon(network: KartNetworkKind, pairing: PairingNetwork?) {
        lifecycleLock.withLock {
            val channel = checkNotNull(startedChannel) { "beacon(): the radio is not started, call start() first -> network=$network" }
            require((network == KartNetworkKind.PAIRING) == (pairing != null)) {
                "beacon(): a PairingNetwork is required for PAIRING and only valid then -> network=$network hasPairing=${pairing != null}"
            }
            if (pairing != null) {
                require(pairing.channel == channel) {
                    "beacon(): the pairing network is on another channel than the radio -> pairingChannel=${pairing.channel} radioChannel=$channel"
                }
            }
            if (servedKind == network && servedPairing == pairing) {
                log.d { "beacon(): already served, nothing to do -> network=$network" }
                return
            }

            val config = if (pairing != null) pairingConfig(pairing, channel) else gameConfig(channel)
            // Forget the old network before serving: when serve() fails part-way the radio's state is unknown, and the
            // next request for either network must redo everything instead of taking the no-op above.
            servedKind = null
            servedPairing = null
            accessPoint.serve(config)
            servedKind = network
            servedPairing = pairing?.let { PairingNetwork(it.ssid, it.seed.copyOf(), it.channel) }
            log.i { "beacon(): network served -> network=$network ssid=${config.ssid} bssid=${config.bssid} channel=$channel" }
        }
    }

    /** The game network: SSID and key from the installation identity, a time-based nonce (the persistent network). */
    private suspend fun gameConfig(channel: Int): ApConfig {
        val identity = deriveKartIdentity(pairingStore.getMasterSecret(), crypto)
        return buildConfig(ssid = identity.gameSsid, psk = identity.gamePsk, channel = channel, temporary = false)
    }

    /** The pairing network: the QR's SSID, the key `SHA-256(seed)` and a random nonce (a one-off network). */
    private suspend fun pairingConfig(pairing: PairingNetwork, channel: Int): ApConfig =
        buildConfig(ssid = pairing.ssid, psk = crypto.sha256(pairing.seed), channel = channel, temporary = true)

    private suspend fun buildConfig(ssid: String, psk: ByteArray, channel: Int, temporary: Boolean): ApConfig {
        val network = lp2pBeacon.encryptNetwork(psk, nonceTable, crypto::secureRandomBytes, temporary)
        val bssid = MacAddress.fromBytes(pairingStore.getApBssid())
        return ApConfig(bssid = bssid, ssid = ssid, channel = channel, beaconIntervalTu = beaconIntervalTu, network = network)
    }

    /** Stops the access point, logging a failure instead of throwing it. */
    private suspend fun stopAccessPoint(caller: String) {
        try {
            accessPoint.stop()
        } catch (failure: Exception) {
            log.w { "$caller: stopping the access point failed -> message=${failure.message}" }
        }
    }

    private companion object {
        const val TAG = "RadioKartRadioAp"
    }
}
