package com.mklhc.app.spike

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.mklhc.core.wifi_ap.ap.ApConfig
import com.mklhc.core.wifi_ap.ap.RadioOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/*
 * Console entry point of the radio hardware spike (Task 1.8), resolved as `com.mklhc.app.spike.SpikeMainKt` and run
 * with `./gradlew :app:runSpike`.
 *
 * Original code; it parses the command line and hands the rest to [runSpikeSession] (SpikeSession.kt).
 */

/**
 * Starts the spike: launches radiod, hosts a pairing network, prints the pairing QR payload, saves the QR code as an
 * image and writes a decrypted pcap.
 *
 * Business logic: this is the GO/NO-GO run of Phase 1, now on the real link layer. `main` only parses the command
 * line and exits with the result; [runSpikeSession] wires DesktopRadioLink (the socket radiod connects to),
 * RadiodSupervisor (keeps the radiod process alive), RadioAccessPoint (the access point and the pcap capture),
 * NetstackNode (ARP, DHCP and TCP on top of it), the pairing probe (the RCD pairing handshake) and the pcap file, then
 * waits to be stopped: Enter on the console, the `--duration` timer, a failed bring-up or the JVM shutting down. The
 * spike stops at the RCD handshake on purpose, so the kart keeps its stored pairing. Run it with the adapter bound to
 * WinUSB and the kart in pairing mode; see docs/HARDWARE_SETUP.md.
 *
 * Exit codes: 0 stopped normally, 1 the radio could not be brought up, 2 bad command line or radiod not found.
 */
fun main(args: Array<String>) {
    // Force UTF-8 on stdout: the console QR code is drawn with block characters.
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))
    val parsed = try {
        parseSpikeArgs(args.toList())
    } catch (invalid: SpikeArgsException) {
        println("error: ${invalid.message}")
        println(SPIKE_USAGE)
        exitProcess(EXIT_USAGE)
    }
    if (parsed.help) {
        println(SPIKE_USAGE)
        return
    }
    Logger.setMinSeverity(if (parsed.verbose) Severity.Debug else Severity.Info)
    val stop = CompletableDeferred<StopRequest>()
    val cleanupDone = CountDownLatch(1)
    installConsoleStopTriggers(stop, cleanupDone)
    exitProcess(runBlocking { runSpikeSession(parsed, stop, cleanupDone) })
}

/** The parsed command line of [main]. */
internal data class SpikeArgs(
    val radiod: Path? = null,
    val vid: Int = RadioOptions.DEFAULT_VID,
    val pid: Int = RadioOptions.DEFAULT_PID,
    val channel: Int = DEFAULT_CHANNEL,
    val txPowerIndex: Int = 0,
    val beaconIntervalTu: Int = ApConfig.DEFAULT_BEACON_INTERVAL_TU,
    val outDir: Path = Path.of(DEFAULT_OUT_DIR),
    val durationSeconds: Long = 0,
    val readyTimeoutMs: Long = RadioOptions.DEFAULT_READY_TIMEOUT_MS,
    val verbose: Boolean = false,
    val showQr: Boolean = true,
    val lightTerminal: Boolean = false,
    val help: Boolean = false,
)

/** A command-line problem; the message is shown to the user. */
internal class SpikeArgsException(message: String) : IllegalArgumentException(message)

/**
 * Parses the spike's command line: `--key value` or `--key=value` options and the flags `--verbose`, `--no-qr`,
 * `--light-terminal` and `--help`. Pure, so it can be unit-tested.
 *
 * @throws SpikeArgsException for an unknown option, a missing or malformed value, or a value out of range.
 */
internal fun parseSpikeArgs(args: List<String>): SpikeArgs {
    var parsed = SpikeArgs()
    var index = 0
    while (index < args.size) {
        val argument = args[index++]
        val name = argument.substringBefore('=')
        val inlineValue = if ('=' in argument) argument.substringAfter('=') else null

        fun value(): String = inlineValue
            ?: args.getOrNull(index++)
            ?: throw SpikeArgsException("$name needs a value")

        parsed = when (name) {
            "--radiod" -> parsed.copy(radiod = Path.of(value()))
            "--vid" -> parsed.copy(vid = hexNumber(name, value(), MAX_USB_ID))
            "--pid" -> parsed.copy(pid = hexNumber(name, value(), MAX_USB_ID))
            "--channel" -> parsed.copy(channel = decimalOneOf(name, value(), ApConfig.ALLOWED_CHANNELS.toSet()))
            "--tx-power" -> parsed.copy(txPowerIndex = decimalInRange(name, value(), 0..MAX_TX_POWER_INDEX))
            "--beacon-interval" -> parsed.copy(beaconIntervalTu = decimalInRange(name, value(), 1..MAX_BEACON_INTERVAL_TU))
            "--out" -> parsed.copy(outDir = Path.of(value()))
            "--duration" -> parsed.copy(durationSeconds = decimalInRange(name, value(), 0..MAX_DURATION_SECONDS).toLong())
            "--ready-timeout" -> parsed.copy(
                readyTimeoutMs = decimalInRange(name, value(), 1..MAX_READY_TIMEOUT_SECONDS) * MILLIS_PER_SECOND,
            )
            "--verbose" -> parsed.copy(verbose = true)
            "--no-qr" -> parsed.copy(showQr = false)
            "--light-terminal" -> parsed.copy(lightTerminal = true)
            "--help", "-h" -> parsed.copy(help = true)
            else -> throw SpikeArgsException("unknown option: $argument")
        }
    }
    return parsed
}

private fun hexNumber(name: String, text: String, max: Int): Int {
    val number = text.removePrefix("0x").removePrefix("0X").toIntOrNull(HEX_RADIX)
    if (number == null || number !in 0..max) throw SpikeArgsException("$name must be a hex number up to ${max.toString(HEX_RADIX)}: $text")
    return number
}

private fun decimalOneOf(name: String, text: String, allowed: Collection<Int>): Int {
    val number = text.toIntOrNull()
    if (number == null || number !in allowed) throw SpikeArgsException("$name must be one of $allowed: $text")
    return number
}

private fun decimalInRange(name: String, text: String, allowed: IntRange): Int {
    val number = text.toIntOrNull()
    if (number == null || number !in allowed) throw SpikeArgsException("$name must be ${allowed.first}..${allowed.last}: $text")
    return number
}

/**
 * Arms the ways a console run ends besides `--duration`: Enter on the console and the JVM shutting down (Ctrl+C).
 * [cleanupDone] is counted down by the session once its cleanup has finished, so the shutdown hook can wait for it
 * without stalling an ordinary exit (where it is already down).
 */
private fun installConsoleStopTriggers(stop: CompletableDeferred<StopRequest>, cleanupDone: CountDownLatch) {
    // Only a real line stops the run: a closed stdin (no console) returns null and must not end it.
    thread(isDaemon = true, name = "spike-stdin") {
        if (readlnOrNull() != null) stop.complete(StopRequest("Enter pressed", failed = false))
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            stop.complete(StopRequest("the JVM is shutting down", failed = false))
            // Let the main thread run its cleanup (stop beacon, SHUTDOWN) before the JVM goes away.
            cleanupDone.await(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)
        },
    )
}

private val SPIKE_USAGE = """
    Usage: ./gradlew :app:runSpike --args="[options]"

    Hosts the kart pairing network on a USB Wi-Fi adapter, waits for the kart to associate, answers its DHCP, ARP and
    RCD pairing handshake (it never changes the kart's stored pairing) and writes a decrypted pcap.

      --radiod <path>        the radiod executable (default: radiod/build/[Release/]radiod[.exe])
      --vid <hex>            adapter USB vendor id (default 0bda)
      --pid <hex>            adapter USB product id (default c812)
      --channel <1|6|11>     Wi-Fi channel (default $DEFAULT_CHANNEL)
      --tx-power <0..255>    TX power index; 0 keeps the chip default (default 0)
      --beacon-interval <n>  beacon interval in time units of 1024 us (default ${ApConfig.DEFAULT_BEACON_INTERVAL_TU})
      --out <dir>            directory for the pcap and the QR image (default $DEFAULT_OUT_DIR)
      --duration <seconds>   stop after this long; 0 runs until Enter or Ctrl+C (default 0)
      --ready-timeout <s>    seconds to wait for the radio to come up (default ${RadioOptions.DEFAULT_READY_TIMEOUT_MS / MILLIS_PER_SECOND})
      --verbose              debug logging, including radiod's own output
      --no-qr                do not draw the QR code in the console (the payload hex and the QR image are still produced)
      --light-terminal       draw the QR code for a light terminal background
      --help                 show this text
""".trimIndent()

private const val DEFAULT_CHANNEL = 6
private const val DEFAULT_OUT_DIR = "spike-captures"
private const val HEX_RADIX = 16
private const val MAX_USB_ID = 0xFFFF
private const val MAX_TX_POWER_INDEX = 255
private const val MAX_BEACON_INTERVAL_TU = 0xFFFF
private const val MAX_DURATION_SECONDS = 86_400
private const val MAX_READY_TIMEOUT_SECONDS = 600
private const val MILLIS_PER_SECOND = 1_000L
private const val SHUTDOWN_WAIT_MS = 10_000L
