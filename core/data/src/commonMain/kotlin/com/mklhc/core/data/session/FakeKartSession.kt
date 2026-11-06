package com.mklhc.core.data.session

import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A scriptable stand-in for the live kart session, for the feature ViewModels' tests (and for running the UI
 * without hardware).
 *
 * Business logic: nothing here touches a radio or a kart. A test scripts the world by pushing values into the
 * flows and inspects what the code under test asked for through recorded calls:
 * - The observable half mirrors [KartSession]: [setLinkState], [setKartInfo], [setPairingQr] set the state flows,
 *   [emitTelemetry] publishes a telemetry value exactly as the real session does (the latest value on
 *   [telemetry], battery packets also on [battery], IMU packets also on [imu]), [setBattery] sets the battery
 *   alone, and [emitImu] and [emitVideoAccessUnit] feed the two streams.
 * - The command half is recorded, not acted on: every call is appended to [calls] in order, drive commands are
 *   kept in [driveCommands] (the newest in [lastDriveCommand]), and the suspending commands are counted
 *   ([startCount], [startPairingCount], [sleepKartCount], [shutdownKartCount], [stopCount] and so on) with
 *   boolean shortcuts ([startCalled], [startPairingCalled]). Commands do not move [linkState] by themselves; the
 *   test decides when the link advances, which keeps the ViewModel's reaction to each state testable on its own.
 *   The one exception is [cancelPairing], which clears [pairingQr] at once because the real repository does.
 *
 * The streams ([imu], [videoAccessUnits]) are hot with a small drop-oldest buffer, like the real session's, so a
 * test must be collecting before it emits (Turbine's `test {}` does that). The fake is not thread-safe; drive it
 * from the test's coroutine. It lives in the main source set so other modules' tests (and a hardware-free demo
 * build) can use it, and it carries no test-framework dependency.
 *
 * @param initialLinkState the starting [linkState]; no adapter by default, like the real session.
 */
class FakeKartSession(
    initialLinkState: LinkState = LinkState.NoAdapter,
) : KartSession {

    /** One recorded command, in the order the code under test issued it. */
    enum class Call {
        START,
        START_PAIRING,
        CANCEL_PAIRING,
        SET_DRIVE_COMMAND,
        SLEEP_KART,
        SHUTDOWN_KART,
        SET_CAPTURE_ENABLED,
        STOP,
    }

    private val linkStateFlow = MutableStateFlow(initialLinkState)
    private val kartInfoFlow = MutableStateFlow<KartInfo?>(null)
    private val telemetryFlow = MutableStateFlow<Telemetry?>(null)
    private val batteryFlow = MutableStateFlow<Telemetry.BatteryStatus?>(null)
    private val pairingQrFlow = MutableStateFlow<ByteArray?>(null)
    private val imuFlow = MutableSharedFlow<Telemetry.Imu>(extraBufferCapacity = STREAM_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val videoFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = STREAM_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val recordedCalls = ArrayList<Call>()
    private val recordedDriveCommands = ArrayList<DriveCommand>()
    private val recordedCaptureCalls = ArrayList<Boolean>()

    override val linkState: StateFlow<LinkState> = linkStateFlow.asStateFlow()

    override val kartInfo: StateFlow<KartInfo?> = kartInfoFlow.asStateFlow()

    override val telemetry: StateFlow<Telemetry?> = telemetryFlow.asStateFlow()

    override val battery: StateFlow<Telemetry.BatteryStatus?> = batteryFlow.asStateFlow()

    override val imu: Flow<Telemetry.Imu> = imuFlow.asSharedFlow()

    override val videoAccessUnits: Flow<ByteArray> = videoFlow.asSharedFlow()

    override val pairingQr: StateFlow<ByteArray?> = pairingQrFlow.asStateFlow()

    // ---- scripting the world -----------------------------------------------------------------

    /** Scripts where the link stands. */
    fun setLinkState(state: LinkState) {
        linkStateFlow.value = state
    }

    /** Scripts what is known about the identified kart, or null for not identified yet. */
    fun setKartInfo(info: KartInfo?) {
        kartInfoFlow.value = info
    }

    /** Scripts the pairing QR payload, or null for no pairing in progress. */
    fun setPairingQr(payload: ByteArray?) {
        pairingQrFlow.value = payload
    }

    /** Scripts the battery status alone, without touching [telemetry]. */
    fun setBattery(status: Telemetry.BatteryStatus?) {
        batteryFlow.value = status
    }

    /**
     * Publishes one telemetry value the way the real session does: it becomes the latest [telemetry], a
     * [Telemetry.BatteryStatus] also becomes the latest [battery], and a [Telemetry.Imu] is also emitted on [imu].
     */
    fun emitTelemetry(value: Telemetry) {
        telemetryFlow.value = value
        when (value) {
            is Telemetry.BatteryStatus -> batteryFlow.value = value
            is Telemetry.Imu -> imuFlow.tryEmit(value)
            is Telemetry.FrameStatus, is Telemetry.Unknown -> Unit
        }
    }

    /** Emits one IMU packet on [imu] only (the latest [telemetry] is left alone). */
    fun emitImu(packet: Telemetry.Imu) {
        imuFlow.tryEmit(packet)
    }

    /** Emits one reassembled access unit on [videoAccessUnits]. */
    fun emitVideoAccessUnit(accessUnit: ByteArray) {
        videoFlow.tryEmit(accessUnit)
    }

    // ---- recorded commands -------------------------------------------------------------------

    /** Every command received, oldest first; drive commands appear as [Call.SET_DRIVE_COMMAND]. */
    val calls: List<Call> get() = recordedCalls.toList()

    /** Every drive command received, oldest first. */
    val driveCommands: List<DriveCommand> get() = recordedDriveCommands.toList()

    /** The newest drive command received, or null when none has been. */
    val lastDriveCommand: DriveCommand? get() = recordedDriveCommands.lastOrNull()

    /** Every value [setCaptureEnabled] was called with, oldest first. */
    val captureEnabledCalls: List<Boolean> get() = recordedCaptureCalls.toList()

    /** The last value [setCaptureEnabled] set; false until it is called. */
    var captureEnabled: Boolean = false
        private set

    /** How many times [start] was called. */
    var startCount: Int = 0
        private set

    /** How many times [startPairing] was called. */
    var startPairingCount: Int = 0
        private set

    /** How many times [cancelPairing] was called. */
    var cancelPairingCount: Int = 0
        private set

    /** How many times [sleepKart] was called. */
    var sleepKartCount: Int = 0
        private set

    /** How many times [shutdownKart] was called. */
    var shutdownKartCount: Int = 0
        private set

    /** How many times [stop] was called. */
    var stopCount: Int = 0
        private set

    /** True once [start] has been called. */
    val startCalled: Boolean get() = startCount > 0

    /** True once [startPairing] has been called. */
    val startPairingCalled: Boolean get() = startPairingCount > 0

    override suspend fun start() {
        startCount++
        recordedCalls += Call.START
    }

    override suspend fun startPairing() {
        startPairingCount++
        recordedCalls += Call.START_PAIRING
    }

    override fun cancelPairing() {
        cancelPairingCount++
        recordedCalls += Call.CANCEL_PAIRING
        // The real repository clears the QR synchronously on cancel, before the teardown it launches.
        pairingQrFlow.value = null
    }

    override fun setDriveCommand(command: DriveCommand) {
        recordedDriveCommands += command
        recordedCalls += Call.SET_DRIVE_COMMAND
    }

    override suspend fun sleepKart() {
        sleepKartCount++
        recordedCalls += Call.SLEEP_KART
    }

    override suspend fun shutdownKart() {
        shutdownKartCount++
        recordedCalls += Call.SHUTDOWN_KART
    }

    override fun setCaptureEnabled(enabled: Boolean) {
        captureEnabled = enabled
        recordedCaptureCalls += enabled
        recordedCalls += Call.SET_CAPTURE_ENABLED
    }

    override suspend fun stop() {
        stopCount++
        recordedCalls += Call.STOP
    }

    private companion object {
        const val STREAM_BUFFER = 64
    }
}
