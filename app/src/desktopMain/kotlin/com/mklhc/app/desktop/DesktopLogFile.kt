package com.mklhc.app.desktop

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/*
 * The desktop app's log file, so a hardware run keeps its whole log.
 *
 * Original code.
 */

/**
 * A Kermit [LogWriter] that writes every log line to a file as well, so a hardware run keeps its whole log: the
 * Terminal panel keeps only its last 1,000 lines, and a drive or a pairing is read back from the log afterwards.
 *
 * Business logic: the desktop app opens one file per run in the data directory's `logs` folder (see [open]), named after
 * the start time, and keeps the [DEFAULT_KEEP] newest. Each line is `yyyy-MM-dd HH:mm:ss.SSS Severity (Tag) message`,
 * followed by the stack trace of a throwable when there is one. The app's log lines never hold a key, the master secret
 * or a seed (a project rule), so neither does the file; it holds the run's events, so it stays on this machine like the
 * data directory itself.
 *
 * Logging never waits for the disk: [log] formats the line and queues it, and one background thread writes the queue and
 * flushes whenever it is empty, so a line reaches the file within moments and a killed process loses at most the last
 * few. A writer that falls behind (more than [queueCapacity] lines waiting) drops the new lines and says how many in the
 * file once it catches up. A write that fails (a full disk) stops the file for good with one warning; the app goes on.
 * [close] writes what is queued, waiting at most [CLOSE_WAIT_MS] in all, and closes the file; lines logged after it are
 * ignored, and so is a line logged by another thread in the instant [close] starts. Lines end with `\n`, stack traces
 * included. Thread-safe.
 *
 * @property path the file the log goes to.
 * @param out the stream the lines go to; the writer owns and closes it.
 * @param queueCapacity how many lines may wait for the disk before new ones are dropped.
 * @param lineTime the time stamped on each line.
 * @param log where the file reports a failure and its closing; those lines reach the terminal, not the file (it has
 *   stopped by then).
 */
class DesktopLogFile internal constructor(
    val path: Path,
    out: OutputStream,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val lineTime: () -> LocalDateTime = LocalDateTime::now,
    private val log: Logger = Logger.withTag(TAG),
) : LogWriter(), AutoCloseable {
    private val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
    private val queue = ArrayBlockingQueue<String>(queueCapacity)
    private val dropped = AtomicLong()
    private val closing = AtomicBoolean(false)

    @Volatile
    private var failed = false

    /** Lines on the disk: counted when a flush succeeds, so a failed flush never counts the lines it lost. */
    @Volatile
    private var linesWritten = 0L

    /** Lines handed to the buffered writer since the last successful flush; only the writer thread touches it. */
    private var linesBuffered = 0L

    private val thread = Thread(::drain, THREAD_NAME).apply {
        isDaemon = true
        start()
    }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        if (closing.get() || failed) return
        val line = buildString {
            append(LINE_TIME.format(lineTime())).append(' ').append(severity.name)
            append(" (").append(tag).append(") ").append(message)
            throwable?.let { append('\n').append(it.stackTraceToString().trimEnd().replace(WINDOWS_LINE_END, "\n")) }
        }
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    /** Writes the queued lines and closes the file, waiting at most [CLOSE_WAIT_MS] in all; idempotent, never throws. */
    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLOSE_WAIT_MS)
        val ended = queue.offer(END, CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) && run {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left > 0) thread.join(left)
            !thread.isAlive
        }
        when {
            !ended -> log.w { "close(): the log file did not finish writing in time, the process exit ends it -> path=$path lines=$linesWritten" }
            failed -> log.w { "close(): the log file had stopped after a write failure -> path=$path lines=$linesWritten" }
            else -> log.i { "close(): log file closed -> path=$path lines=$linesWritten" }
        }
    }

    /** The writer thread: takes the queued lines, writes them and flushes whenever the queue is empty. */
    private fun drain() {
        while (true) {
            val line = queue.take()
            if (line === END) break
            writeOrStop { appendLine(line) }
            linesBuffered++
            if (queue.isEmpty()) flushWithDroppedNotice()
        }
        flushWithDroppedNotice()
        try {
            writer.close()
        } catch (ignored: IOException) {
            // Already reported by the failed write, or nothing left to lose.
        }
    }

    /** Writes how many lines were dropped since the last notice, if any, then flushes and counts the flushed lines. */
    private fun flushWithDroppedNotice() {
        val count = dropped.getAndSet(0)
        if (count > 0L) {
            writeOrStop {
                append(LINE_TIME.format(lineTime())).append(' ').append(Severity.Warn.name).append(" ($TAG) ")
                appendLine("log(): the log file fell behind, lines were dropped -> dropped=$count")
            }
        }
        writeOrStop { flush() }
        if (!failed) linesWritten += linesBuffered
        linesBuffered = 0
    }

    /** Runs [step] on the writer unless a write already failed; the first failure stops the file with one warning. */
    private inline fun writeOrStop(step: BufferedWriter.() -> Unit) {
        if (failed) return
        try {
            writer.step()
        } catch (failure: IOException) {
            failed = true
            log.e { "writeOrStop(): the log file cannot be written, it stops here -> path=$path message=${failure.message}" }
        }
    }

    companion object {
        /** The log files' name prefix. */
        const val FILE_PREFIX = "mklhc-"

        /** The log files' extension. */
        const val FILE_EXTENSION = ".log"

        /** How many log files [open] keeps, the new one included. */
        const val DEFAULT_KEEP = 10

        /** How many lines may wait for the disk before new ones are dropped. */
        const val DEFAULT_QUEUE_CAPACITY = 10_000

        /** How long [close] waits for the queued lines to be written. */
        const val CLOSE_WAIT_MS = 2_000L

        private const val TAG = "DesktopLogFile"
        private const val THREAD_NAME = "mklhc-log-file"
        private const val WINDOWS_LINE_END = "\r\n"

        /** A log file's name: the start time, and the attempt number when a run in the same second came first. */
        private val LOG_FILE_NAME = Regex("""mklhc-(\d{8}-\d{6})(?:-(\d+))?\.log""")
        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val LINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        /** The end-of-queue marker [close] sends; compared by identity, so no logged line can be mistaken for it. */
        private val END = String(charArrayOf('\u0000'))

        /**
         * Creates [directory] if needed, opens a new log file in it named after [now], `mklhc-yyyyMMdd-HHmmss.log` (with
         * `-2`, `-3`... added when a file of that name already exists), then deletes the oldest log files so that [keep]
         * remain, the new one included. Only regular files named exactly like a log file are ever deleted, oldest first
         * by start time and then attempt number. It logs nothing itself: the caller says where the log goes once the file
         * is attached, so that line reaches the file too.
         *
         * @throws IOException when the folder or the file cannot be created.
         */
        fun open(
            directory: Path,
            keep: Int = DEFAULT_KEEP,
            now: () -> LocalDateTime = LocalDateTime::now,
            lineTime: () -> LocalDateTime = LocalDateTime::now,
            log: Logger = Logger.withTag(TAG),
        ): DesktopLogFile {
            Files.createDirectories(directory)
            val stem = "$FILE_PREFIX${FILE_TIME.format(now())}"
            var attempt = 1
            while (true) {
                val name = if (attempt == 1) "$stem$FILE_EXTENSION" else "$stem-$attempt$FILE_EXTENSION"
                val path = directory.resolve(name)
                val stream = try {
                    Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                } catch (exists: FileAlreadyExistsException) {
                    attempt++
                    continue
                }
                deleteOldLogFiles(directory, keep = keep, current = path, log = log)
                return DesktopLogFile(path, stream, lineTime = lineTime, log = log)
            }
        }

        /** Deletes the oldest log files in [directory] but [current] so that [keep] remain with it; best effort. */
        private fun deleteOldLogFiles(directory: Path, keep: Int, current: Path, log: Logger) {
            val older = try {
                directory.listDirectoryEntries("$FILE_PREFIX*$FILE_EXTENSION")
                    .filter { it != current && Files.isRegularFile(it) }
                    .mapNotNull { file -> LOG_FILE_NAME.matchEntire(file.name)?.let { match -> file to match } }
                    .sortedWith(compareBy({ it.second.groupValues[1] }, { it.second.groupValues[2].toIntOrNull() ?: 1 }))
                    .map { it.first }
            } catch (failure: IOException) {
                log.w { "deleteOldLogFiles(): the old log files could not be listed -> directory=$directory message=${failure.message}" }
                return
            }
            for (stale in older.dropLast((keep - 1).coerceAtLeast(0))) {
                try {
                    Files.deleteIfExists(stale)
                } catch (failure: IOException) {
                    log.w { "deleteOldLogFiles(): an old log file could not be deleted -> path=$stale message=${failure.message}" }
                }
            }
        }
    }
}
