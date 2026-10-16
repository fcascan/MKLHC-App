package com.mklhc.feature.cockpit.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.design_system.drive.driveCommandOf
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.presentation.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** How often, at most, the HUD is published from the IMU stream: every 50 ms, which is 20 Hz. */
internal const val HUD_PUBLISH_PERIOD_MS = 50L

/**
 * How often a held, non-neutral drive command is repeated to the session: every 100 ms. The session's deadman
 * (250 ms by default) turns a command that stops being refreshed into neutral, and a finger resting on the pedal
 * produces no new values, so the cockpit has to keep the command fresh itself. Well inside the deadman window,
 * so one late repeat does not cut the throttle.
 */
internal const val DRIVE_KEEP_ALIVE_MS = 100L

/**
 * The state holder of the cockpit screen.
 *
 * Business logic: the ViewModel is the bridge between the driver's hands and the kart session. Three things flow
 * through it:
 * - The link and the telemetry come in. [KartSession.linkState] decides whether the cockpit is driving (the
 *   controls only work while it is [LinkState.Driving]), the battery feeds the gauge, and the IMU stream feeds
 *   the artificial horizon and the G meter through [toHudReading]. The IMU is conflated to a display rate
 *   ([HUD_PUBLISH_PERIOD_MS], 20 Hz): the kart sends packets faster than a screen can use them, and publishing
 *   each one would recompose the HUD for nothing. The newest packet always wins.
 * - The controls go out. Each steering, throttle or brake change updates the state (so the controls move) and
 *   sends `driveCommandOf(throttle, steering, brakeLight)` to the session with the RAW -1..1 values; the
 *   repository applies the user's max-throttle and expo settings, so nothing is scaled here. While the command is
 *   not neutral it is repeated every [DRIVE_KEEP_ALIVE_MS], because the session's deadman would otherwise cut a
 *   steady throttle after a quarter of a second.
 * - Safety. Release means neutral: letting go of the steering or the throttle returns that axis to zero and
 *   resends the command (so releasing one thumb does not cut the other), and [onControlRelease] centres everything
 *   and sends [DriveCommand.NEUTRAL]. The kart is also sent neutral, with the controls centred, whenever the link
 *   is anywhere but Driving (including on the first state, if the cockpit opens before the kart is driving) and
 *   when the ViewModel is cleared, i.e. the screen was left. Control input is ignored while not driving, so a
 *   touch that slips through a disabled control cannot move the kart.
 *
 * The raw video frames never enter the state: [videoAccessUnits] is the session's flow, handed on as is, so that
 * only the video surface touches it.
 *
 * Interaction style: public action functions ([onSteering], [onThrottle], [onSteeringRelease],
 * [onThrottleRelease], [onControlRelease], [onBrake]); there are no one-time effects, a lost link is shown as an
 * overlay and the Root's back button is a plain callback. The session is injected and everything runs in
 * [viewModelScope], so it is cancelled when the screen goes away.
 *
 * @param session the kart session this screen drives and reads.
 */
class CockpitViewModel(private val session: KartSession) : ViewModel() {

    private val log = Logger.withTag(TAG)

    private val _state = MutableStateFlow(initialState())
    val state = _state.asStateFlow()

    /** The H.264 access units for the video surface: the session's flow, never copied into [state]. */
    val videoAccessUnits: Flow<ByteArray> = session.videoAccessUnits

    private var keepAlive: Job? = null

    init {
        observeLinkState()
        observeBattery()
        observeImu()
    }

    /** The driver moved the steering control to [value], -1 (full left) .. 1 (full right). Ignored unless driving. */
    fun onSteering(value: Float) {
        updateControls(requireDriving = true) { it.copy(steeringFraction = value.sanitized()) }
    }

    /** The driver moved the throttle pedal to [value], -1 (full reverse) .. 1 (full forward). Ignored unless driving. */
    fun onThrottle(value: Float) {
        updateControls(requireDriving = true) { it.copy(throttleFraction = value.sanitized()) }
    }

    /** The driver let go of the steering control: steering returns to zero, the other controls are untouched. */
    fun onSteeringRelease() {
        updateControls(requireDriving = false) { it.copy(steeringFraction = 0f) }
    }

    /** The driver let go of the throttle pedal: throttle returns to zero, the other controls are untouched. */
    fun onThrottleRelease() {
        updateControls(requireDriving = false) { it.copy(throttleFraction = 0f) }
    }

    /**
     * Releases everything: centres the steering and the throttle, switches the brake light off and sends
     * [DriveCommand.NEUTRAL]. For when the screen or the app goes out of view, where there is no single control to
     * blame.
     */
    fun onControlRelease() {
        _state.update { it.withCentredControls() }
        holdNeutral()
    }

    /** The brake-light button went down ([active] true) or up. Pressing is ignored unless driving; releasing never is. */
    fun onBrake(active: Boolean) {
        updateControls(requireDriving = active) { it.copy(brakeLight = active) }
    }

    override fun onCleared() {
        log.i { "onCleared(): the cockpit was left, returning the kart to neutral" }
        stopKeepAlive()
        session.setDriveCommand(DriveCommand.NEUTRAL)
    }

    private fun initialState(): CockpitState {
        val linkState = session.linkState.value
        return CockpitState(linkState = linkState, statusText = linkState.toUiText()).withBattery(session.battery.value)
    }

    private fun observeLinkState() {
        launchGuarded("link state") {
            session.linkState.collect { linkState ->
                val driving = linkState == LinkState.Driving
                if (!driving) {
                    log.i { "observeLinkState(): not driving, holding the kart at neutral -> linkState=$linkState" }
                    holdNeutral()
                }
                _state.update {
                    val updated = it.copy(linkState = linkState, statusText = linkState.toUiText())
                    if (driving) updated else updated.withCentredControls()
                }
            }
        }
    }

    private fun observeBattery() {
        launchGuarded("battery") {
            session.battery.collect { status -> _state.update { it.withBattery(status) } }
        }
    }

    private fun observeImu() {
        launchGuarded("IMU") {
            // Conflate so the producer never waits, then pause after each publication: whatever arrived in the
            // meantime collapses to the newest packet, which is the next one published.
            session.imu.conflate().collect { packet ->
                val reading = packet.toHudReading()
                _state.update {
                    it.copy(
                        pitchDegrees = reading.pitchDegrees,
                        rollDegrees = reading.rollDegrees,
                        gX = reading.gForce?.lateral ?: it.gX,
                        gY = reading.gForce?.longitudinal ?: it.gY,
                    )
                }
                delay(HUD_PUBLISH_PERIOD_MS)
            }
        }
    }

    /** Applies [transform] to the controls and sends the resulting command, unless [requireDriving] and not driving. */
    private fun updateControls(requireDriving: Boolean, transform: (CockpitState) -> CockpitState) {
        if (requireDriving && !_state.value.isDriving) return
        sendDriveCommand(_state.updateAndGet(transform))
    }

    /** Sends the command for [state] and keeps it alive for as long as it is not neutral. */
    private fun sendDriveCommand(state: CockpitState) {
        val command = state.toDriveCommand()
        session.setDriveCommand(command)
        if (command == DriveCommand.NEUTRAL) stopKeepAlive() else startKeepAlive()
    }

    /** Sends neutral and stops repeating whatever was being held. */
    private fun holdNeutral() {
        stopKeepAlive()
        session.setDriveCommand(DriveCommand.NEUTRAL)
    }

    private fun startKeepAlive() {
        if (keepAlive?.isActive == true) return
        keepAlive = viewModelScope.launch {
            while (true) {
                delay(DRIVE_KEEP_ALIVE_MS)
                val current = _state.value
                if (!current.isDriving) break
                session.setDriveCommand(current.toDriveCommand())
            }
        }
    }

    private fun stopKeepAlive() {
        keepAlive?.cancel()
        keepAlive = null
    }

    /** Launches [block] in the ViewModel scope, logging (not propagating) any failure other than cancellation. */
    private fun launchGuarded(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "launchGuarded(): $what collection ended -> message=${failure.message}" }
            }
        }
    }

    private companion object {
        const val TAG = "CockpitViewModel"
    }
}

/** The drive command the controls, as held, ask for: the raw -1..1 values through the shared octet mapping. */
private fun CockpitState.toDriveCommand(): DriveCommand =
    driveCommandOf(throttle = throttleFraction, steering = steeringFraction, brakeLight = brakeLight)

private fun CockpitState.withCentredControls(): CockpitState =
    copy(steeringFraction = 0f, throttleFraction = 0f, brakeLight = false)

private fun CockpitState.withBattery(status: Telemetry.BatteryStatus?): CockpitState = copy(
    hasBattery = status != null,
    batteryBars = status?.batteryBars?.coerceIn(0, MAX_BATTERY_BARS) ?: 0,
    batteryCharging = status?.cableConnected ?: false,
)

/** Clamps a control value to -1..1; NaN reads as centred and negative zero as zero. */
private fun Float.sanitized(): Float = when {
    isNaN() || this == 0f -> 0f
    else -> coerceIn(-1f, 1f)
}

private const val MAX_BATTERY_BARS = 4
