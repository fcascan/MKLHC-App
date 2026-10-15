package com.mklhc.core.data.session

import com.mklhc.core.kart_protocol.fuji.FujiPorts
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The ports and timings the [KartSessionRepository] drives the session with.
 *
 * Business logic: every value here is fixed by the kart protocol or by a safety requirement, but it is
 * collected in one injectable object so the integration tests can shorten timers and so a hardware
 * spike can adjust a value without touching the orchestrator. The defaults are the production values:
 *
 * - [sessionRcdPort] / [pairingRcdPort] are the two TCP ports OUR app hosts the RCD server on; the kart
 *   connects to [pairingRcdPort] while it is being paired and to [sessionRcdPort] once it is a known kart.
 * - [telemetryPort], [videoStreamPort] and [videoControlPort] are the app ports the kart is told about in
 *   `connection_info`: the kart sends telemetry and video to the first two (UDP) and connects to the
 *   third (TCP) for video control, on which the app sends the LVNI keepalive.
 * - [controlLocalPort], [eventLocalPort], [pairingLocalPort] and [teleopLocalPort] are the app-side local
 *   ports the app opens its own connections / datagrams from; they only need to be distinct and free.
 * - [teleopInterval] is the 30 Hz drive-command cadence; [deadmanTimeout] is how long the last command is
 *   repeated before the app falls back to neutral; [lvniInterval] is the one-per-second video keepalive;
 *   [connectWindow] is the few-second budget the kart gives the app to open its channels after the
 *   handshake before it resets (shared by the control, event and pairing connects together, not per connect).
 * - [recoveryBackoffInitial] and [recoveryBackoffMax] bound the wait before re-beaconing after a session is
 *   lost or fails, so a radio that cannot beacon or a kart that never associates does not busy-loop (and
 *   flood the log): the wait starts at the initial value and doubles up to the maximum, resetting after a
 *   session that reached driving.
 *
 * @property sessionRcdPort TCP port the app's RCD server listens on for a known kart (session mode).
 * @property pairingRcdPort TCP port the app's RCD server listens on while pairing.
 * @property telemetryPort UDP port the app receives telemetry on.
 * @property videoStreamPort UDP port the app receives the video stream on.
 * @property videoControlPort TCP port the app listens on for the video (LSP) control channel.
 * @property controlLocalPort app-side local port for the connection to the kart's control service.
 * @property eventLocalPort app-side local port for the connection to the kart's event channel.
 * @property pairingLocalPort app-side local port for the connection to the kart's pairing service.
 * @property teleopLocalPort app-side local UDP port the drive datagrams are sent from.
 * @property teleopInterval the drive-command send period (about 30 Hz).
 * @property deadmanTimeout how long with no fresh command before the app sends neutral.
 * @property lvniInterval the video-control keepalive period.
 * @property connectWindow how long the app may take to open its channels after the handshake (shared).
 * @property recoveryBackoffInitial the first wait before re-beaconing after a session is lost or fails.
 * @property recoveryBackoffMax the ceiling the doubling recovery wait is capped at.
 */
data class SessionConfig(
    val sessionRcdPort: Int = 5202,
    val pairingRcdPort: Int = 5201,
    val telemetryPort: Int = 5116,
    val videoStreamPort: Int = 5016,
    val videoControlPort: Int = 5032,
    val controlLocalPort: Int = 41_003,
    val eventLocalPort: Int = 41_007,
    val pairingLocalPort: Int = 41_006,
    val teleopLocalPort: Int = 41_102,
    val teleopInterval: Duration = 33.milliseconds,
    val deadmanTimeout: Duration = 250.milliseconds,
    val lvniInterval: Duration = 1.seconds,
    val connectWindow: Duration = 5.seconds,
    val recoveryBackoffInitial: Duration = 500.milliseconds,
    val recoveryBackoffMax: Duration = 5.seconds,
) {
    /** The kart's drive (teleop) UDP port the app sends to; the kart protocol fixes it. */
    val kartDrivePort: Int = FujiPorts.DRIVE
}
