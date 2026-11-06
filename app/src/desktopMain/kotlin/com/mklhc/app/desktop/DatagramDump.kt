package com.mklhc.app.desktop

import co.touchlab.kermit.Logger
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Raw datagram dumps of the desktop app, for studying the kart's video and telemetry streams with real data.
 *
 * Original code. The file format is this project's own (described below); nothing was taken from elsewhere.
 */

/**
 * Writes every datagram the kart sends on one of its streams ([Stream]), exactly as received, to a file, so the stream
 * can be studied offline: the video's layout against what [com.mklhc.core.kart_protocol.video.LspVideoReassembler]
 * assumes, and the telemetry records [com.mklhc.core.kart_protocol.telemetry.TelemetryDecoder] does not decode yet.
 *
 * Business logic: the desktop app opens one dump per stream and run when that stream's variable names a folder
 * (`MKLHC_VIDEO_DUMP`, `MKLHC_TELEMETRY_DUMP`; see [DesktopRadioSettings]) and hands it each datagram's UDP payload
 * through the session's tap for that port. Only that port's payloads are written: no 802.11 frame, no control traffic,
 * so the file never holds a network key, the master secret or the pairing handshake. A video dump holds the camera's
 * picture and a telemetry dump the kart's motion, so both stay on this machine (`*.viddump` and `*.teldump` are
 * git-ignored).
 *
 * File layout (multi-octet fields little-endian), the same for every stream but the magic:
 *
 * ```
 * Header, 8 octets, written when the dump is created
 *   0  8 octets  magic, "MKLHCVD1" for video, "MKLHCTD1" for telemetry (ASCII)
 *
 * Record, one per datagram
 *   0  u64       receive time, microseconds since the Unix epoch (wall clock: a clock sync can make it jump)
 *   8  u32       payload length
 *  12  ...       the UDP payload, as received (for telemetry, the type octet first)
 * ```
 *
 * Every record goes to the file in one unbuffered write, so the file stays usable when the process is killed; a kill
 * or a full disk in the middle of a write can leave a short last record, which a reader drops. The dump stops for
 * good, with one warning, when the next record would take the file past [maxBytes] (the kart sends about 280 KB/s of
 * video, so the default cap is reached after roughly a quarter of an hour; the telemetry is far smaller), or with one
 * error when a write fails (a full disk): the session never sees either. The writes run on the session's dispatcher, so the folder
 * should be on a local disk: a slow one (a network share, a synced folder) delays the session's other work too. Writes
 * after [close] are ignored. Thread-safe: the session writes on its own dispatcher while the app's exit path closes the
 * dump on another thread.
 *
 * @property stream which of the kart's streams this dump holds.
 * @property path the file the dump writes.
 * @param out the stream the records go to; the dump owns and closes it.
 * @param maxBytes the size the file never exceeds, header included.
 * @param clock the receive time stamped on each record, in microseconds since the Unix epoch.
 * @param log where the dump reports opening, stopping and closing; never the datagrams' contents.
 */
class DatagramDump internal constructor(
    val stream: Stream,
    val path: Path,
    private val out: OutputStream,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = ::epochMicros,
    private val log: Logger = Logger.withTag(TAG),
) : AutoCloseable {
    private val lock = Any()
    private var stopped = false
    private var closed = false

    /** Datagrams written so far. */
    @Volatile
    var datagramsWritten: Long = 0
        private set

    /** Octets in the file so far, header included. */
    @Volatile
    var bytesWritten: Long = 0
        private set

    init {
        val magic = stream.magic.encodeToByteArray()
        out.write(magic)
        out.flush()
        bytesWritten = magic.size.toLong()
    }

    /**
     * Appends [payload] as one record stamped with the current time; never throws.
     *
     * @param payload a datagram's UDP payload; copied out before this returns, so the caller keeps it.
     */
    fun write(payload: ByteArray) {
        synchronized(lock) {
            if (stopped || closed) return
            val recordSize = RECORD_HEADER_BYTES + payload.size.toLong()
            if (bytesWritten + recordSize > maxBytes) {
                stopped = true
                log.w { "write(): the dump reached its size cap, it stops here -> path=$path maxBytes=$maxBytes datagrams=$datagramsWritten" }
                return
            }
            // One write per record on the unbuffered stream: a failed write leaves nothing behind for close() to retry.
            val record = ByteBuffer.allocate(recordSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(clock())
                .putInt(payload.size)
                .put(payload)
                .array()
            try {
                out.write(record)
                out.flush()
            } catch (failure: IOException) {
                stopped = true
                log.e(failure) { "write(): the dump cannot be written, it stops here -> path=$path message=${failure.message}" }
                return
            }
            bytesWritten += recordSize
            datagramsWritten++
        }
    }

    /** Closes the file; idempotent, never throws. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                out.close()
            } catch (failure: IOException) {
                log.e(failure) { "close(): the dump could not be closed cleanly -> path=$path message=${failure.message}" }
            }
            log.i { "close(): ${stream.label} dump closed -> path=$path datagrams=$datagramsWritten bytes=$bytesWritten" }
        }
    }

    /**
     * A stream of the kart that can be dumped: the name its files and log lines use, the magic its files start with and
     * their extension (both git-ignored).
     *
     * @property label the stream's name in the file name and the log lines.
     * @property magic the 8 ASCII characters the file starts with; the digit is the format version.
     * @property fileExtension the extension of the stream's dump files.
     */
    enum class Stream(val label: String, val magic: String, val fileExtension: String) {
        /** The video stream port's datagrams (H.264 in the kart's LSP framing). */
        VIDEO("video", "MKLHCVD1", ".viddump"),

        /** The telemetry port's datagrams (a type octet, then the record). */
        TELEMETRY("telemetry", "MKLHCTD1", ".teldump"),
    }

    companion object {
        /** The default size cap of a dump file: 256 MiB. */
        const val DEFAULT_MAX_BYTES = 256L * 1024 * 1024

        private const val TAG = "DatagramDump"
        private const val RECORD_HEADER_BYTES = Long.SIZE_BYTES + Int.SIZE_BYTES
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val NANOS_PER_MICRO = 1_000L
        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * Creates [directory] if needed and opens a new dump file of [stream] in it named after [now],
         * `<label>-yyyyMMdd-HHmmss<extension>` (`video-...viddump`, `telemetry-...teldump`), with `-2`, `-3`... added
         * when a file of that name already exists.
         *
         * @throws IOException when the folder or the file cannot be created.
         */
        fun open(
            stream: Stream,
            directory: Path,
            maxBytes: Long = DEFAULT_MAX_BYTES,
            clock: () -> Long = ::epochMicros,
            now: () -> LocalDateTime = LocalDateTime::now,
            log: Logger = Logger.withTag(TAG),
        ): DatagramDump {
            Files.createDirectories(directory)
            val stem = "${stream.label}-${FILE_TIME.format(now())}"
            var attempt = 1
            while (true) {
                val name = if (attempt == 1) "$stem${stream.fileExtension}" else "$stem-$attempt${stream.fileExtension}"
                val path = directory.resolve(name)
                val output = try {
                    Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                } catch (exists: FileAlreadyExistsException) {
                    attempt++
                    continue
                }
                val dump = try {
                    DatagramDump(stream, path, output, maxBytes, clock, log)
                } catch (failure: IOException) {
                    output.close()
                    Files.deleteIfExists(path)
                    throw failure
                }
                log.i { "open(): dumping the raw ${stream.label} datagrams -> path=$path maxBytes=$maxBytes" }
                return dump
            }
        }

        /** The current time in microseconds since the Unix epoch. */
        private fun epochMicros(): Long {
            val now = Instant.now()
            return now.epochSecond * MICROS_PER_SECOND + now.nano / NANOS_PER_MICRO
        }
    }
}
