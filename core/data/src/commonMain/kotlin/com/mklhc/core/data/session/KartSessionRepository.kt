package com.mklhc.core.data.session

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.identity.KartIdentity
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.data.mapper.kartInfoOf
import com.mklhc.core.data.radio.AdapterState
import com.mklhc.core.data.radio.KartRadioAp
import com.mklhc.core.data.radio.PairingNetwork
import com.mklhc.core.data.store.PairingStore
import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.LinkState
import com.mklhc.core.domain.PairedKart
import com.mklhc.core.kart_protocol.fuji.FujiControlClient
import com.mklhc.core.kart_protocol.fuji.FujiEventChannel
import com.mklhc.core.kart_protocol.fuji.FujiPairingClient
import com.mklhc.core.kart_protocol.fuji.FujiPorts
import com.mklhc.core.kart_protocol.pairing.PAIRING_SEED_SIZE
import com.mklhc.core.kart_protocol.pairing.encodePairingPayload
import com.mklhc.core.kart_protocol.rcd.RcdClient
import com.mklhc.core.kart_protocol.rcd.RcdDevice
import com.mklhc.core.kart_protocol.rcd.RcdDeviceHandler
import com.mklhc.core.kart_protocol.rcd.RcdHandshakeService
import com.mklhc.core.kart_protocol.rcd.RcdServer
import com.mklhc.core.kart_protocol.rcd.asRcdTransport
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.teleop.TeleopEncoder
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.kart_protocol.telemetry.TelemetryDecoder
import com.mklhc.core.kart_protocol.video.LspVideoReassembler
import com.mklhc.core.kart_protocol.video.Lvni
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.DhcpServer
import com.mklhc.core.netstack.NetstackConfig
import com.mklhc.core.netstack.NetstackNode
import com.mklhc.core.netstack.UdpEndpoint
import com.mklhc.core.netstack.tcp.TcpConnection
import com.mklhc.core.netstack.tcp.TcpListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The orchestration capstone: it brings the radio and access point up, runs the network stack on the
 * link, and drives the kart through the pairing and session sequences, publishing where it stands as a
 * [LinkState] so the UI can follow along. It is the production [KartSession]; the feature ViewModels depend on
 * that interface and tests substitute [FakeKartSession].
 *
 * Business logic: this is the one place the lower layers meet. It owns none of the protocols; it wires
 * them. OUR app hosts the RCD SERVER (the kart connects in), and once the handshake completes the app
 * turns around and becomes a CLIENT of the kart's own services (control, event, pairing) and the sink of
 * its telemetry and video. The sequence, end to end:
 *
 * ```
 * adapter -> radio up -> netstack on the link -> beacon GAME
 *   -> kart associates (from here on, the kart leaving the network loses the session)
 *   -> kart connects to RCD 5202 -> handshake completes
 *   -> app connects to the kart's control (5103) and event (5107) ports
 *   -> identify (system info + product code) -> announce our ports (connection_info)
 *   -> bind telemetry (5116) and video (5016), listen video control (5032, LVNI keepalive)
 *   -> put the kart in drive -> stream 30 Hz teleop
 * ```
 *
 * Pairing runs the same way but first beacons the pairing network, serves the handshake with pairing on,
 * hands the kart the game-network credentials, and then falls into the session sequence; the paired kart
 * is remembered once it is identified on the game network.
 *
 * Departure: the kart can drop off the access point at any time (power cycled, reset after a failed step, out
 * of range), usually without closing its connections, so TCP would notice only seconds later, or never while the
 * sequence waits in an accept or a handshake. Once the kart is associated, a watcher per session turns a departure
 * the access point reports into a session loss at once, and every setup step that can block (the accept, the
 * handshake, the connects, the RCD calls) ends as soon as the session is lost. The access point reports what it
 * sees on the air: the kart's deauthentication or disassociation, or the kart authenticating again after a silent
 * power cycle. A kart that powers off or leaves range without sending a frame stays associated, and is noticed only
 * when TCP gives up on its connections (retransmission timeout or keepalive), which closes the RCD control channel
 * and loses the session the usual way; see the `TODO(access point)` on [Session.watchDeparture]. The loss then takes
 * the usual path: [LinkState.Recovering], teardown, the lease freed, and the kart awaited again, after a departure
 * without the recovery backoff (waiting for the kart to associate again already paces the loop).
 *
 * While pairing, any session loss before the SetGroupInfo call starts (a departure, the RCD control channel closing,
 * or the stale RCD connection reset by the kart's reconnect) ends only that attempt: it is torn down and the app
 * waits for the kart again on the same pairing network, with the same QR. Any other failure ends the pairing, and
 * [cancelPairing] / [stop] are the way out of a kart that keeps failing. Once the SetGroupInfo call is under way,
 * the kart leaving is its expected reset onto the game network, not a loss.
 *
 * Safety is woven through: a deadman repeats the last drive command for at most [SessionConfig.deadmanTimeout]
 * and otherwise sends neutral, any loss of the RCD control channel drives [LinkState.Recovering] with the
 * teleop forced neutral and the session torn down before it is retried, and [stop] / [sleepKart] put the
 * kart to sleep and send a final neutral when the kart is still reachable.
 *
 * Threading: like the layers it composes this class is NOT thread-safe and must be driven from a
 * single-threaded [scope] (in production a `limitedParallelism(1)` dispatcher); [setDriveCommand] is the
 * one exception, written to be safe from any thread. Everything it launches is a child of [scope], so
 * [stop] (or cancelling [scope]) ends every coroutine and timer it started.
 *
 * @param radioAp the radio + access-point edge the network stack runs on.
 * @param pairingStore the installation identity, paired kart and user settings.
 * @param crypto the crypto primitives the identity and RCD handshake build on.
 * @param scope the single-threaded scope the orchestrator and every loop and timer run on.
 * @param clock Unix time in seconds, supplied for `connection_info` and other timestamps.
 * @param config the ports and timings the session is driven with.
 * @param timeSource the monotonic source the deadman and the connect window measure elapsed real time
 *   against; [TimeSource.Monotonic] in production, the test scheduler's virtual source under `runTest`
 *   (the real monotonic clock does not advance with virtual time, so it must be injected for tests).
 */
class KartSessionRepository(
    private val radioAp: KartRadioAp,
    private val pairingStore: PairingStore,
    private val crypto: CryptoPrimitives = CryptoPrimitives(),
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val config: SessionConfig = SessionConfig(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : KartSession {
    private val log = Logger.withTag(TAG)

    // Everything the repository launches hangs off this supervisor so one failing loop never takes the
    // others down and stop() can cancel the whole subtree without touching the caller's scope.
    private val repositoryJob = SupervisorJob(scope.coroutineContext[Job])
    private val repositoryScope = CoroutineScope(scope.coroutineContext + repositoryJob)

    private val _linkState = MutableStateFlow<LinkState>(LinkState.NoAdapter)

    /** Where the link currently stands; the UI follows this to show progress and recovery. */
    override val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _kartInfo = MutableStateFlow<KartInfo?>(null)

    /** What the app has learned about the identified kart, or null before it is identified. */
    override val kartInfo: StateFlow<KartInfo?> = _kartInfo.asStateFlow()

    private val _telemetry = MutableStateFlow<Telemetry?>(null)

    /** The most recent decoded telemetry datagram, of any kind, or null before the first one. */
    override val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    private val _battery = MutableStateFlow<Telemetry.BatteryStatus?>(null)

    /** The most recent battery/charging status, or null before the first one. */
    override val battery: StateFlow<Telemetry.BatteryStatus?> = _battery.asStateFlow()

    // DROP_OLDEST, not the default SUSPEND: these are emitted from the telemetry / video read loops, so a
    // slow or absent collector must never back-pressure those loops and stall the live telemetry StateFlow.
    private val _imu = MutableSharedFlow<Telemetry.Imu>(
        extraBufferCapacity = IMU_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The IMU telemetry packets as they arrive. */
    override val imu: Flow<Telemetry.Imu> = _imu.asSharedFlow()

    private val _videoAccessUnits = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = VIDEO_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Reassembled H.264 access units as they are completed from the kart's video stream. */
    override val videoAccessUnits: Flow<ByteArray> = _videoAccessUnits.asSharedFlow()

    private val _pairingQr = MutableStateFlow<ByteArray?>(null)

    /** The pairing QR payload while the app is beaconing the pairing network, or null otherwise. */
    override val pairingQr: StateFlow<ByteArray?> = _pairingQr.asStateFlow()

    // The latest command the UI set and whether it is fresh since the teleop loop last looked. Both are
    // read and written from any thread (setDriveCommand), so both are volatile; a reference and a boolean
    // write are atomic, which is all the deadman accounting needs.
    @Volatile
    private var latestDriveCommand: DriveCommand = DriveCommand.NEUTRAL

    @Volatile
    private var driveCommandFresh: Boolean = false

    @Volatile
    private var captureEnabled: Boolean = false

    // The user settings applied when encoding a drive command; snapshotted and then kept live by an
    // observer for the duration of a session, so the 30 Hz loop never does I/O.
    @Volatile
    private var currentSettings: KartSettings = KartSettings.DEFAULT

    // The current session's control client, so sleepKart / shutdownKart / stop can reach the kart.
    @Volatile
    private var currentControl: FujiControlClient? = null

    @Volatile
    private var currentSession: Session? = null

    @Volatile
    private var node: NetstackNode? = null

    @Volatile
    private var orchestratorJob: Job? = null

    /**
     * The running network stack's DHCP server, or null while no stack runs. Only this module's tests read it, to
     * check that the kart's lease is freed when a session or a pairing attempt ends.
     */
    internal val dhcpServer: DhcpServer? get() = node?.dhcpServer

    /**
     * Begins the normal session flow: ensure the adapter, start the radio, run the network stack, beacon
     * the game network and drive the session (with automatic recovery) until [stop]. Launches the work and
     * returns; progress is published through [linkState]. Calling it again restarts the flow.
     */
    override suspend fun start() {
        log.i { "start(): starting the session flow" }
        relaunchOrchestrator(startWithPairing = false)
    }

    /**
     * Begins the pairing flow: beacon the pairing network, serve the pairing handshake, hand the kart the
     * game credentials and then fall into the normal session. The pairing QR payload is published through
     * [pairingQr] while the pairing network is up.
     */
    override suspend fun startPairing() {
        log.i { "startPairing(): starting the pairing flow" }
        relaunchOrchestrator(startWithPairing = true)
    }

    /**
     * Cancels an in-progress pairing (or session) attempt: clears the pairing QR at once, then cancels the
     * orchestrator, brings the pairing beacon and the network stack down so the radio is not left advertising
     * the pairing network, and settles the link on a terminal state so the UI is not stuck on
     * [LinkState.Beaconing]. A later [start] / [startPairing] restarts the flow.
     */
    override fun cancelPairing() {
        log.i { "cancelPairing(): cancelling" }
        _pairingQr.value = null
        val cancelled = orchestratorJob
        orchestratorJob = null
        repositoryScope.launch {
            cancelled?.cancelAndJoin()
            withContext(NonCancellable) {
                try {
                    node?.stop()
                } catch (failure: Exception) {
                    log.w { "cancelPairing(): stopping the node failed -> message=${failure.message}" }
                }
                node = null
                try {
                    radioAp.stop()
                } catch (failure: Exception) {
                    log.w { "cancelPairing(): stopping the beacon failed -> message=${failure.message}" }
                }
            }
            setState(LinkState.Failed("pairing cancelled"))
        }
    }

    /**
     * Sets the latest drive command the teleop loop will send. Safe to call from any thread; it only
     * records the command, which the 30 Hz loop picks up on its next tick.
     */
    override fun setDriveCommand(command: DriveCommand) {
        latestDriveCommand = command
        driveCommandFresh = true
    }

    /** Puts the kart to sleep (stops accepting drive) and forces a neutral command, when it is reachable. */
    override suspend fun sleepKart() {
        log.i { "sleepKart(): parking the kart" }
        runGuarded("sleepKart forcing neutral") { currentSession?.forceNeutral() }
        runGuarded("sleepKart parking the kart") { currentControl?.setState(FujiControlClient.STATE_SLEEP) }
    }

    /** Powers the kart off, when it is reachable. */
    override suspend fun shutdownKart() {
        log.i { "shutdownKart(): powering the kart off" }
        runGuarded("shutdownKart forcing neutral") { currentSession?.forceNeutral() }
        runGuarded("shutdownKart powering the kart off") { currentControl?.shutdown() }
    }

    /** Enables or disables packet capture (a flag only for now). */
    override fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled = enabled
        log.i { "setCaptureEnabled(): capture is now -> enabled=$enabled" }
    }

    /**
     * Stops the session: sleeps the kart and sends a final neutral if reachable, then tears everything
     * down. This is terminal; after it every coroutine the repository started has ended and the instance
     * is not restarted.
     */
    override suspend fun stop(): Unit = withContext(NonCancellable) {
        // All of teardown runs under NonCancellable so a cancelled caller (a closing screen scope) can never
        // abort it part-way and leave the node, radio or repository coroutines standing. Inside this block
        // cancellation is not thrown at suspension points, so the safety calls catch only real failures.
        log.i { "stop(): stopping the session" }
        val shutdownOnExit = currentSettings.shutdownOnExit
        try {
            currentSession?.forceNeutral()
        } catch (failure: Exception) {
            log.w { "stop(): forcing a final neutral failed -> message=${failure.message}" }
        }
        try {
            // Honour the user's end-of-session preference: power the kart off, or just park it.
            if (shutdownOnExit) currentControl?.shutdown() else currentControl?.setState(FujiControlClient.STATE_SLEEP)
        } catch (failure: Exception) {
            log.w { "stop(): parking or powering off the kart failed -> shutdownOnExit=$shutdownOnExit message=${failure.message}" }
        }
        try {
            orchestratorJob?.cancelAndJoin()
        } catch (failure: Exception) {
            log.w { "stop(): cancelling the orchestrator failed -> message=${failure.message}" }
        }
        orchestratorJob = null
        try {
            node?.stop()
        } catch (failure: Exception) {
            log.w { "stop(): stopping the node failed -> message=${failure.message}" }
        }
        node = null
        try {
            radioAp.stop()
        } catch (failure: Exception) {
            log.w { "stop(): stopping the radio failed -> message=${failure.message}" }
        }
        repositoryJob.cancel()
    }

    // ---- orchestration ------------------------------------------------------------------------

    private suspend fun relaunchOrchestrator(startWithPairing: Boolean) {
        orchestratorJob?.cancelAndJoin()
        withContext(NonCancellable) { runCatching { node?.stop() } }
        node = null
        orchestratorJob = repositoryScope.launch { orchestrate(startWithPairing) }
    }

    private suspend fun orchestrate(startWithPairing: Boolean) {
        var localNode: NetstackNode? = null
        try {
            awaitAdapterReady()
            setState(LinkState.RadioStarting)
            radioAp.start()
            val identity = deriveKartIdentity(pairingStore.getMasterSecret(), crypto)
            val bssid = pairingStore.getApBssid()
            localNode = NetstackNode(
                frameLink = radioAp.frameLink,
                scope = repositoryScope,
                config = NetstackConfig(ourIp = AddressPlan.AP, ourMac = bssid),
            ).also { it.start() }
            node = localNode

            var persistPaired = false
            if (startWithPairing) {
                runPairingSequence(localNode, identity)
                persistPaired = true
            }

            var backoff = config.recoveryBackoffInitial
            while (coroutineContext.isActive) {
                try {
                    radioAp.beacon(KartNetworkKind.GAME)
                    setState(LinkState.Beaconing(KartNetworkKind.GAME))
                    // A session only ever ends by a loss, so its progress is reported as it happens: once the paired
                    // kart is saved it is not saved again, and a session that reached driving restarts the backoff.
                    runGameSession(
                        node = localNode,
                        identity = identity,
                        persistPaired = persistPaired,
                        onPairedKartSaved = { persistPaired = false },
                        onDriving = { backoff = config.recoveryBackoffInitial },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (lost: SessionLostException) {
                    if (lost.departure) {
                        // No backoff after a departure: waiting for the kart to associate again already paces the
                        // loop, and on hardware the kart's re-association and first SYN arrive right as the loss is
                        // raised, so a backoff would only keep its connect waiting.
                        log.w { "orchestrate(): the kart left, waiting for it again -> reason=${lost.message}" }
                        setState(LinkState.Recovering(lost.message ?: "link lost"))
                    } else {
                        log.w { "orchestrate(): session lost, recovering -> reason=${lost.message} backoff=$backoff" }
                        setState(LinkState.Recovering(lost.message ?: "link lost"))
                        backoff = backOff(backoff)
                    }
                } catch (failure: Exception) {
                    log.e(failure) { "orchestrate(): session failed, recovering -> message=${failure.message} backoff=$backoff" }
                    setState(LinkState.Recovering(failure.message ?: "session error"))
                    backoff = backOff(backoff)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: Exception) {
            log.e(fatal) { "orchestrate(): fatal error -> message=${fatal.message}" }
            setState(LinkState.Failed(fatal.message ?: "fatal error"))
        } finally {
            withContext(NonCancellable) { runCatching { localNode?.stop() } }
        }
    }

    /** Reflects the adapter state into the first rungs of the link state, returning once it is ready. */
    private suspend fun awaitAdapterReady() {
        radioAp.adapterState
            .onEach { state ->
                when (state) {
                    AdapterState.ABSENT -> setState(LinkState.NoAdapter)
                    AdapterState.PERMISSION_REQUIRED -> {
                        radioAp.requestAdapterPermission()
                        setState(LinkState.AwaitingUsbPermission)
                    }
                    AdapterState.READY -> Unit
                }
            }
            .first { it == AdapterState.READY }
    }

    /**
     * Publishes a fresh pairing QR, beacons the network it describes and runs pairing attempts on it until one hands
     * the kart the game-network credentials.
     *
     * Business logic: an attempt ends without the credentials when its session is lost before the SetGroupInfo call
     * starts (see [runPairingAttempt]). That ends only the attempt: its connections, its listener and the kart's lease
     * go, and the next attempt waits for the kart on the same network, with the QR still published and the link back
     * at [LinkState.Beaconing]. There is no attempt limit; [cancelPairing] and [stop] end the loop. Any other failure
     * propagates and ends the pairing.
     */
    private suspend fun runPairingSequence(node: NetstackNode, identity: KartIdentity) {
        val seed = crypto.secureRandomBytes(PAIRING_SEED_SIZE)
        val pairingSsid = pairingSsid()
        val channel = pairingStore.getChannel()
        _pairingQr.value = encodePairingPayload(seed, pairingSsid, channel)
        try {
            // The beacon carries the SAME ssid / seed / channel the QR advertises, so the access point stands up
            // exactly the network the kart is about to scan for; it derives the pairing PSK from the seed itself.
            radioAp.beacon(KartNetworkKind.PAIRING, PairingNetwork(pairingSsid, seed, channel))
            var attempt = 1
            while (true) {
                setState(LinkState.Beaconing(KartNetworkKind.PAIRING))
                val session = Session()
                currentSession = session
                val lostReason = try {
                    runPairingAttempt(node, identity, session)
                } finally {
                    // The station disassociates as it resets onto the game network, or it left part-way: either way
                    // free its lease for the next association, and tear the attempt down.
                    node.dhcpServer.reset()
                    session.teardown()
                }
                if (lostReason == null) return
                log.w { "runPairingSequence(): the attempt ended before the group info was sent, waiting for the kart again -> attempt=$attempt reason=$lostReason" }
                attempt++
            }
        } finally {
            currentSession = null
            _pairingQr.value = null
            resetDriveCommand()
        }
    }

    /**
     * Runs one pairing attempt on [session]: waits for the kart to associate with the pairing network, serves the
     * pairing handshake, connects to the kart's pairing service and hands it the game-network credentials.
     *
     * Business logic: until the SetGroupInfo call starts, any loss of the session ends the attempt, not the pairing:
     * the kart leaving the network (it resets after a failed step, or is power cycled), the RCD control channel
     * closing, or the stale RCD connection reset by the kart's reconnect. The departure watcher is armed once the
     * kart is associated, every blocking step ends as soon as the session is lost, and the attempt then returns the
     * reason so the caller tears it down and waits for the kart again. A failure while the session still stands (a
     * connect that times out or is refused, a programming error) propagates and ends the pairing, as before.
     *
     * The watcher is disarmed before the SetGroupInfo call: from then on the kart leaving is its expected reset onto
     * the game network, and the call's own outcome decides. A departure published just before the disarm whose watcher
     * had not run yet is cancelled with it, so the association is checked once more after the disarm.
     *
     * @return null once the credentials were handed over, or the reason the session was lost before the call.
     */
    private suspend fun runPairingAttempt(node: NetstackNode, identity: KartIdentity, session: Session): String? {
        radioAp.associatedStation.first { it }
        val departureWatch = session.watchDeparture()
        setState(LinkState.KartAssociated)

        val pairConn = try {
            val listener = node.tcp.listen(config.pairingRcdPort)
            session.register { listener.close() }
            val serverConn = session.untilLost {
                listener.accept().also { accepted -> session.register { runCatching { accepted.abort() } } }
            }
            setState(LinkState.KartLeased)

            session.untilLost { serveHandshakeAwaitConnect(session, serverConn, identity, pairing = true) }
            setState(LinkState.KartHandshaked)

            // One connect window opens here at handshake finalize and is shared by every connect that follows.
            val connectStart = timeSource.markNow()
            val connection = node.tcp.open(config.pairingLocalPort, AddressPlan.KART_LEASE, FujiPorts.PAIRING)
            session.register { runCatching { connection.abort() } }
            session.untilLost { connectWithin(connection, connectStart) }
            connection
        } catch (failure: Exception) {
            // CancellationException is an IllegalStateException: cancelPairing / stop always end the pairing.
            val programmingError = failure is IllegalArgumentException || failure is IllegalStateException
            if (programmingError || !session.lost.isCompleted) throw failure
            return session.lost.await()
        }

        // Disarmed before the call, with nothing in between: a loss seen until now ends the attempt, a departure from
        // here on is the kart resetting onto the game network with the credentials.
        departureWatch.cancel()
        if (session.lost.isCompleted) return session.lost.await()
        if (!radioAp.associatedStation.value) {
            log.w { "runPairingAttempt(): the kart left just before the group info call -> associated=false" }
            return STATION_LEFT_REASON
        }
        val pairingClient = FujiPairingClient(RcdClient(pairConn.asRcdTransport()))
        pairingClient.setGroupInfo(identity.gameSsid, identity.gamePsk)
        log.i { "runPairingAttempt(): group info sent, the kart will reset onto the game network" }
        return null
    }

    /**
     * Runs one game session until it is lost; it never returns normally, it ends with a [SessionLostException].
     *
     * @param persistPaired whether the kart identified in this session is the one just paired and must be saved.
     * @param onPairedKartSaved called once the paired kart has been saved, so later sessions do not save it again.
     * @param onDriving called when the session reaches [LinkState.Driving], so the caller can restart its backoff.
     */
    private suspend fun runGameSession(
        node: NetstackNode,
        identity: KartIdentity,
        persistPaired: Boolean,
        onPairedKartSaved: () -> Unit,
        onDriving: () -> Unit,
    ) {
        val session = Session()
        currentSession = session
        try {
            radioAp.associatedStation.first { it }
            // From here until the session ends, the kart leaving the network is a session loss.
            session.watchDeparture()
            setState(LinkState.KartAssociated)

            val listener = node.tcp.listen(config.sessionRcdPort)
            session.register { listener.close() }
            val serverConn = session.untilLost {
                listener.accept().also { accepted -> session.register { runCatching { accepted.abort() } } }
            }
            setState(LinkState.KartLeased)

            val device = session.untilLost { serveHandshakeAwaitConnect(session, serverConn, identity, pairing = false) }
            setState(LinkState.KartHandshaked)

            // Within the connect window the app turns into the client of the kart's own services. One window
            // opens here at handshake finalize and is shared by the control and event connects, so the two
            // together may take at most config.connectWindow, not that long each.
            val connectStart = timeSource.markNow()
            val controlConn = node.tcp.open(config.controlLocalPort, AddressPlan.KART_LEASE, FujiPorts.CONTROL)
            session.register { runCatching { controlConn.abort() } }
            session.untilLost { connectWithin(controlConn, connectStart) }
            val control = FujiControlClient(RcdClient(controlConn.asRcdTransport()))
            currentControl = control

            val eventConn = node.tcp.open(config.eventLocalPort, AddressPlan.KART_LEASE, FujiPorts.EVENT)
            session.register { runCatching { eventConn.abort() } }
            session.untilLost { connectWithin(eventConn, connectStart) }
            val eventChannel = FujiEventChannel(eventConn.asRcdTransport())
            session.launch { runGuarded("event channel") { eventChannel.run() } }

            val systemInfo = session.untilLost { control.getSystemInfo() }
            val productCode = session.untilLost { control.getProductCode() }
            _kartInfo.value = kartInfoOf(productCode, systemInfo)
            if (persistPaired) {
                val paired = PairedKart(productCode.serial, device.ident)
                pairingStore.setPairedKart(paired)
                onPairedKartSaved()
                log.i { "runGameSession(): remembered the paired kart -> serial=${paired.serial}" }
            }

            session.untilLost {
                control.setConnectionInfo(
                    telemetryPort = config.telemetryPort,
                    videoControlPort = config.videoControlPort,
                    videoStreamPort = config.videoStreamPort,
                    timestamp = clock(),
                )
            }

            val telemetryUdp = node.bindUdp(config.telemetryPort)
            session.register { telemetryUdp.close() }
            session.launch { telemetryLoop(telemetryUdp) }

            val videoUdp = node.bindUdp(config.videoStreamPort)
            session.register { videoUdp.close() }
            session.launch { videoLoop(videoUdp) }

            val lspListener = node.tcp.listen(config.videoControlPort)
            session.register { lspListener.close() }
            session.launch { lspKeepaliveLoop(session, lspListener) }

            val teleopUdp = node.bindUdp(config.teleopLocalPort)
            session.teleopUdp = teleopUdp
            session.register { teleopUdp.close() }

            // Snapshot the settings, then keep them live for the session so the drive loop does no I/O.
            currentSettings = pairingStore.getSettings()
            session.launch { pairingStore.observeSettings().collect { currentSettings = it } }

            session.untilLost { control.setState(FujiControlClient.STATE_DRIVE) }
            setState(LinkState.KartReady)

            // Safety: clear any command left over from a previous (pre-loss) session so the deadman path and
            // the first ticks send neutral until a genuinely fresh command arrives, never a stale throttle.
            resetDriveCommand()
            session.launch { teleopLoop(teleopUdp) }
            setState(LinkState.Driving)
            onDriving()

            session.awaitLoss()
        } finally {
            currentControl = null
            currentSession = null
            // Safety again on the way out: a torn-down session must leave no non-neutral command behind.
            resetDriveCommand()
            // The station disassociates on loss / teardown; free its lease so a reconnecting kart gets a fresh one.
            node.dhcpServer.reset()
            session.teardown()
        }
    }

    /**
     * Serves the RCD handshake on [serverConn] and suspends until the kart has completed it, returning the
     * connected device. The serve loop keeps running in the session scope; when it ends (the kart closed
     * the control channel) it signals the session lost. If the channel ends before the handshake completes,
     * the await fails with a [SessionLostException]. Callers run it through [Session.untilLost], so a session
     * lost otherwise (the kart left the network) ends the wait too.
     */
    private suspend fun serveHandshakeAwaitConnect(
        session: Session,
        serverConn: TcpConnection,
        identity: KartIdentity,
        pairing: Boolean,
    ): RcdDevice {
        val connected = CompletableDeferred<RcdDevice>()
        val handler = object : RcdDeviceHandler {
            override fun deviceConnected(device: RcdDevice) {
                connected.complete(device)
            }

            override fun deviceDisconnected(device: RcdDevice) {
                session.signalLost("RCD device disconnected")
            }
        }
        val serverInfo = identity.toRcdServerInfo(crypto)
        val service = RcdHandshakeService(
            serverInfo = serverInfo,
            peerAddress = serverConn.peer.toString(),
            handler = handler,
            pairing = pairing,
            crypto = crypto,
        )
        val server = RcdServer(serverConn.asRcdTransport(), listOf(service))
        session.launch {
            try {
                server.serve()
            } finally {
                session.signalLost("RCD control channel closed")
                if (!connected.isCompleted) {
                    connected.completeExceptionally(SessionLostException("RCD closed before the handshake completed"))
                }
            }
        }
        return connected.await()
    }

    private suspend fun telemetryLoop(udp: UdpEndpoint) {
        val decoder = TelemetryDecoder()
        try {
            while (true) {
                val datagram = udp.receive()
                val value = decoder.decode(datagram.payload)
                _telemetry.value = value
                when (value) {
                    is Telemetry.BatteryStatus -> _battery.value = value
                    is Telemetry.Imu -> _imu.emit(value)
                    is Telemetry.Unknown -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: ClosedReceiveChannelException) {
            log.d { "telemetryLoop(): telemetry endpoint closed" }
        }
    }

    private suspend fun videoLoop(udp: UdpEndpoint) {
        val reassembler = LspVideoReassembler()
        try {
            while (true) {
                val datagram = udp.receive()
                for (frame in reassembler.feed(datagram.payload)) {
                    _videoAccessUnits.emit(frame)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: ClosedReceiveChannelException) {
            log.d { "videoLoop(): video endpoint closed" }
        }
    }

    /**
     * Accepts the kart's video (LSP) control connection and sends it an LVNI keepalive once a second so the
     * kart keeps streaming. The frame-id fields are placeholders (their semantics are an open question in
     * the protocol): a running counter is sent so each record differs.
     */
    private suspend fun lspKeepaliveLoop(session: Session, listener: TcpListener) {
        try {
            val conn = listener.accept()
            session.register { runCatching { conn.abort() } }
            var frameId = 0L
            while (true) {
                val previous = if (frameId == 0L) 0L else frameId - 1
                try {
                    conn.write(Lvni.encode(previousFrameId = previous, currentFrameId = frameId, flags = 0))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.d { "lspKeepaliveLoop(): keepalive write failed, stopping -> message=${failure.message}" }
                    return
                }
                frameId += 1
                delay(config.lvniInterval)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (closed: ClosedReceiveChannelException) {
            log.d { "lspKeepaliveLoop(): video control listener closed" }
        }
    }

    private suspend fun teleopLoop(udp: UdpEndpoint) {
        val encoder = TeleopEncoder()
        val deadmanWindow = config.deadmanTimeout
        // Wall-clock, not a tick count: mark when a fresh command last arrived and measure real elapsed time
        // each tick, so the deadman fires on time (at the window) even if the dispatcher stalls between ticks.
        var lastFresh = timeSource.markNow()
        while (coroutineContext.isActive) {
            if (driveCommandFresh) {
                driveCommandFresh = false
                lastFresh = timeSource.markNow()
            }
            val driving = _linkState.value is LinkState.Driving
            val deadman = lastFresh.elapsedNow() >= deadmanWindow
            val command = if (!driving || deadman) {
                DriveCommand.NEUTRAL
            } else {
                applyDriveSettings(latestDriveCommand, currentSettings)
            }
            runGuarded("teleop send") { udp.send(AddressPlan.KART_LEASE, config.kartDrivePort, encoder.encode(command)) }
            delay(config.teleopInterval)
        }
    }

    /**
     * Connects [connection] within what is left of the single connect window that opened at [started], so the
     * control, event and pairing connects share one budget rather than each getting the full window.
     */
    private suspend fun connectWithin(connection: TcpConnection, started: TimeMark) {
        val remaining = config.connectWindow - started.elapsedNow()
        if (remaining <= Duration.ZERO) {
            throw SessionLostException("the connect window of ${config.connectWindow} elapsed before connecting to the kart")
        }
        try {
            withTimeout(remaining) { connection.connect() }
        } catch (timedOut: TimeoutCancellationException) {
            throw SessionLostException("timed out connecting to the kart within ${config.connectWindow}")
        }
    }

    private suspend fun runGuarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.w { "runGuarded(): $what ended -> message=${failure.message}" }
        }
    }

    /**
     * Waits out the current recovery [backoff] and returns the next one, a capped exponential. Keeps the
     * recovery loop from busy-spinning (and flooding the log) when beacon / listen fail immediately, while
     * [delay] still lets cancellation propagate so [stop] ends the loop at once.
     */
    private suspend fun backOff(backoff: Duration): Duration {
        delay(backoff)
        val next = backoff * 2
        return if (next > config.recoveryBackoffMax) config.recoveryBackoffMax else next
    }

    /** Resets the latest drive command to neutral so a pre-loss command is never replayed on a new session. */
    private fun resetDriveCommand() {
        latestDriveCommand = DriveCommand.NEUTRAL
        driveCommandFresh = false
    }

    private suspend fun setState(state: LinkState) {
        if (_linkState.value == state) return
        _linkState.value = state
        log.d { "setState(): -> state=$state" }
        // Yield so a collector on the single dispatcher observes every phase, not only the latest; the UI
        // relies on seeing each rung of the ladder.
        yield()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun pairingSsid(): String = PAIRING_SSID_PREFIX + Base64.Default.encode(crypto.secureRandomBytes(SSID_RANDOM_SIZE))

    /**
     * Raised when an established session is lost; the orchestrator turns it into [LinkState.Recovering].
     *
     * @param departure true when the loss was the kart leaving the network, which the recovery loop does not back
     *   off from.
     */
    private class SessionLostException(message: String, val departure: Boolean = false) : Exception(message)

    /**
     * One session's launched work and sockets, and the single "lost" signal that ends it.
     *
     * Everything a session opens is registered here so [teardown] can cancel the loops, send one last
     * neutral and close the sockets even when the surrounding scope is being cancelled. The sequence runs its
     * blocking setup steps through [untilLost], and [watchDeparture] makes the kart leaving the network one of
     * the ways the session is lost.
     */
    private inner class Session {
        private val job = SupervisorJob(repositoryJob)
        private val scope = CoroutineScope(repositoryScope.coroutineContext + job)
        private val closeables = ArrayList<suspend () -> Unit>()

        /** Completes with the reason the moment the session is lost; [awaitLoss] reports it. */
        val lost = CompletableDeferred<String>()

        // True when the session was lost because the kart left the network (the departure came first).
        private var stationLeft: Boolean = false

        /** The teleop endpoint, so a safety neutral can be sent on teardown. */
        @Volatile
        var teleopUdp: UdpEndpoint? = null

        fun register(close: suspend () -> Unit) {
            closeables += close
        }

        fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

        fun signalLost(reason: String) {
            lost.complete(reason)
        }

        // TODO(access point): a station inactivity timeout in the access point (drop the station once it has sent
        //  nothing for a few seconds) would report the silent departures too. Today a kart that powers off or leaves
        //  range without a frame stays associated, and its session ends only when TCP retransmission or keepalive
        //  gives up on the RCD control channel; a sequence waiting in an accept waits until the kart returns.
        /**
         * Arms the departure watcher: from now until the session ends, or until the returned job is cancelled, a
         * departure the access point reports signals the session lost. The access point reports the station's
         * deauthentication or disassociation, and the station authenticating again (a kart that power cycled without
         * a word and rejoins); a station that goes silent without a frame is not reported. Call it once the station
         * is associated; a station already gone by then is caught at once ([KartRadioAp.associatedStation] replays
         * its value).
         */
        fun watchDeparture(): Job = launch {
            radioAp.associatedStation.first { associated -> !associated }
            if (lost.isCompleted) return@launch
            stationLeft = true
            log.w { "watchDeparture(): the kart left the network, ending the session -> reason=$STATION_LEFT_REASON" }
            signalLost(STATION_LEFT_REASON)
        }

        /** Suspends until the session is lost, then throws the [SessionLostException] that reports the loss. */
        suspend fun awaitLoss(): Nothing {
            val reason = lost.await()
            throw SessionLostException(reason, departure = stationLeft)
        }

        /**
         * Runs one setup [step] that can block (an accept, a handshake await, a connect, an RCD call) but ends it
         * with a [SessionLostException] as soon as the session is lost, so a lost session never leaves the sequence
         * waiting on something that can no longer complete.
         *
         * Business logic: a session already lost never starts the step. Otherwise the step runs in the caller's
         * coroutine while a watcher child waits in [awaitLoss]; a loss fails the scope with the watcher's
         * [SessionLostException], which cancels the step. A step that finishes first returns its value (a loss right
         * after is seen by the next step or the final await), a step that fails first fails with its own exception,
         * and a cancellation from outside propagates as usual. The step must be cancellable: whatever it runs under
         * [NonCancellable] delays the loss until it returns. Anything the step creates that the session must clean up
         * (an accepted connection) is registered inside the step, so it is torn down whichever way the race between
         * the step and the loss goes.
         */
        suspend fun <T> untilLost(step: suspend () -> T): T {
            if (lost.isCompleted) awaitLoss()
            return coroutineScope {
                val watcher = launch { awaitLoss() }
                step().also { watcher.cancel() }
            }
        }

        /** Sends one neutral drive datagram, if the teleop endpoint is still open, for the deadman path. */
        suspend fun forceNeutral() {
            val udp = teleopUdp ?: return
            runGuarded("session forcing neutral") { udp.send(AddressPlan.KART_LEASE, config.kartDrivePort, TeleopEncoder().neutral()) }
        }

        /** Cancels the loops, forces a last neutral and closes every socket; runs even under cancellation. */
        suspend fun teardown() = withContext(NonCancellable) {
            job.cancel()
            forceNeutral()
            for (close in closeables.reversed()) runCatching { close() }
            closeables.clear()
        }
    }

    private companion object {
        const val TAG = "KartSessionRepository"

        /** The pairing network's SSID prefix; the tail is random, base64-encoded. */
        const val PAIRING_SSID_PREFIX = "mklhc-pair_"

        /** Why a session ends when the kart leaves the access point; shown in [LinkState.Recovering]. */
        const val STATION_LEFT_REASON = "the kart left the network"

        // Design 8.5: the pairing SSID's random tail is 12 octets (base64 to 16 characters after the prefix).
        const val SSID_RANDOM_SIZE = 12
        const val IMU_BUFFER = 32
        const val VIDEO_BUFFER = 64
    }
}

/**
 * Shapes a raw drive command by the user's settings: the throttle is scaled to [KartSettings.maxThrottlePercent]
 * of full, the steering is softened around centre by the cubic expo curve and then offset by the integer trim,
 * and both axes are clamped to the signed-octet range (-128..127).
 *
 * Business logic: this is the one place the user's preferences meet the wire command, pulled out of the 30 Hz
 * loop as a pure function so the scaling, the expo curve, the trim and the clamping can be unit-tested directly
 * without standing up a session. It holds no state and reads no clock, so the same inputs always map to the
 * same output.
 */
internal fun applyDriveSettings(command: DriveCommand, settings: KartSettings): DriveCommand {
    val throttle = (command.throttle * settings.maxThrottlePercent / DRIVE_PERCENT).coerceIn(DRIVE_AXIS_MIN, DRIVE_AXIS_MAX)

    val expo = settings.steeringExpoPercent / DRIVE_PERCENT.toDouble()
    val normalized = (command.steering / DRIVE_AXIS_MAX.toDouble()).coerceIn(-1.0, 1.0)
    val curved = (1 - expo) * normalized + expo * normalized * normalized * normalized
    val trimOffset = settings.steeringTrimPercent * DRIVE_AXIS_MAX / DRIVE_PERCENT
    val steering = ((curved * DRIVE_AXIS_MAX).roundToInt() + trimOffset).coerceIn(DRIVE_AXIS_MIN, DRIVE_AXIS_MAX)

    return DriveCommand(throttle = throttle, steering = steering, brakeLight = command.brakeLight)
}

private const val DRIVE_PERCENT = 100
private const val DRIVE_AXIS_MIN = -128
private const val DRIVE_AXIS_MAX = 127
