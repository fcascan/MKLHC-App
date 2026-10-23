package com.mklhc.app.desktop

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.radio.AdapterState
import com.mklhc.core.data.radio.RadioKartRadioAp
import com.mklhc.core.data.radio.RadiodUsbAdapterRepository
import com.mklhc.core.data.radio.UsbAdapterRepository
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.data.session.KartSessionRepository
import com.mklhc.core.data.store.DataStorePairingStore
import com.mklhc.core.data.store.PairingStore
import com.mklhc.core.data.store.createPairingDataStore
import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.radio.DesktopRadioLink
import com.mklhc.core.radio.ProcessRadiodLauncher
import com.mklhc.core.radio.RadiodConfig
import com.mklhc.core.radio.RadiodLauncher
import com.mklhc.core.radio.RadiodSupervisor
import com.mklhc.core.wifi_ap.ap.RadioAccessPoint
import com.mklhc.core.wifi_ap.ap.RadioOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The desktop composition of the real kart session: radiod, the radio link, the access point, the pairing store and the
 * session orchestrator, wired for the life of the app.
 *
 * Original code. It mirrors the radio hardware spike's wiring (app/src/desktopMain/kotlin/com/mklhc/app/spike/
 * SpikeSession.kt: the socket in a short temporary directory, the random session token, DesktopRadioLink,
 * RadiodSupervisor over ProcessRadiodLauncher, one dispatcher that runs one task at a time, and a teardown that lets
 * radiod exit by itself before the supervisor is stopped), with the spike's throwaway pairing network replaced by the
 * production KartSessionRepository over RadioKartRadioAp and the persistent DataStorePairingStore.
 */

/**
 * The real kart session on the desktop, and everything it runs on, for the life of the app.
 *
 * Business logic: the UI binds [session]; this class owns what is under it.
 *
 * - Construction wires everything and starts nothing: the socket's temporary directory is created (radiod's AF_UNIX
 *   path must stay short), a random token is drawn for radiod's HELLO, and the radio link, the radiod supervisor, the
 *   access point over the link, the pairing store in the data directory, the USB adapter repository ([adapters]), the
 *   radio unit and the [KartSessionRepository] are built on it. The app creates and locks the data
 *   directory before building the runtime (see [DesktopDataDirectory]), so one runtime at a time uses it. When
 *   [DesktopRadioSettings.videoDumpDirectory] is set, a [VideoDatagramDump] is opened there and the session hands it
 *   every video datagram; a folder that cannot be used is logged at error and the app runs without the dump.
 * - [adapters] lists the USB devices radiod sees (a scan when radiod connects, then every 2 s while the radio is down)
 *   and resolves the user's choice (automatic by default, persisted in the pairing file). The adapter is "ready" while
 *   radiod is connected and an adapter resolves, and "absent" otherwise, so the UI shows the no-adapter state until
 *   radiod is up and a usable adapter is plugged in (and for good when radiod is missing); a Start that waits there
 *   goes on by itself once the adapter shows up. The radio unit waits for the connection, resolves the adapter afresh
 *   before every bring-up, and brings the radio up again (same network, same keys) when the supervisor has relaunched
 *   radiod after a crash.
 * - [start] listens on the socket and starts the supervisor, which launches radiod and relaunches it when it dies. A
 *   missing radiod executable is logged at error with its path and the variable that sets it, and the radio stays
 *   off; the demo is never used instead. The session itself starts when the UI asks for it (Start, or the Pairing
 *   screen).
 * - [shutdown] is the end of the app, called after `KartSession.stop()` (which puts the kart to sleep, or powers it off
 *   per the user's setting, and sends radiod STOP_BEACON and SHUTDOWN). It closes the link first: radiod shuts the
 *   radio down and exits on the end of its connection exactly as on SHUTDOWN (radiod's main.cpp), so a radiod the
 *   session never used exits at once too, instead of being waited for and killed. It then waits briefly for the
 *   supervisor to see radiod exit and stops it before its restart backoff can launch radiod again, cancels everything
 *   this class started, releases the pairing file, removes the socket and its directory, and closes the video dump.
 *
 * Threading: everything that touches the access point, the network stack, the adapter scans or the session repository
 * runs on the runtime's dispatcher, `Dispatchers.Default.limitedParallelism(1)`, as those classes require: one task at
 * a time, in order, each seeing what the previous ones wrote, though not always on the same pool thread. [session]
 * moves the UI's commands onto it; [adapters] is safe from any coroutine.
 *
 * Secrets: the token travels on radiod's command line and in HELLO only; it, the master secret and the network keys are
 * never logged. The pairing file holds the master secret in the clear, in the data directory (see
 * [DataStorePairingStore]), which the app creates readable by its owner only on macOS and Linux.
 *
 * @param settings the radiod executable, the data directory and the video dump folder.
 * @param launcher how radiod is started; replaceable so a test can run a fake radiod in-process.
 * @param crypto the crypto provider for the token, the identity, the keys and the RCD handshake.
 * @param clock Unix time in seconds, for the session's timestamps.
 * @param log the logger of the runtime's own milestones; injectable so a test can assert them.
 */
class DesktopKartRuntime(
    private val settings: DesktopRadioSettings,
    launcher: RadiodLauncher = ProcessRadiodLauncher(),
    crypto: CryptoPrimitives = CryptoPrimitives(),
    clock: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    private val log: Logger = Logger.withTag(TAG),
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // The pairing DataStore runs its file I/O here; cancelling it releases the file for the next DataStore on it.
    private val storeJob = SupervisorJob()
    private val storeScope = CoroutineScope(storeJob + Dispatchers.IO)

    // AF_UNIX socket paths are limited to about 100 bytes, so the socket goes in a short temporary directory.
    private val socketDirectory: Path = Files.createTempDirectory(WORK_DIR_PREFIX)
    private val socketPath: Path = socketDirectory.resolve(SOCKET_FILE_NAME)
    private val token: String = crypto.secureRandomBytes(TOKEN_BYTES).toHexString()

    private val link = DesktopRadioLink(socketPath, token, scope)
    private val supervisor = RadiodSupervisor(
        config = RadiodConfig(settings.radiodExecutable, socketPath, token),
        scope = scope,
        launcher = launcher,
    )

    /** The installation's identity, paired kart and settings, in the data directory. Tests read it directly. */
    internal val pairingStore: PairingStore = DataStorePairingStore(
        dataStore = createPairingDataStore(settings.pairingStorePath.toString(), storeScope),
        crypto = crypto,
    )

    /** The USB devices radiod sees, the user's choice among them and the adapter the radio opens; the screen binds it. */
    val adapters: UsbAdapterRepository = RadiodUsbAdapterRepository(
        link = link,
        store = pairingStore,
        scope = scope,
        radiodConnected = link.connected,
    )

    // READY while radiod is connected and an adapter resolves: the session waits in NoAdapter until one is usable, and
    // goes on by itself when one is plugged in (the repository scans every 2 s while the radio is down).
    private val adapterState: StateFlow<AdapterState> = combine(link.connected, adapters.overview) { connected, overview ->
        if (connected && overview.resolution is AdapterResolution.Use) AdapterState.READY else AdapterState.ABSENT
    }.stateIn(scope, SharingStarted.Eagerly, AdapterState.ABSENT)

    // Opened with the runtime when MKLHC_VIDEO_DUMP names a folder; one that cannot be used leaves the dump off.
    private val videoDump: VideoDatagramDump? = settings.videoDumpDirectory?.let(::openVideoDumpOrNull)

    private val repository = KartSessionRepository(
        radioAp = RadioKartRadioAp(
            accessPoint = RadioAccessPoint(
                radio = link,
                options = RadioOptions(),
                primitives = crypto,
            ),
            pairingStore = pairingStore,
            scope = scope,
            adapterState = adapterState,
            adapterRepository = adapters,
            awaitRadioConnected = { link.connected.first { it } },
            radioConnection = link.connected,
            crypto = crypto,
        ),
        pairingStore = pairingStore,
        crypto = crypto,
        scope = scope,
        clock = clock,
        videoDatagramTap = videoDump?.let { dump -> dump::write },
    )

    /** The session the UI binds: the real one, with its commands moved onto the runtime's dispatcher. */
    val session: KartSession = ConfinedKartSession(repository, dispatcher, scope)

    private val started = AtomicBoolean(false)
    private val shutDown = AtomicBoolean(false)

    // True once the supervisor runs, so the shutdown only waits for a radiod that was launched.
    @Volatile
    private var supervising = false

    /**
     * Listens on the socket and starts supervising radiod; radiod connects by itself once launched. Does nothing when
     * already started or shut down.
     *
     * Business logic: a radiod executable that does not exist, and a socket that cannot be bound, are logged at error
     * and leave the radio off: the UI then shows the no-adapter state, which is what the user has to fix. Nothing
     * falls back to the demo. Only cancellation is thrown.
     */
    suspend fun start() {
        if (shutDown.get()) {
            log.w { "start(): the runtime is shut down, not starting" }
            return
        }
        if (!started.compareAndSet(false, true)) {
            log.d { "start(): already started" }
            return
        }
        val radiod = settings.radiodExecutable
        if (!Files.isRegularFile(radiod)) {
            log.e {
                "start(): radiod was not found, the radio stays off -> path=$radiod " +
                    "(build radiod, see docs/HARDWARE_SETUP.md, or set ${DesktopRadioSettings.ENV_RADIOD} to its path)"
            }
            return
        }
        try {
            // Before radiod is launched, so the socket exists when it connects.
            link.listen()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.e(failure) { "start(): the radio socket could not be opened, the radio stays off -> socketPath=$socketPath message=${failure.message}" }
            return
        }
        supervisor.start()
        supervising = true
        log.i { "start(): supervising radiod -> radiod=$radiod" }
    }

    /**
     * Ends the runtime: closes the link, lets radiod exit and stops the supervisor before it relaunches radiod, cancels
     * everything the runtime started, releases the pairing file and removes the socket and its directory. Call it after
     * `KartSession.stop()`. Idempotent; never throws, and runs to completion even when the caller is cancelled. Each
     * wait is bounded, so it ends within a few seconds.
     */
    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        if (!shutDown.compareAndSet(false, true)) {
            log.d { "shutdown(): already shut down" }
            return@withContext
        }
        log.i { "shutdown(): shutting the radio runtime down -> supervising=$supervising" }
        // radiod shuts the radio down and exits on the end of its connection as on SHUTDOWN; a SHUTDOWN already sent by
        // the session's stop was written before this close, so it is read first.
        bestEffort("closing the radio link") { link.close() }
        if (supervising) {
            bestEffort("stopping radiod supervision") {
                // Stopped in the window between radiod's exit and the supervisor's restart backoff, so radiod is not
                // launched again; a radiod that does not exit in time (busy bringing the radio up) is killed.
                val exited = withTimeoutOrNull(RADIOD_EXIT_GRACE_MS) {
                    supervisor.state.first { it is RadiodSupervisor.State.WaitingToRestart }
                } != null
                if (!exited) log.w { "shutdown(): radiod did not exit in time, stopping it -> timeoutMs=$RADIOD_EXIT_GRACE_MS" }
                supervisor.stop()
            }
        }
        scope.cancel()
        bestEffort("releasing the pairing file") {
            if (withTimeoutOrNull(STORE_RELEASE_TIMEOUT_MS) { storeJob.cancelAndJoin() } == null) {
                log.w { "shutdown(): the pairing file was not released in time -> timeoutMs=$STORE_RELEASE_TIMEOUT_MS" }
            }
        }
        bestEffort("removing the socket directory") {
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(socketDirectory)
        }
        videoDump?.close()
        log.i { "shutdown(): radio runtime shut down" }
    }

    /** Opens the video dump in [directory], or logs why it cannot and returns null so the app runs without it. */
    private fun openVideoDumpOrNull(directory: Path): VideoDatagramDump? = try {
        VideoDatagramDump.open(directory)
    } catch (failure: IOException) {
        log.e {
            "openVideoDumpOrNull(): the video dump cannot be opened, running without it -> directory=$directory " +
                "message=${failure.message} (set ${DesktopRadioSettings.ENV_VIDEO_DUMP} to a folder you can write, or unset it)"
        }
        null
    }

    /** Runs one shutdown step, logging a failure instead of throwing it, so the steps after it still run. */
    private suspend fun bestEffort(what: String, step: suspend () -> Unit) {
        try {
            step()
        } catch (failure: Exception) {
            log.w { "bestEffort(): $what failed, going on -> message=${failure.message}" }
        }
    }

    private companion object {
        const val TAG = "DesktopKartRuntime"
        const val MILLIS_PER_SECOND = 1_000L
        const val TOKEN_BYTES = 16
        const val WORK_DIR_PREFIX = "mklhc-radio"
        const val SOCKET_FILE_NAME = "radiod.sock"

        /** How long radiod may take to exit after the link closed before it is killed (the spike's grace). */
        const val RADIOD_EXIT_GRACE_MS = 3_000L

        /** How long the pairing DataStore may take to release its file. */
        const val STORE_RELEASE_TIMEOUT_MS = 2_000L
    }
}

/**
 * A [KartSession] whose commands run on the runtime's dispatcher, one at a time.
 *
 * Business logic: [KartSessionRepository] is not thread-safe and must be driven from its scope, whose dispatcher runs one
 * task at a time, but the ViewModels call it from the UI thread and the exit stop from a background thread. The
 * wrapper confines the commands: each suspending one switches to [dispatcher] for the call, and [cancelPairing], which
 * does not suspend, is queued on [scope] in call order. The observable flows, [setDriveCommand] and [setCaptureEnabled]
 * pass through by design: the repository makes them safe from any thread. A suspend member added to [KartSession] later
 * must be added here too, or it would reach the repository from the caller's thread; ConfinedKartSessionTest fails until
 * it is.
 *
 * @param delegate the repository.
 * @param dispatcher the runtime's dispatcher, which runs one task at a time.
 * @param scope the runtime's scope, on that dispatcher.
 */
internal class ConfinedKartSession(
    private val delegate: KartSession,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : KartSession by delegate {

    override suspend fun start() = withContext(dispatcher) { delegate.start() }

    override suspend fun startPairing() = withContext(dispatcher) { delegate.startPairing() }

    override fun cancelPairing() {
        scope.launch { delegate.cancelPairing() }
    }

    override suspend fun sleepKart() = withContext(dispatcher) { delegate.sleepKart() }

    override suspend fun shutdownKart() = withContext(dispatcher) { delegate.shutdownKart() }

    // Under NonCancellable like the repository's own stop, so a cancelled caller cannot abort the switch either.
    override suspend fun stop() = withContext(NonCancellable + dispatcher) { delegate.stop() }
}
