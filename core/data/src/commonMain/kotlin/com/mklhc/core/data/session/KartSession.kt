package com.mklhc.core.data.session

import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The live session with the kart, as the feature ViewModels see it.
 *
 * Business logic: this is the seam between the screens and the orchestration layer. [KartSessionRepository]
 * implements it for real (radio, access point, network stack and protocols); a ViewModel depends only on this
 * interface, so its tests substitute [FakeKartSession] and script the link, the telemetry and the video without
 * any radio. The surface has two halves. The observable half is what the UI renders: where the link stands,
 * what the kart is, the live telemetry, the IMU and video streams and the pairing QR. The command half is what
 * the UI asks for: start a normal or a pairing flow, cancel pairing, set the drive command, put the kart to
 * sleep or power it off, toggle capture and stop.
 *
 * Safety contract for implementers and callers: [setDriveCommand] only records the latest command, the session
 * itself repeats it for at most a short deadman window and otherwise sends neutral, so a screen that stops
 * calling it (or whose finger-up sets [DriveCommand.NEUTRAL]) never leaves the kart driving.
 */
interface KartSession {

    /** Where the link currently stands; the UI follows this to show progress and recovery. */
    val linkState: StateFlow<LinkState>

    /** What the app has learned about the identified kart, or null before it is identified. */
    val kartInfo: StateFlow<KartInfo?>

    /** The most recent decoded telemetry datagram, of any kind, or null before the first one. */
    val telemetry: StateFlow<Telemetry?>

    /** The most recent battery/charging status, or null before the first one. */
    val battery: StateFlow<Telemetry.BatteryStatus?>

    /** The IMU telemetry packets as they arrive. */
    val imu: Flow<Telemetry.Imu>

    /** Reassembled H.264 access units as they are completed from the kart's video stream. */
    val videoAccessUnits: Flow<ByteArray>

    /** The pairing QR payload while the app is beaconing the pairing network, or null otherwise. */
    val pairingQr: StateFlow<ByteArray?>

    /**
     * Begins the normal session flow: ensure the adapter, start the radio, run the network stack, beacon the
     * game network and drive the session (with automatic recovery) until [stop]. Launches the work and returns;
     * progress is published through [linkState]. Calling it again restarts the flow.
     */
    suspend fun start()

    /**
     * Begins the pairing flow: beacon the pairing network, serve the pairing handshake, hand the kart the game
     * credentials and then fall into the normal session. The pairing QR payload is published through [pairingQr]
     * while the pairing network is up.
     */
    suspend fun startPairing()

    /**
     * Cancels an in-progress pairing (or session) attempt: clears the pairing QR at once, brings the pairing
     * beacon and the network stack down and settles the link on a terminal state. A later [start] /
     * [startPairing] restarts the flow.
     */
    fun cancelPairing()

    /**
     * Sets the latest drive command the teleop loop will send. Safe to call from any thread; it only records
     * the command, which the 30 Hz loop picks up on its next tick.
     */
    fun setDriveCommand(command: DriveCommand)

    /** Puts the kart to sleep (stops accepting drive) and forces a neutral command, when it is reachable. */
    suspend fun sleepKart()

    /** Powers the kart off, when it is reachable. */
    suspend fun shutdownKart()

    /** Enables or disables packet capture. */
    fun setCaptureEnabled(enabled: Boolean)

    /**
     * Stops the session: sleeps (or powers off, per the user's setting) the kart and sends a final neutral if
     * reachable, then tears everything down. Terminal: the instance is not restarted afterwards.
     */
    suspend fun stop()
}
