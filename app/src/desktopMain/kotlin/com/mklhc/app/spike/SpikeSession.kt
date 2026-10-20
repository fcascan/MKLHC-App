package com.mklhc.app.spike

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.radio.DesktopRadioLink
import com.mklhc.core.radio.ProcessRadiodLauncher
import com.mklhc.core.radio.RadiodConfig
import com.mklhc.core.radio.RadiodLauncher
import com.mklhc.core.radio.RadiodSupervisor
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.core.wifi_ap.pcap.PcapSink
import com.mklhc.core.wifi_ap.pcap.PcapWriter
import com.mklhc.feature.pairing.presentation.qr.encodeQrMatrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch

/*
 * The spike's composition: the process-level wiring around SpikeRunner (radiod, the socket, the pcap file, shutdown).
 *
 * Original code; it only starts the pieces that already exist (DesktopRadioLink and RadiodSupervisor from :core:radio,
 * SpikePairing and SpikeRunner from this package) and tears them down in the order radiod needs.
 */

/** Exit code: the session ended normally. */
internal const val EXIT_OK = 0

/** Exit code: the radio could not be brought up (or radiod never connected). */
internal const val EXIT_RADIO_FAILED = 1

/** Exit code: bad command line, or the radiod executable was not found. */
internal const val EXIT_USAGE = 2

/** Why a session is ending, and whether that counts as a failure. */
internal class StopRequest(val reason: String, val failed: Boolean)

/**
 * Runs the whole spike session and returns the process exit code.
 *
 * Business logic: in order, it resolves radiod, creates a fresh pairing network and prints its QR payload, opens the
 * pcap file, listens on a socket in a short temporary directory, starts [SpikeRunner] and the radiod supervisor, and
 * brings the radio up every time radiod connects (so a radiod restart re-arms the beacon). Then it waits for [stop].
 * Cleanup always runs: the runner stops the beacon and asks radiod to shut down, the supervisor is stopped once radiod
 * has exited by itself (so it does not launch it again), and the socket and its directory are removed.
 *
 * Everything that touches the access point runs on one single-threaded dispatcher, as [SpikeRunner] requires.
 *
 * @param args the parsed command line.
 * @param stop completed by whoever wants the run to end (the console, the JVM shutdown hook, a test); a failed
 * bring-up and the `--duration` timer complete it too.
 * @param cleanupDone counted down when the cleanup has finished, so the shutdown hook can wait for it.
 * @param launcher how radiod is started; replaceable so a test can run a fake radiod in-process.
 * @param primitives the crypto provider.
 * @param nonceTable the LP2P nonce table; replaceable so a test knows which nonce was used.
 * @param console where the pairing information and the QR code are printed.
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
): Int {
    val log = Logger.withTag(TAG)
    // Held so the outer finally can always remove it and release the shutdown hook, even if setup throws before the
    // capture starts; otherwise the hook would block for SHUTDOWN_WAIT_MS and the temp directory would leak.
    var workDir: Path? = null
    try {
        val radiod = args.radiod ?: findRadiodExecutable()
        if (radiod == null || !Files.isRegularFile(radiod)) {
            console("error: the radiod executable was not found${radiod?.let { " at $it" }.orEmpty()}.")
            console("Build it (see radiod/README.md and docs/HARDWARE_SETUP.md) or pass --radiod <path>.")
            return EXIT_USAGE
        }

        val pairing = createSpikePairing(primitives, nonceTable, args.channel, args.beaconIntervalTu)
        printPairing(pairing, args, console, onAir = false)

        Files.createDirectories(args.outDir)
        val pcapPath = args.outDir.resolve("spike-${LocalDateTime.now().format(FILE_TIME_FORMAT)}.pcap")
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
            val runner = SpikeRunner(
                radio = link,
                apConfig = pairing.apConfig,
                capture = PcapWriter(sink),
                captureLabel = pcapPath.toString(),
                options = SpikeOptions(
                    vid = args.vid,
                    pid = args.pid,
                    txPowerIndex = args.txPowerIndex,
                    readyTimeoutMs = args.readyTimeoutMs,
                ),
                primitives = primitives,
            )
            try {
                link.listen()
                runner.start(scope)
                supervisor.start()
                armBringUp(scope, link, runner, stop) { printPairing(pairing, args, console, onAir = true) }
                if (args.durationSeconds > 0) {
                    scope.launch {
                        delay(args.durationSeconds * MILLIS_PER_SECOND)
                        stop.complete(StopRequest("duration elapsed", failed = false))
                    }
                }
                console("Spike running; capture goes to $pcapPath. Press Enter to stop (Ctrl+C also works).")

                val request = stop.await()
                log.i { "runSpikeSession(): stopping -> reason=${request.reason} failed=${request.failed}" }
                if (request.failed) EXIT_RADIO_FAILED else EXIT_OK
            } finally {
                // On the runner's own dispatcher, as SpikeRunner requires, and even if this coroutine was cancelled.
                withContext(NonCancellable + dispatcher) {
                    runner.stop()
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
        cleanupDone.countDown()
    }
}

/**
 * Waits for radiod to connect (a bad radiod build or a missing libusb library shows up here as "never connects"), then
 * brings the radio up every time a radiod connection is established, so a radiod restart re-arms the beacon, and calls
 * [onRadioUp] each time the beacon is on air. A failure ends the session through [stop].
 */
private fun armBringUp(
    scope: CoroutineScope,
    link: DesktopRadioLink,
    runner: SpikeRunner,
    stop: CompletableDeferred<StopRequest>,
    onRadioUp: () -> Unit,
) {
    val log = Logger.withTag(TAG)
    scope.launch {
        val connected = withTimeoutOrNull(RADIOD_CONNECT_TIMEOUT_MS) { link.connected.first { it } } != null
        if (!connected) {
            log.e { "armBringUp(): radiod did not connect -> timeoutMs=$RADIOD_CONNECT_TIMEOUT_MS (run with --verbose for radiod's output)" }
            stop.complete(StopRequest("radiod did not connect", failed = true))
            return@launch
        }
        link.connected.collectLatest { isConnected ->
            if (!isConnected) return@collectLatest
            try {
                runner.bringUp()
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
 * Prints the pairing network and its QR payload. The payload (which contains the seed) is exactly what the QR code
 * shows to the kart's camera; it is printed to the console on purpose, never to the log.
 *
 * It is printed twice: at start-up, while the radio is still coming up (the network does not exist yet, so the kart
 * must not scan it then), and again once the beacon is on air, so the code to scan is the last thing on screen
 * instead of being pushed out of view by the bring-up log.
 *
 * @param onAir true once the pairing beacon is on air.
 */
private fun printPairing(pairing: SpikePairing, args: SpikeArgs, console: (String) -> Unit, onAir: Boolean) {
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
    if (onAir) {
        console("Put the kart in pairing mode (front LEDs blinking) and point its camera at the QR code.")
    } else {
        console("Wait: the radio is coming up (about a minute on Windows). The QR code is printed again once the network is on air; scan that one.")
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
private val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val RADIOD_CANDIDATES = listOf(
    "radiod/build/Release/radiod.exe",
    "radiod/build/radiod.exe",
    "radiod/build/RelWithDebInfo/radiod.exe",
    "radiod/build/radiod",
)
