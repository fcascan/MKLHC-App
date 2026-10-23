package com.mklhc.core.data.radio

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.data.store.PairingStore
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.UsbId
import com.mklhc.core.netstack.FrameLink
import com.mklhc.core.wifi_ap.ap.ApConfig
import com.mklhc.core.wifi_ap.ap.RadioAccessPoint
import com.mklhc.core.wifi_ap.lp2p.Lp2pBeacon
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.core.wifi_ap.mac.MacAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
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
 *   radio and brings the radio up, on the adapter the [UsbAdapterRepository] resolves, on the channel the
 *   [PairingStore] chose (the one channel every network of this installation uses); see [bringUpForStart] for the
 *   adapter and for a radiod that goes away during the bring-up. It is idempotent, and when the radio does not come up
 *   the access point is shut down again so a retry starts clean.
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
 * - A radio helper relaunched underneath a started radio (the desktop supervisor restarts radiod after a crash) is
 *   idle: nothing opens the adapter, sets the identity or starts the beacon, and [start] and a repeated `beacon(GAME)`
 *   would answer from the state recorded here and change nothing. So, when the platform passes a [radioConnection],
 *   a watcher reacts to its false-to-true change while the radio is started: under the lifecycle lock it resolves the
 *   adapter again, brings the radio up again on the same channel and, when a network was served, serves it again with the very same [ApConfig]
 *   (same SSID, keys and nonce, so the kart joins again with what it holds; rebuilding it would draw a new game nonce
 *   and so a new key). Bringing the radio up drops the associated station, so the orchestrator sees a departure and
 *   recovers as for any other. A started radio that beaconed nothing is only brought up. A failure is logged at error
 *   and left for the next reconnect; until the network is served again the bookkeeping says that nothing is served, so
 *   a request for a network is served (or fails visibly) instead of being taken for a no-op on a radio that is off
 *   the air. A disconnect alone changes nothing, and nothing happens while the radio is not started.
 * - Disconnects and reconnects are detected as edges of [radioConnection], a conflated StateFlow: a drop and a
 *   reconnect that both land between two looks at it read as no change at all. That holds because the desktop
 *   supervisor waits at least 0.5 s (its restart backoff) before relaunching radiod, which lets the watcher, and the
 *   bring-up of [start], see the false before the true. No generation counter of the connection is kept for now; one
 *   would be needed only if radiod could come back faster than that.
 *
 * The DHCP lease is the orchestrator's business: the access point does not touch it, `KartSessionRepository` resets it
 * after pairing and when a session ends, and the DHCP server re-offers its single lease to any client.
 *
 * Threading: one [Mutex] serialises [start], [stop], [beacon] and the re-bring-up after a reconnect. The nonce table is
 * not thread-safe, and a beacon request must never interleave with a start or a stop. A re-bring-up holds the lock
 * for as long as radiod takes to report READY (like [start]), so [stop] ends it first instead of waiting behind it,
 * and a disconnect that arrives meanwhile ends it too. [start] holds it through its bring-up, including the adapter's
 * scan (a few seconds at most) and the wait for radiod to come back after a disconnect; cancelling its caller ends
 * that wait. Everything else is as safe as the
 * layers underneath.
 *
 * Secrets: the pairing seed, the pre-shared keys, the CCMP keys and the master secret are never logged.
 *
 * @param accessPoint the access point over the radio link; it is the [frameLink] and is started and stopped here, so
 *   nobody else may start it.
 * @param pairingStore where the master secret, the BSSID and the channel come from.
 * @param scope the scope the access point's radio collectors and the reconnect watcher run in; give it the same
 *   single-threaded scope as the orchestrator. The watcher ends when the scope ends.
 * @param adapterState what the platform says about the adapter (on desktop: whether radiod is connected and an adapter
 *   resolves; on Android the USB permission, in Phase 2).
 * @param adapterRepository which adapter to open: resolved right before every bring-up (the first, the one after a
 *   disconnect during [start], and the redo after a reconnect), and told which adapter the radio holds.
 * @param awaitRadioConnected suspends until the radio helper can be reached; [start] calls it before touching the
 *   radio, and again when radiod disconnected during its bring-up. Null (the default) derives it from
 *   [radioConnection]: it waits until the connection is true, or returns at once when there is no connection signal.
 *   Pass it only when the platform knows better than its connection flag when the helper can be reached.
 * @param radioConnection whether the radio helper is connected, as the platform's link reports it (on desktop
 *   `DesktopRadioLink.connected`); null (the default) means the helper is not relaunched underneath the radio, so
 *   nothing is watched and [start] brings the radio up without racing a disconnect. A change from false to true while
 *   the radio is started brings it up again and serves the network again (see above). Only changes after the radio
 *   came up count: its value at that moment is the baseline, so the connection [start] waited for through
 *   [awaitRadioConnected], or came back to after a disconnect during its bring-up, is never mistaken for a reconnect.
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
    private val adapterRepository: UsbAdapterRepository,
    private val awaitRadioConnected: (suspend () -> Unit)? = null,
    private val radioConnection: StateFlow<Boolean>? = null,
    private val onRequestAdapterPermission: () -> Unit = {},
    private val crypto: CryptoPrimitives = CryptoPrimitives(),
    private val nonceTable: NonceTable = NonceTable(),
    private val beaconIntervalTu: Int = ApConfig.DEFAULT_BEACON_INTERVAL_TU,
    private val log: Logger = Logger.withTag(TAG),
) : KartRadioAp {

    private val lp2pBeacon = Lp2pBeacon(crypto)

    /** Serialises [start], [stop], [beacon] and the re-bring-up after a reconnect, and guards every field below. */
    private val lifecycleLock = Mutex()

    // The channel the radio was brought up on; null while the radio is not started.
    private var startedChannel: Int? = null

    // The network the access point serves now (null for none): what was asked for and the configuration it got.
    private var served: ServedNetwork? = null

    // The network a re-bring-up dropped and that is not served again yet (null for none). The next reconnect serves it
    // again, and a request that serves something else successfully replaces it.
    private var interrupted: ServedNetwork? = null

    // The watcher of radioConnection, alive exactly while the radio is started. Written under lifecycleLock; read by
    // stop() before it takes the lock, so that it can end a re-bring-up that holds it.
    @Volatile
    private var connectionWatcher: Job? = null

    /** The one access point; it survives [stop] and [start] cycles, as the contract requires. */
    override val frameLink: FrameLink = accessPoint

    /**
     * Passed through, not mapped: a mapped flow would still say true for one dispatch after a stop or a switch. It also
     * turns false when the kart deauthenticates, and when it has sent nothing for the access point's inactivity timeout
     * ([RadioOptions.stationInactivityTimeoutMs][com.mklhc.core.wifi_ap.ap.RadioOptions.stationInactivityTimeoutMs],
     * 20 s by default): a kart that powered off or left range is dropped then instead of staying associated forever.
     */
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
     * A failure (the helper is unreachable, radiod reports an error, READY does not arrive, radiod disconnects twice
     * during the bring-up) shuts the access point down again and is rethrown; cancellation, or any other throwable,
     * does the same cleanup and propagates. Once the radio is up the reconnect watcher starts (see [radioConnection]).
     */
    override suspend fun start() {
        lifecycleLock.withLock {
            val running = startedChannel
            if (running != null) {
                log.d { "start(): already started -> channel=$running" }
                return
            }
            awaitConnection()
            var started = false
            var accessPointStarted = false
            var adapterResolved = false
            try {
                val channel = pairingStore.getChannel()
                // Before the access point starts: with no usable adapter nothing is opened, and radiod is not sent a
                // SHUTDOWN (which would end it) for a radio it never brought up.
                val firstAdapter = adapterRepository.resolveForBringUp()
                adapterResolved = true
                accessPoint.start(scope)
                accessPointStarted = true
                val adapter = bringUpForStart(channel, firstAdapter)
                startedChannel = channel
                started = true
                adapterRepository.radioActive(adapter)
                log.i { "start(): radio started -> channel=$channel adapter=$adapter" }
                launchReconnectWatcher()
            } catch (failure: Throwable) {
                // Only reported here and rethrown. A cancellation is not a failure of the radio: it is cleaned up
                // below like the rest, but not reported.
                if (failure !is CancellationException) {
                    log.w { "start(): the radio did not come up, shutting the access point down again -> message=${failure.message}" }
                }
                throw failure
            } finally {
                // Whatever ended the attempt (a failure, a cancellation, an Error), a half-started access point would
                // keep its collectors and make the next start() fail with "already started", and the repository
                // would keep its scans paused for an adapter the radio never opened.
                if (!started) {
                    if (accessPointStarted) stopAccessPoint("start()")
                    if (adapterResolved) adapterRepository.radioActive(null)
                }
            }
        }
    }

    /**
     * Stops the beacon and the radio and forgets the served network; [associatedStation] turns false. Never throws and
     * runs to completion even when the caller is cancelled; safe to call when already stopped. It ends the reconnect
     * watcher, and with it a re-bring-up in flight, so it never waits for READY behind one.
     */
    override suspend fun stop(): Unit = withContext(NonCancellable) {
        // Before the lock: a re-bring-up in flight holds it until READY (or its timeout), and cancelling the watcher
        // releases it. Not enough alone: a start() that holds the lock now may still launch a watcher; that is below.
        connectionWatcher?.cancel()
        lifecycleLock.withLock {
            log.i { "stop(): stopping the radio -> wasStarted=${startedChannel != null}" }
            connectionWatcher?.cancel()
            connectionWatcher = null
            stopAccessPoint("stop()")
            val wasStarted = startedChannel != null
            startedChannel = null
            served = null
            interrupted = null
            if (wasStarted) adapterRepository.radioActive(null)
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
            val current = served
            if (current != null && current.kind == network && current.pairing == pairing) {
                log.d { "beacon(): already served, nothing to do -> network=$network" }
                return
            }

            val config = if (pairing != null) pairingConfig(pairing, channel) else gameConfig(channel)
            // Forget the old network before serving: when serve() fails part-way the radio's state is unknown, and the
            // next request for either network must redo everything instead of taking the no-op above.
            served = null
            accessPoint.serve(config)
            served = ServedNetwork(network, pairing?.let { PairingNetwork(it.ssid, it.seed.copyOf(), it.channel) }, config)
            // Whatever a re-bring-up dropped is superseded by what the radio serves now.
            interrupted = null
            log.i { "beacon(): network served -> network=$network ssid=${config.ssid} bssid=${config.bssid} channel=$channel" }
        }
    }

    /**
     * Suspends until radiod can be reached: through the platform's [awaitRadioConnected] when it gave one, else until
     * [radioConnection] is true, else not at all.
     */
    private suspend fun awaitConnection() {
        val explicit = awaitRadioConnected
        val connection = radioConnection
        when {
            explicit != null -> explicit()
            connection != null -> connection.first { connected -> connected }
        }
    }

    /**
     * Brings the radio up on [channel] for [start], with [firstAdapter] first, and returns the adapter it came up on;
     * call with [lifecycleLock] held, after `accessPoint.start`.
     *
     * Business logic: the adapter. [start] asks the [UsbAdapterRepository] for [firstAdapter] before the access point
     * starts (a fresh scan resolved against the user's choice), so with no usable adapter it fails with
     * [AdapterUnavailableException] before touching the radio, and radiod is not sent a SHUTDOWN for nothing. Once the
     * radio is up, [start] tells the repository which adapter the radio holds; it tells it the radio holds none when a
     * bring-up fails and when [stop] runs, so the repository never scans a device radiod has open.
     *
     * Without a [radioConnection] this is one bring-up. With one, the bring-up races radiod disconnecting
     * ([bringUpUnlessDisconnected]): a radiod that goes away during it (a crash in its ~50 s INIT, or the radiod of a
     * [stop] that is still connected while it shuts down) never sends READY, and the bring-up would wait out its whole
     * READY timeout (two minutes) for nothing, after which the orchestrator gives up. On a disconnect the bring-up is
     * abandoned, [awaitConnection] waits for radiod to be connected again, the adapter is
     * resolved again (the new radiod scans afresh) and the radio is brought up once more from OPEN_DEVICE on: the
     * relaunched radiod knows nothing of the first commands. A second disconnect fails with an IllegalStateException, so
     * a radiod that keeps crashing is reported instead of chased; any other failure (an [AdapterUnavailableException]
     * included) fails at once, and cancellation propagates.
     */
    private suspend fun bringUpForStart(channel: Int, firstAdapter: UsbId): UsbId {
        val connection = radioConnection
        if (connection == null) {
            accessPoint.bringUp(channel, firstAdapter)
            return firstAdapter
        }
        var adapter = firstAdapter
        var attempt = 1
        while (!bringUpUnlessDisconnected(connection, channel, adapter)) {
            check(attempt < START_BRING_UP_ATTEMPTS) {
                "start(): radiod disconnected again during the bring-up, giving up -> channel=$channel attempts=$attempt"
            }
            log.w { "start(): radiod disconnected during the bring-up, waiting for it to reconnect -> channel=$channel attempt=$attempt" }
            awaitConnection()
            attempt++
            adapter = adapterRepository.resolveForBringUp()
            log.i { "start(): radiod connected again, bringing the radio up on it -> channel=$channel adapter=$adapter attempt=$attempt" }
        }
        return adapter
    }

    /**
     * Runs one bring-up on [channel] with [adapter] unless radiod disconnects first: true when the radio came up, false
     * when [connection] dropped (the bring-up is then abandoned, and the access point recorded nothing).
     *
     * Business logic: the disconnect watcher subscribes before the bring-up sends anything, so a drop at any moment
     * after the bring-up began is seen, and a connection already down counts at once (a StateFlow replays its value).
     * The drop fails the scope with [RadiodDisconnectedException], which cancels the bring-up, as `untilLost` does in
     * `KartSessionRepository`. A bring-up that fails on its own while the connection is down is the same drop, seen
     * first by a command radiod could no longer take ("radiod is not connected"). Any other failure, and cancellation,
     * propagate.
     */
    private suspend fun bringUpUnlessDisconnected(connection: StateFlow<Boolean>, channel: Int, adapter: UsbId): Boolean =
        try {
            coroutineScope {
                val disconnectWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    connection.first { connected -> !connected }
                    throw RadiodDisconnectedException()
                }
                accessPoint.bringUp(channel, adapter)
                disconnectWatcher.cancel()
            }
            true
        } catch (disconnected: RadiodDisconnectedException) {
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (connection.value) throw failure
            log.d { "bringUpUnlessDisconnected(): the bring-up failed while radiod is disconnected -> message=${failure.message}" }
            false
        }

    /**
     * Starts watching [radioConnection] for a reconnect: a change from false to true while the radio is started. Call
     * with [lifecycleLock] held, right after the bring-up of [start] succeeded. Does nothing without a connection signal.
     *
     * Business logic: the watcher is launched here, once per start, and [stop] ends it, rather than in `init` or on the
     * first [start]. It therefore exists exactly while the radio is started: while stopped there is no coroutine to
     * react, so "nothing happens" holds by construction and not by a check, and merely constructing the object launches
     * nothing. Its baseline is the connection as it is now that the radio is up, so the false-to-true change [start]
     * itself waited for ([awaitRadioConnected]), or the one it came back to after a disconnect during its own bring-up,
     * came before the watcher and can never make a second bring-up. (A watcher launched earlier would see that change
     * and, queued behind [start] on the lifecycle lock, redo the bring-up the moment [start] released it.) The reconnect
     * is handled by `collectLatest`: a disconnect during a re-bring-up cancels it, so a bring-up waiting for the READY of
     * a radiod that is gone does not hold the lifecycle lock until its timeout.
     */
    private fun launchReconnectWatcher() {
        val connection = radioConnection ?: return
        val connectedNow = connection.value
        log.d { "launchReconnectWatcher(): watching the radio connection -> connected=$connectedNow" }
        connectionWatcher = scope.launch {
            var wasConnected = connectedNow
            connection.collectLatest { connected ->
                // Decided before the first suspension, so a handler that is cancelled never leaves the baseline behind.
                val reconnected = connected && !wasConnected
                wasConnected = connected
                if (reconnected) redoBringUpAfterReconnect()
            }
        }
    }

    /**
     * Brings the radio up again after radiod reconnected, on the adapter resolved afresh, and serves again the network
     * that was served. A failure (no usable adapter included) is logged at error, reported to the adapter repository as
     * a radio that is down, and left for the next reconnect; cancellation propagates.
     *
     * Business logic: the access point drops the network when it brings the radio up (and with it the associated
     * station), so the bookkeeping moves the served network to [interrupted] first and gives it back only once it is
     * served again. The [ApConfig] it was served with is reused, not rebuilt: same keys, so the kart can rejoin. The
     * adapter is resolved again because the new radiod is a new chance: the adapter may have been unplugged, or another
     * plugged in, while radiod was away. Runs under the lifecycle lock; [stop] being called, or radiod disconnecting
     * again, cancels it.
     */
    private suspend fun redoBringUpAfterReconnect() {
        try {
            lifecycleLock.withLock {
                val channel = startedChannel
                if (channel == null) {
                    log.d { "redoBringUpAfterReconnect(): the radio is not started, nothing to bring up again" }
                    return
                }
                val resume = served ?: interrupted
                served = null
                interrupted = resume
                log.i {
                    "redoBringUpAfterReconnect(): radiod is connected again, bringing the radio up again -> " +
                        "channel=$channel network=${resume?.kind}"
                }
                val adapter = adapterRepository.resolveForBringUp()
                accessPoint.bringUp(channel, adapter)
                if (resume != null) {
                    accessPoint.serve(resume.config)
                    served = resume
                    interrupted = null
                }
                adapterRepository.radioActive(adapter)
                log.i { "redoBringUpAfterReconnect(): radio up again -> channel=$channel adapter=$adapter network=${resume?.kind}" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The radio is off the air until the next reconnect: the repository may scan again meanwhile, so the screen
            // shows what is plugged in.
            adapterRepository.radioActive(null)
            log.e(failure) {
                "redoBringUpAfterReconnect(): the radio did not come up again, left for the next reconnect -> message=${failure.message}"
            }
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

    /**
     * A network the access point serves: what the orchestrator asked for ([kind] and, for PAIRING, the [pairing]
     * request, compared by content) and the exact [config] it was served with, kept so a reconnect serves it again.
     */
    private class ServedNetwork(val kind: KartNetworkKind, val pairing: PairingNetwork?, val config: ApConfig)

    /** Ends a bring-up of [start] whose radiod disconnected; never leaves [bringUpUnlessDisconnected]. */
    private class RadiodDisconnectedException : Exception("radiod disconnected during the bring-up")

    private companion object {
        const val TAG = "RadioKartRadioAp"

        /** How many bring-ups [start] runs at most: the first, and one on the radiod that replaced a disconnected one. */
        const val START_BRING_UP_ATTEMPTS = 2
    }
}
