package com.mklhc.app.demo

import co.touchlab.kermit.Logger
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * How long each part of the demo script takes, in milliseconds of (possibly virtual) time.
 *
 * @property stepDelayMs the pause between two rungs of the link ladder.
 * @property pairingScanDelayMs how long the pairing QR stays up before the demo kart "scans" it.
 * @property imuPeriodMs the interval between IMU packets.
 * @property batteryPeriodMs the interval between battery reports.
 * @property videoPeriodMs the interval between placeholder video access units.
 */
data class DemoTiming(
    val stepDelayMs: Long = 700L,
    val pairingScanDelayMs: Long = 6_000L,
    val imuPeriodMs: Long = 33L,
    val batteryPeriodMs: Long = 2_000L,
    val videoPeriodMs: Long = 100L,
)

/**
 * DEMO / no-hardware kart session: a scripted [KartSession] that lets the whole UI run without a radio or a kart.
 *
 * This is NOT the real session. It talks to nothing, persists nothing and invents everything it publishes; it lets
 * the screens be developed, demonstrated and tested without hardware. Android binds it for now; the desktop binds
 * `KartSessionRepository` instead and uses this only with `MKLHC_DEMO=1` or `--demo` (see the app's Koin module).
 *
 * Business logic: the session is a small script driven by two commands.
 * - [start] climbs the link ladder the way the real session does, one rung per [DemoTiming.stepDelayMs]:
 *   RadioStarting, Beaconing(GAME), KartAssociated, KartLeased, KartHandshaked (the sample [KartInfo] appears with
 *   the first battery report), KartReady and Driving. From the handshake on, synthetic telemetry streams until the
 *   session is parked: a battery report every [DemoTiming.batteryPeriodMs], a gently oscillating IMU packet every
 *   [DemoTiming.imuPeriodMs] (so the artificial horizon and the G meter move, and react to the held drive command)
 *   and a placeholder video access unit every [DemoTiming.videoPeriodMs] (so the desktop video surface counts frames).
 * - [startPairing] shows a pairing QR while it beacons the pairing network, waits [DemoTiming.pairingScanDelayMs]
 *   as if the kart scanned it, clears the QR and climbs the same ladder from the association on. Nothing is
 *   persisted.
 * - [cancelPairing] clears the QR at once and settles the link on a failed state, like the real session.
 * - [stop], [sleepKart] and [shutdownKart] park the session: the script ends, every published value is cleared and
 *   the link returns to its idle state ([LinkState.NoAdapter], where a new start is offered). Unlike the real session
 *   a parked demo can be started again, so the demo can be walked repeatedly.
 *
 * [setDriveCommand] only records the command and feeds it into the generated acceleration, so the G meter reacts to
 * the driver. [setCaptureEnabled] is remembered and has no other effect.
 *
 * Threading: the flows are thread safe and the script's lifecycle is serialized by a mutex, so overlapping
 * start/pair/stop/cancel calls never leave two scripts running or publish stale data after a teardown. The script
 * runs on [scope], which the caller owns (the demo never cancels it).
 *
 * @param scope where the script runs; use a scope that outlives the screens.
 * @param timing the pace of the script; the defaults are tuned for a person watching, tests pass the same values
 *   and advance a virtual clock.
 * @param random the source of the pairing seed. It is not cryptographically secure, because a demo seed pairs nothing.
 */
class DemoKartSession(
    private val scope: CoroutineScope,
    private val timing: DemoTiming = DemoTiming(),
    private val random: Random = Random.Default,
) : KartSession {

    private val log = Logger.withTag(TAG)

    private val linkStateFlow = MutableStateFlow<LinkState>(LinkState.NoAdapter)
    private val kartInfoFlow = MutableStateFlow<KartInfo?>(null)
    private val telemetryFlow = MutableStateFlow<Telemetry?>(null)
    private val batteryFlow = MutableStateFlow<Telemetry.BatteryStatus?>(null)
    private val pairingQrFlow = MutableStateFlow<ByteArray?>(null)
    private val driveCommandFlow = MutableStateFlow(DriveCommand.NEUTRAL)
    private val imuFlow = MutableSharedFlow<Telemetry.Imu>(
        extraBufferCapacity = STREAM_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val videoFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = STREAM_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Serializes every change to [scriptJob] so a script is never started, replaced or torn down twice at once. */
    private val lifecycle = Mutex()

    /** The running script, or null when parked. Read without the lock only by [cancelPairing], hence [Volatile]. */
    @Volatile
    private var scriptJob: Job? = null

    override val linkState: StateFlow<LinkState> = linkStateFlow.asStateFlow()

    override val kartInfo: StateFlow<KartInfo?> = kartInfoFlow.asStateFlow()

    override val telemetry: StateFlow<Telemetry?> = telemetryFlow.asStateFlow()

    override val battery: StateFlow<Telemetry.BatteryStatus?> = batteryFlow.asStateFlow()

    override val imu: Flow<Telemetry.Imu> = imuFlow.asSharedFlow()

    override val videoAccessUnits: Flow<ByteArray> = videoFlow.asSharedFlow()

    override val pairingQr: StateFlow<ByteArray?> = pairingQrFlow.asStateFlow()

    /** The latest drive command received; [DriveCommand.NEUTRAL] until one is set and after the session is parked. */
    val driveCommand: StateFlow<DriveCommand> = driveCommandFlow.asStateFlow()

    /** The last value [setCaptureEnabled] set; false until it is called. */
    @Volatile
    var captureEnabled: Boolean = false
        private set

    override suspend fun start() {
        log.i { "start(): walking the demo link ladder" }
        launchScript("start") { climbToDriving(pairing = false) }
    }

    override suspend fun startPairing() {
        log.i { "startPairing(): showing a demo pairing code" }
        launchScript("startPairing") { climbToDriving(pairing = true) }
    }

    override fun cancelPairing() {
        log.i { "cancelPairing(): cancelling the demo pairing" }
        // Immediate feedback: cancel the script (so publish() goes quiet) and show the failure now.
        val cancelled = scriptJob
        cancelled?.cancel()
        pairingQrFlow.value = null
        linkStateFlow.value = LinkState.Failed(PAIRING_CANCELLED)
        // Finish the teardown off the caller's thread: wait for the script (and its telemetry children) to
        // actually stop before clearing, so a late battery/IMU write cannot land after the clear. Skip the clear
        // if a new script has since taken over, so cancelling never wipes a session that was started again.
        scope.launch {
            lifecycle.withLock {
                cancelled?.join()
                if (scriptJob === cancelled) {
                    scriptJob = null
                    clearPublishedData()
                }
            }
        }
    }

    override fun setDriveCommand(command: DriveCommand) {
        driveCommandFlow.value = command
    }

    override suspend fun sleepKart() {
        log.i { "sleepKart(): parking the demo session" }
        park()
    }

    override suspend fun shutdownKart() {
        log.i { "shutdownKart(): parking the demo session" }
        park()
    }

    override fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled = enabled
        log.i { "setCaptureEnabled(): capture is now -> enabled=$enabled (the demo captures nothing)" }
    }

    override suspend fun stop() {
        log.i { "stop(): parking the demo session" }
        park()
    }

    /** Ends the running script and returns every published value to the idle, parked state. */
    private suspend fun park() {
        lifecycle.withLock {
            scriptJob?.cancelAndJoin()
            scriptJob = null
            clearPublishedData()
            linkStateFlow.value = LinkState.NoAdapter
        }
    }

    private fun clearPublishedData() {
        kartInfoFlow.value = null
        telemetryFlow.value = null
        batteryFlow.value = null
        pairingQrFlow.value = null
        driveCommandFlow.value = DriveCommand.NEUTRAL
    }

    /**
     * Replaces the running script with [block]; a failure inside it is logged and shown as a failed link. The swap
     * is serialized by [lifecycle] so two overlapping starts cannot both replace the job and orphan one script.
     */
    private suspend fun launchScript(name: String, block: suspend CoroutineScope.() -> Unit) {
        lifecycle.withLock {
            scriptJob?.cancelAndJoin()
            scriptJob = scope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.e(failure) {
                        "launchScript(): the demo script ended with an error -> name=$name message=${failure.message}"
                    }
                    linkStateFlow.value = LinkState.Failed(failure.message ?: "demo script error")
                }
            }
        }
    }

    /**
     * The ladder shared by both flows, then the telemetry that runs until the script is cancelled.
     *
     * The normal flow beacons the game network; the pairing flow beacons the pairing network with a QR, waits for
     * the demo kart to "scan" it and continues from the association on.
     */
    private suspend fun climbToDriving(pairing: Boolean) = coroutineScope {
        clearPublishedData()
        publish(LinkState.RadioStarting)
        pause()
        if (pairing) {
            pairingQrFlow.value = DemoTelemetry.pairingPayload(random)
            publish(LinkState.Beaconing(KartNetworkKind.PAIRING))
            delay(timing.pairingScanDelayMs)
            pairingQrFlow.value = null
        } else {
            publish(LinkState.Beaconing(KartNetworkKind.GAME))
            pause()
        }
        publish(LinkState.KartAssociated)
        pause()
        publish(LinkState.KartLeased)
        pause()
        publish(LinkState.KartHandshaked)
        kartInfoFlow.value = DemoTelemetry.SAMPLE_KART_INFO
        launchTelemetry()
        pause()
        publish(LinkState.KartReady)
        pause()
        publish(LinkState.Driving)
        // The telemetry children run until this scope is cancelled, which keeps the script alive.
    }

    /** Starts the three synthetic streams as children of the script. */
    private fun CoroutineScope.launchTelemetry() {
        launch { runBattery() }
        launch { runImu() }
        launch { runVideo() }
    }

    private suspend fun runBattery() {
        while (currentCoroutineContext().isActive) {
            val status = DemoTelemetry.battery()
            batteryFlow.value = status
            telemetryFlow.value = status
            delay(timing.batteryPeriodMs)
        }
    }

    private suspend fun runImu() {
        var sequence = 0
        while (currentCoroutineContext().isActive) {
            val seconds = sequence * timing.imuPeriodMs / MILLIS_PER_SECOND
            val packet = DemoTelemetry.imu(timeSeconds = seconds, sequence = sequence, drive = driveCommandFlow.value)
            telemetryFlow.value = packet
            imuFlow.tryEmit(packet)
            sequence++
            delay(timing.imuPeriodMs)
        }
    }

    private suspend fun runVideo() {
        var sequence = 0
        while (currentCoroutineContext().isActive) {
            videoFlow.tryEmit(DemoTelemetry.videoAccessUnit(sequence))
            sequence++
            delay(timing.videoPeriodMs)
        }
    }

    /** Publishes [state] unless the script was cancelled in the meantime, so a cancelled script never speaks again. */
    private suspend fun publish(state: LinkState) {
        currentCoroutineContext().ensureActive()
        linkStateFlow.value = state
    }

    private suspend fun pause() = delay(timing.stepDelayMs)

    private companion object {
        const val TAG = "DemoKartSession"
        const val STREAM_BUFFER = 64
        const val MILLIS_PER_SECOND = 1000.0
        const val PAIRING_CANCELLED = "pairing cancelled"
    }
}
