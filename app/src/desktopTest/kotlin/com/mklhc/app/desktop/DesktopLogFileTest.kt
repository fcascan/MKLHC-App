package com.mklhc.app.desktop

import co.touchlab.kermit.Severity
import com.mklhc.app.LogCapture
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DesktopLogFile]: the file it opens, the line format, the pruning of old files, the close, a writer that falls behind
 * and a failing write.
 */
class DesktopLogFileTest {

    private val tempDir: Path = Files.createTempDirectory("mklhc-log-file-test")
    private val logs = LogCapture()

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun openCreatesTheFolderAndANewFileNamedAfterTheStartTime() {
        val folder = tempDir.resolve("logs")

        val first = DesktopLogFile.open(folder, now = { START }, log = logs.logger())
        val second = first.use { DesktopLogFile.open(folder, now = { START }, log = logs.logger()) }
        second.close()

        assertEquals(folder.resolve("mklhc-20261023-141503.log"), first.path)
        assertEquals(folder.resolve("mklhc-20261023-141503-2.log"), second.path, "a run in the same second gets its own file")
    }

    @Test
    fun eachLogCallIsOneLineWithTheTimeTheSeverityTheTagAndTheMessage() {
        val file = DesktopLogFile.open(tempDir, now = { START }, lineTime = { LINE_TIME }, log = logs.logger())

        file.log(Severity.Info, "start(): starting the session flow", "KartSessionRepository", null)
        file.log(Severity.Warn, "runGuarded(): teleop send ended -> message=gone", "KartSessionRepository", null)
        file.close()

        assertEquals(
            listOf(
                "2026-10-23 14:15:07.042 Info (KartSessionRepository) start(): starting the session flow",
                "2026-10-23 14:15:07.042 Warn (KartSessionRepository) runGuarded(): teleop send ended -> message=gone",
            ),
            file.path.readLines(),
        )
    }

    @Test
    fun aThrowableIsWrittenAfterItsLineWithItsStackTrace() {
        val file = DesktopLogFile.open(tempDir, now = { START }, lineTime = { LINE_TIME }, log = logs.logger())

        file.log(Severity.Error, "main(): the app failed", "Main", IllegalStateException("no display"))
        file.close()

        val lines = file.path.readLines()
        assertEquals("2026-10-23 14:15:07.042 Error (Main) main(): the app failed", lines[0])
        assertEquals("java.lang.IllegalStateException: no display", lines[1])
        assertTrue(lines[2].trimStart().startsWith("at "), "the stack trace follows: $lines")
    }

    @Test
    fun openKeepsOnlyTheNewestLogFilesAndLeavesOtherFilesAlone() {
        val old = (1..12).map { day -> tempDir.resolve("mklhc-202610%02d-090000.log".format(day)).also { it.writeText("old") } }
        val unrelated = tempDir.resolve("notes.txt").also { it.writeText("keep me") }

        DesktopLogFile.open(tempDir, keep = 10, now = { START }, log = logs.logger()).close()

        val remaining = tempDir.listDirectoryEntries("mklhc-*.log").map { it.name }.sorted()
        assertEquals(10, remaining.size, "the new file and the 9 newest old ones: $remaining")
        assertEquals("mklhc-20261023-141503.log", remaining.last())
        assertFalse(Files.exists(old[0]), "the oldest is deleted")
        assertFalse(Files.exists(old[2]), "the third oldest is deleted")
        assertTrue(Files.exists(old[3]), "the ninth newest is kept")
        assertTrue(Files.exists(unrelated), "a file that is not a log is never deleted")
    }

    @Test
    fun pruningOrdersRunsOfTheSameSecondByAttemptAndSparesLookAlikes() {
        val base = tempDir.resolve("mklhc-20261022-100000.log").also { it.writeText("1st") }
        val second = tempDir.resolve("mklhc-20261022-100000-2.log").also { it.writeText("2nd") }
        val tenth = tempDir.resolve("mklhc-20261022-100000-10.log").also { it.writeText("10th") }
        val lookAlike = tempDir.resolve("mklhc-0-crash.log").also { it.writeText("renamed by hand") }
        val folderLookAlike = Files.createDirectory(tempDir.resolve("mklhc-20261001-000000.log"))

        DesktopLogFile.open(tempDir, keep = 3, now = { START }, log = logs.logger()).close()

        assertFalse(Files.exists(base), "the first run of that second is the oldest")
        assertTrue(Files.exists(second), "-2 is newer than the base name")
        assertTrue(Files.exists(tenth), "-10 is newer than -2")
        assertTrue(Files.exists(lookAlike), "a name that is not a log file's is never deleted")
        assertTrue(Files.isDirectory(folderLookAlike), "a directory is never deleted")
    }

    @Test
    fun closeWritesEverythingQueuedAndLaterCallsAreIgnored() {
        val file = DesktopLogFile.open(tempDir, now = { START }, lineTime = { LINE_TIME }, log = logs.logger())

        repeat(500) { file.log(Severity.Debug, "line $it", "Test", null) }
        file.close()
        file.log(Severity.Info, "after close", "Test", null)
        file.close()

        val lines = file.path.readLines()
        assertEquals(500, lines.size)
        assertTrue(lines.last().endsWith("line 499"), lines.last())
        assertTrue(logs.has("close(): log file closed -> path=${file.path} lines=500"), logs.text())
    }

    @Test
    fun aWriterThatFallsBehindDropsLinesAndSaysHowMany() {
        val stream = BlockingStream()
        val file = DesktopLogFile(tempDir.resolve("x.log"), stream, queueCapacity = 2, lineTime = { LINE_TIME }, log = logs.logger())

        file.log(Severity.Info, "line 1", "Test", null)
        assertTrue(stream.entered.await(5, TimeUnit.SECONDS), "the writer thread took the first line")
        repeat(4) { file.log(Severity.Info, "line ${it + 2}", "Test", null) }
        stream.release.countDown()
        file.close()

        val text = stream.written.toString(Charsets.UTF_8)
        assertTrue("line 1" in text && "line 2" in text && "line 3" in text, text)
        assertFalse("line 4" in text || "line 5" in text, "the queue held two lines: $text")
        assertTrue("the log file fell behind, lines were dropped -> dropped=2" in text, text)
    }

    @Test
    fun aFailingWriteStopsTheFileWithOneErrorAndNeverThrows() {
        val file = DesktopLogFile(tempDir.resolve("x.log"), FailingStream(), lineTime = { LINE_TIME }, log = logs.logger())

        repeat(3) { file.log(Severity.Info, "line $it", "Test", null) }
        file.close()

        val errors = logs.text().lines().count { "ERROR: writeOrStop(): the log file cannot be written, it stops here" in it }
        assertEquals(1, errors, logs.text())
        assertTrue(logs.has("WARN: close(): the log file had stopped after a write failure -> path=${file.path} lines=0"), logs.text())
    }

    @Test
    fun closeGivesUpWithinItsBoundWhenTheDiskIsStuck() {
        val stream = BlockingStream()
        val file = DesktopLogFile(tempDir.resolve("x.log"), stream, queueCapacity = 1, lineTime = { LINE_TIME }, log = logs.logger())
        file.log(Severity.Info, "line 1", "Test", null)
        assertTrue(stream.entered.await(5, TimeUnit.SECONDS))
        file.log(Severity.Info, "line 2", "Test", null)

        val started = System.nanoTime()
        file.close()
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        stream.release.countDown()

        assertTrue(tookMs < DesktopLogFile.CLOSE_WAIT_MS + 500, "close took $tookMs ms")
        assertTrue(logs.has("WARN: close(): the log file did not finish writing in time"), logs.text())
    }

    /** Blocks its first write until [release] opens, so the test can fill the queue behind it. */
    private class BlockingStream : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = java.io.ByteArrayOutputStream()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            synchronized(written) { written.write(b, off, len) }
        }
    }

    private class FailingStream : OutputStream() {
        override fun write(b: Int) = throw IOException("disk full")

        override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
    }

    private companion object {
        val START: LocalDateTime = LocalDateTime.of(2026, 10, 23, 14, 15, 3)
        val LINE_TIME: LocalDateTime = LocalDateTime.of(2026, 10, 23, 14, 15, 7, 42_000_000)
    }
}
