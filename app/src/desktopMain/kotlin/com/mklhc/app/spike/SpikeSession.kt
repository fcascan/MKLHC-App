package com.mklhc.app.spike

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.NetstackConfig
import com.mklhc.core.netstack.NetstackNode
import com.mklhc.core.radio.DesktopRadioLink
import com.mklhc.core.radio.ProcessRadiodLauncher
import com.mklhc.core.radio.RadiodConfig
import com.mklhc.core.radio.RadiodLauncher
import com.mklhc.core.radio.RadiodSupervisor
import com.mklhc.core.wifi_ap.ap.RadioAccessPoint
import com.mklhc.core.wifi_ap.ap.RadioOptions
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.core.wifi_ap.pcap.PcapSink
import com.mklhc.core.wifi_ap.pcap.PcapWriter
import com.mklhc.feature.pairing.presentation.qr.encodeQrMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch

/*
 * The spike's composition: the process-level wiring around the real link layer (radiod, the access point, the network
 * stack, the pairing probe, the socket, the pcap file, the QR image, shutdown).
 *
 * Original code; it only starts the pieces that already exist (DesktopRadioLink and RadiodSupervisor from :core:radio,
 * RadioAccessPoint from :core:wifi-ap, NetstackNode from :core:netstack, SpikePairing and the pairing probe from this
 * package) and tears them down in the order radiod needs.
 */

/** Exit code: the session ended normally. */
internal const val EXIT_OK = 0

/** Exit code: the radio could not be brought up (or radiod never connected). */
internal const val EXIT_RADIO_FAILED = 1

/**
 * Exit code: bad command line, the radiod executable was not found, or a stop file left by an earlier run cannot be
 * removed.
 */
internal const val EXIT_USAGE = 2

/** The name of the stop file, created inside the `--out` directory to end a run cleanly (see [runSpikeSession]). */
internal const val STOP_FILE_NAME = "spike.stop"

/** How often the stop file is looked for; the wait between two looks is a delay, never a busy loop. */
internal const val STOP_FILE_POLL_MS = 500L

/** Why a session is ending, and whether that counts as a failure. */
internal class StopRequest(val reason: String, val failed: Boolean)

/**
 * Runs the whole spike session and returns the process exit code.
 *
 * Business logic: in order, it resolves radiod, creates a fresh pairing network, saves its QR code as an SVG image next
 * to the capture and prints the QR payload and the image path, opens the pcap file, listens on a socket in a short
 * temporary directory, starts the [RadioAccessPoint] (the link layer, recording to the pcap), the [NetstackNode] on top
 * of it (ARP, DHCP and TCP), the pairing probe and the radiod supervisor, and brings the radio up every time radiod
 * connects, serving the pairing network each time (so a radiod restart re-arms the beacon). Then it waits for [stop].
 * Cleanup always runs: the QR image is deleted first, then the bring-up, the pairing probe, the lease watcher and the
 * stop file watcher are cancelled and awaited (so nothing is brought up, served or announced while the radio shuts
 * down), then the network stack stops, then the access point stops the beacon and asks radiod to shut down, the
 * supervisor is stopped once radiod has exited by itself (so it does not launch it again), and the socket and its
 * directory are removed.
 *
 * The stop file `<out dir>/spike.stop` is the way to end a run cleanly when Enter cannot reach the process (see
 * [installConsoleStopTriggers]). One left by an earlier run is removed before anything starts, and the run does not
 * start ([EXIT_USAGE]) if that fails (see [clearStaleStopFile]); whatever file exists once the stop is taken is removed
 * again, so the next run starts clean.
 *
 * What the spike answers is everything the kart needs up to the RCD pairing handshake: authentication and association,
 * DHCP, ARP and the TCP pairing port (see [runPairingProbe]). It stops there on purpose: it never sends the pairing
 * request that stores the game network, so the kart should keep the pairing it has stored.
 *
 * Everything that touches the access point or the network stack runs on one single-threaded dispatcher, as both
 * require.
 *
 * @param args the parsed command line.
 * @param stop completed by whoever wants the run to end (the console, the JVM shutdown hook, a test); a failed
 * bring-up, the `--duration` timer and the stop file watcher complete it too.
 * @param cleanupDone counted down when the cleanup has finished, so the shutdown hook can wait for it.
 * @param launcher how radiod is started; replaceable so a test can run a fake radiod in-process.
 * @param primitives the crypto provider.
 * @param nonceTable the LP2P nonce table; replaceable so a test knows which nonce was used.
 * @param console where the pairing information, the QR code and the way to stop the run are printed.
 * @param log the logger of the session's own milestones (the access point and the network stack log under their own
 * tags); injectable so a test can assert them.
 * @return [EXIT_OK], [EXIT_RADIO_FAILED] or [EXIT_USAGE].
 */
internal suspend fun runSpikeSession(
    args: SpikeArgs,
    stop: CompletableDeferred<StopRequest>,
    cleanupDone: CountDownLatch = CountDownLatch(1),
    launcher: RadiodLauncher = ProcessRadiodLauncher(),
    primitives: CryptoPrimitives = CryptoPrimitives(),
    nonceTable: NonceTable = NonceTable(),
    console: (String) -> Unit = { println(it) },
    log: Logger = Logger.withTag(TAG),
): Int {
    // Held so the outer finally can always remove it and release the shutdown hook, even if setup throws before the
    // capture starts; otherwise the hook would block for SHUTDOWN_WAIT_MS and the temp directory would leak.
    var workDir: Path? = null
    // The QR image holds this run's pairing seed and is useless once the run ends (the network is gone), so it is
    // removed as soon as the run starts stopping, or by the outer finally if setup fails before the capture starts.
    var qrImage: Path? = null
    try {
        val radiod = args.radiod ?: findRadiodExecutable()
        if (radiod == null || !Files.isRegularFile(radiod)) {
            console("error: the radiod executable was not found${radiod?.let { " at $it" }.orEmpty()}.")
            console("Build it (see radiod/README.md and docs/HARDWARE_SETUP.md) or pass --radiod <path>.")
            return EXIT_USAGE
        }

        val pairing = createSpikePairing(primitives, nonceTable, args.channel, args.beaconIntervalTu)

        Files.createDirectories(args.outDir)
        val stopFile = args.outDir.resolve(STOP_FILE_NAME)
        if (!clearStaleStopFile(stopFile, console, log)) return EXIT_USAGE
        val fileStem = "spike-${LocalDateTime.now().format(FILE_TIME_FORMAT)}"
        val pcapPath = args.outDir.resolve("$fileStem.pcap")
        val qrPath = args.outDir.resolve("$fileStem-qr.svg")
        qrImage = qrPath
        Files.writeString(qrPath, renderQrAsSvg(encodeQrMatrix(pairing.qrPayload)))
        printPairing(pairing, qrPath, args, console, onAir = false)

        // AF_UNIX socket paths are limited to about 100 bytes, so bind in a short temporary directory.
        val dir = Files.createTempDirectory(WORK_DIR_PREFIX)
        workDir = dir
        val socketPath = dir.resolve("radiod.sock")
        val token = primitives.secureRandomBytes(TOKEN_BYTES).toHexString()

        val dispatcher = Dispatchers.Default.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val link = DesktopRadioLink(socketPath, token, scope)
        val supervisor = RadiodSupervisor(
            config = RadiodConfig(radiod, socketPath, token, extraArguments = if (args.verbose) listOf("--verbose") else emptyList()),
            scope = scope,
            launcher = launcher,
        )

        return FilePcapSink(pcapPath).use { sink ->
            val capture = PcapWriter(sink)
            val accessPoint = RadioAccessPoint(
                radio = link,
                options = RadioOptions(
                    vid = args.vid,
                    pid = args.pid,
                    txPowerIndex = args.txPowerIndex,
                    readyTimeoutMs = args.readyTimeoutMs,
                ),
                primitives = primitives,
            )
            accessPoint.capture = capture
            // The stack's hardware address is the BSSID: it is what the kart sees frames coming from, so it is what
            // the ARP reply for the app's address must carry.
            val node = NetstackNode(
                frameLink = accessPoint,
                scope = scope,
                config = NetstackConfig(ourIp = AddressPlan.AP, ourMac = pairing.apConfig.bssid.toByteArray()),
            )
            // The long-running jobs of the session, kept so the cleanup can end them before the radio goes down.
            val sessionJobs = mutableListOf<Job>()
            try {
                link.listen()
                // On the stack's own dispatcher, and before radiod is started: the radio's flows have no replay, so the
                // access point subscribes first, and the stack starts reading what it delivers before it can deliver.
                withContext(dispatcher) {
                    accessPoint.start(scope)
                    node.start()
                }
                sessionJobs += scope.launch { freeLeaseWhenStationLeaves(accessPoint.associatedStation, node, log) }
                sessionJobs += scope.launch { runPairingProbe(node, accessPoint.associatedStation, primitives, log) }
                sessionJobs += scope.launch { watchStopFile(stopFile, stop, log) }
                supervisor.start()
                sessionJobs += armBringUp(scope, link, accessPoint, pairing, stop, log) {
                    printPairing(pairing, qrPath, args, console, onAir = true)
                }
                if (args.durationSeconds > 0) {
                    scope.launch {
                        delay(args.durationSeconds * MILLIS_PER_SECOND)
                        stop.complete(StopRequest("duration elapsed", failed = false))
                    }
                }
                // The stop file as an absolute path: this process runs from the repository root, a new terminal need not.
                console(
                    "Spike running; capture goes to $pcapPath. Press Enter to stop, or create the stop file " +
                        "(PowerShell: New-Item ${powerShellQuoted(stopFile.toAbsolutePath().toString())}). " +
                        "Ctrl+C under Gradle on Windows skips the clean stop.",
                )

                val request = stop.await()
                log.i { "runSpikeSession(): stopping -> reason=${request.reason} failed=${request.failed}" }
                // Any file present now was created during this run: remove it before the radio teardown (which can take
                // seconds), so the next run starts clean and the user sees that the stop was taken.
                removeUsedStopFile(stopFile, console, log)
                if (request.failed) EXIT_RADIO_FAILED else EXIT_OK
            } finally {
                // The QR image goes first: it is not needed once the run is stopping, and the radio teardown below can
                // take seconds, during which a hard kill would leave the seed-bearing file behind.
                qrImage?.let { image -> deleteQrImage(image, console) }
                qrImage = null
                // On the stack's own dispatcher, as the node and the access point require, and even if this coroutine
                // was cancelled.
                withContext(NonCancellable + dispatcher) {
                    // Before anything is torn down: a bring-up that is still waiting for READY, a probe that is
                    // serving a connection and the lease watcher must not run (or bring the radio up, serve the
                    // network or print the on-air banner) while the stack and the radio shut down.
                    endSessionJobs(sessionJobs, log)
                    // The network stack stops before the access point, so nothing is queued for a radio that is about
                    // to shut down; the access point then stops the beacon and asks radiod to shut down. A failing
                    // stack must never keep the beacon on air, so it is only logged.
                    try {
                        node.stop()
                    } catch (failure: Exception) {
                        log.w {
                            "runSpikeSession(): stopping the network stack failed, shutting the radio down anyway -> " +
                                "message=${failure.message}"
                        }
                    }
                    accessPoint.stop()
                    log.i { "runSpikeSession(): pcap written: $pcapPath -> frames=${capture.framesWritten}" }
                    // radiod powers the chip down and exits by itself after SHUTDOWN; stopping the supervisor in
                    // that window (before its restart backoff fires) keeps it from launching radiod again.
                    withTimeoutOrNull(RADIOD_EXIT_GRACE_MS) {
                        supervisor.state.first { it is RadiodSupervisor.State.WaitingToRestart }
                    }
                    supervisor.stop()
                    link.close()
                    scope.cancel()
                    runCatching { Files.deleteIfExists(socketPath) }
                }
            }
        }
    } finally {
        workDir?.let { dir -> runCatching { Files.deleteIfExists(dir) } }
        qrImage?.let { image -> deleteQrImage(image, console) }
        cleanupDone.countDown()
    }
}

/**
 * Waits for radiod to connect (a bad radiod build or a missing libusb library shows up here as "never connects"), then
 * brings the radio up every time a radiod connection is established and serves the pairing network on it, so a radiod
 * restart re-arms the beacon, and calls [onRadioUp] each time the beacon is on air. A failure ends the session through
 * [stop].
 *
 * Every bring-up is followed by serving the network: a bring-up drops whatever the access point served before (radiod
 * restarted, so its beacon and identity are gone).
 *
 * @return the job that runs all of this, so the cleanup can end it before the radio is shut down.
 */
private fun armBringUp(
    scope: CoroutineScope,
    link: DesktopRadioLink,
    accessPoint: RadioAccessPoint,
    pairing: SpikePairing,
    stop: CompletableDeferred<StopRequest>,
    log: Logger,
    onRadioUp: () -> Unit,
): Job {
    return scope.launch {
        val connected = withTimeoutOrNull(RADIOD_CONNECT_TIMEOUT_MS) { link.connected.first { it } } != null
        if (!connected) {
            log.e { "armBringUp(): radiod did not connect -> timeoutMs=$RADIOD_CONNECT_TIMEOUT_MS (run with --verbose for radiod's output)" }
            stop.complete(StopRequest("radiod did not connect", failed = true))
            return@launch
        }
        link.connected.collectLatest { isConnected ->
            if (!isConnected) return@collectLatest
            try {
                accessPoint.bringUp(pairing.apConfig.channel)
                accessPoint.serve(pairing.apConfig)
                onRadioUp()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "armBringUp(): bring-up failed -> message=${failure.message}" }
                stop.complete(StopRequest("bring-up failed: ${failure.message}", failed = true))
            }
        }
    }
}

/**
 * Waits for the stop file to appear and then asks the session to stop, through [stop], like Enter does.
 *
 * Business logic: Enter does not always reach the process (see [installConsoleStopTriggers]), and a run that is killed
 * leaves the adapter beaconing. A file is a way to say "stop" from any other terminal, a file manager or a script, with
 * no stdin involved: the run then goes through the same clean path (stop beacon, SHUTDOWN) as Enter, with
 * `failed = false`. The file is looked for every [STOP_FILE_POLL_MS] with a delay, so the wait costs nothing between
 * looks, and each look runs on [Dispatchers.IO]: the `--out` directory may be on a file system that hangs (a network
 * share), and that must not stall the single-threaded dispatcher the access point and the network stack run on. It only
 * checks that the file exists, which is enough because [clearStaleStopFile] removed any older one.
 *
 * Ends by itself once the file is found; otherwise it runs until cancelled. The session keeps it with its other jobs,
 * so the cleanup cancels and awaits it before the node and the access point stop.
 *
 * @param stopFile the file to wait for.
 * @param stop completed with the stop request when the file appears.
 * @param log the session's logger.
 */
private suspend fun watchStopFile(stopFile: Path, stop: CompletableDeferred<StopRequest>, log: Logger) {
    while (!withContext(Dispatchers.IO) { Files.exists(stopFile) }) delay(STOP_FILE_POLL_MS)
    log.i { "watchStopFile(): the stop file appeared -> path=$stopFile" }
    stop.complete(StopRequest(STOP_FILE_REASON, failed = false))
}

/**
 * Deletes a stop file left by an earlier run, before the radio comes up.
 *
 * Business logic: a stale file never stops a new run: the watcher must not act on a file older than the run, or a
 * forgotten file would end every run at once. When the file cannot be deleted (a directory with that name, or a file
 * another program holds open on Windows) the run must not start either, since the watcher would stop it at once: the
 * console says which path to delete by hand, and nothing has been started yet.
 *
 * @param stopFile the stop file of this run.
 * @param console where the problem is reported.
 * @param log the session's logger.
 * @return true when there is no stop file any more, false when it could not be deleted.
 */
private fun clearStaleStopFile(stopFile: Path, console: (String) -> Unit, log: Logger): Boolean {
    try {
        if (Files.deleteIfExists(stopFile)) {
            log.i { "clearStaleStopFile(): removed a stop file left by an earlier run -> path=$stopFile" }
        }
        return true
    } catch (failure: IOException) {
        log.w {
            "clearStaleStopFile(): could not delete the stop file left by an earlier run -> path=$stopFile " +
                "message=${failure.message}"
        }
        console(
            "error: could not delete the stop file left by an earlier run; delete it by hand and start again: " +
                "${stopFile.toAbsolutePath()}",
        )
        return false
    }
}

/**
 * Deletes the stop file, if there is one, once the run has taken its stop request.
 *
 * Business logic: a file that exists at this point was created during this run (the stale one was removed at the
 * start), so whichever way the run is stopping it has done its job and can go; the next run then starts clean. Nothing
 * is logged when there was no file (Enter, `--duration`). A failure (a program that still holds the file open) is
 * reported on the console with the path, and the next start refuses to run until the file is gone.
 *
 * @param stopFile the stop file of this run.
 * @param console where a failure is reported.
 * @param log the session's logger.
 */
private fun removeUsedStopFile(stopFile: Path, console: (String) -> Unit, log: Logger) {
    try {
        if (Files.deleteIfExists(stopFile)) {
            log.i { "removeUsedStopFile(): the stop file was taken and removed -> path=$stopFile" }
        }
    } catch (failure: IOException) {
        log.w { "removeUsedStopFile(): could not delete the stop file -> path=$stopFile message=${failure.message}" }
        console(
            "warning: could not delete the stop file; delete it by hand before the next run: " +
                "${stopFile.toAbsolutePath()}",
        )
    }
}

/** [text] as a PowerShell single-quoted string, so a path with spaces or an apostrophe can be pasted as it is. */
internal fun powerShellQuoted(text: String): String = "'" + text.replace("'", "''") + "'"

/**
 * Cancels the session's long-running jobs and waits for them to end, for at most [SESSION_JOBS_END_TIMEOUT_MS].
 *
 * Business logic: the bring-up, the pairing probe, the lease watcher and the stop file watcher keep running while the
 * cleanup shuts the network stack and the radio down. A bring-up that is still waiting for READY could otherwise
 * complete while the access point stops, and serve the network (or print the on-air banner) on a radio that is going
 * away. Ending them first means nothing new starts during the shutdown; the probe's own cleanup (a RST for each of its
 * connections) runs here too, while the stack is still alive. The wait is bounded so a job that cannot end never keeps
 * the beacon on air: the teardown goes on and the scope's cancellation at the end finishes the job.
 *
 * Call it under `NonCancellable`, so a cancelled session still ends them.
 *
 * @param jobs the jobs to end.
 * @param log the session's logger.
 */
private suspend fun endSessionJobs(jobs: List<Job>, log: Logger) {
    jobs.forEach { it.cancel() }
    if (withTimeoutOrNull(SESSION_JOBS_END_TIMEOUT_MS) { jobs.joinAll() } == null) {
        log.w {
            "endSessionJobs(): some jobs did not end in time, shutting down anyway -> timeoutMs=$SESSION_JOBS_END_TIMEOUT_MS"
        }
    }
}

/**
 * Prints the pairing network, its QR payload and where the QR image is. The payload (which contains the seed) is
 * exactly what the QR code shows to the kart's camera; it is printed to the console on purpose, never to the log.
 *
 * It is printed twice: at start-up, while the radio is still coming up (the network does not exist yet, so the kart
 * must not scan it then), and again once the beacon is on air, so the code to scan is the last thing on screen
 * instead of being pushed out of view by the bring-up log.
 *
 * @param qrImage the SVG file of the same QR code, for a console that cannot draw it (see [renderQrAsSvg]).
 * @param onAir true once the pairing beacon is on air.
 */
private fun printPairing(pairing: SpikePairing, qrImage: Path, args: SpikeArgs, console: (String) -> Unit, onAir: Boolean) {
    val config = pairing.apConfig
    val payload = pairing.qrPayload
    if (onAir) {
        console("")
        console("=== The pairing network is on air: show this QR code to the kart now. ===")
    }
    console("Pairing network: ssid=${config.ssid} channel=${config.channel} bssid=${config.bssid}")
    console("Pairing QR payload (${payload.size} bytes, hex):")
    console(payload.toHexString())
    if (args.showQr) {
        console(renderQrForConsole(encodeQrMatrix(payload), darkTerminal = !args.lightTerminal))
    }
    // As a file URI: plain ASCII, so a non-ASCII path survives the same console code page the image works around.
    console("Pairing QR image (open it in a browser if the console code is missing or unreadable): ${qrImage.toAbsolutePath().toUri()}")
    if (onAir) {
        console("Put the kart in pairing mode (front LEDs blinking) and point its camera at the QR code.")
    } else {
        console("Wait: the radio is coming up (about a minute on Windows). The QR code is printed again once the network is on air; scan that one.")
    }
}

/**
 * Deletes the run's QR image. A failure (on Windows, a viewer that still holds the file open) is reported on the
 * console with the path, so the seed-bearing file can be removed by hand; the path itself holds no secret.
 */
private fun deleteQrImage(image: Path, console: (String) -> Unit) {
    try {
        Files.deleteIfExists(image)
    } catch (failure: IOException) {
        console("warning: could not delete the QR image, which holds this run's pairing seed; delete it by hand: ${image.toAbsolutePath()}")
    }
}

private fun findRadiodExecutable(): Path? =
    RADIOD_CANDIDATES.map { Path.of(it) }.firstOrNull { Files.isRegularFile(it) }

/** A [PcapSink] that streams to a file; every record is written straight through, so a killed run keeps its capture. */
internal class FilePcapSink(path: Path) : PcapSink, AutoCloseable {
    private val output: OutputStream = Files.newOutputStream(path)

    override fun write(bytes: ByteArray) = output.write(bytes)

    override fun flush() = output.flush()

    override fun close() = output.close()
}

private const val TAG = "SpikeSession"
private const val MILLIS_PER_SECOND = 1_000L
private const val TOKEN_BYTES = 16
private const val WORK_DIR_PREFIX = "mklhc-spike"
private const val RADIOD_CONNECT_TIMEOUT_MS = 30_000L
private const val RADIOD_EXIT_GRACE_MS = 3_000L
private const val SESSION_JOBS_END_TIMEOUT_MS = 3_000L
private const val STOP_FILE_REASON = "stop file found"

private val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val RADIOD_CANDIDATES = listOf(
    "radiod/build/Release/radiod.exe",
    "radiod/build/radiod.exe",
    "radiod/build/RelWithDebInfo/radiod.exe",
    "radiod/build/radiod",
)
