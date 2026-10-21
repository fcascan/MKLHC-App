package com.mklhc.app.desktop

import com.mklhc.app.LogCapture
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [VideoDatagramDump]: the file it opens, the record layout, the size cap, a failing write and the close.
 */
class VideoDatagramDumpTest {

    private val tempDir: Path = Files.createTempDirectory("mklhc-video-dump-test")
    private val logs = LogCapture()

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun openCreatesTheFolderAndANewFileNamedAfterTheTimeHoldingTheMagic() {
        val folder = tempDir.resolve("captures").resolve("video")

        val first = VideoDatagramDump.open(folder, now = { START }, log = logs.logger())
        val second = first.use { VideoDatagramDump.open(folder, now = { START }, log = logs.logger()) }
        second.close()

        assertEquals(folder.resolve("video-20261021-041343.viddump"), first.path)
        assertEquals(folder.resolve("video-20261021-041343-2.viddump"), second.path, "a run in the same second gets its own file")
        assertContentEquals(VideoDatagramDump.MAGIC.encodeToByteArray(), Files.readAllBytes(first.path))
        assertTrue(logs.has("open(): dumping the raw video datagrams -> path=${first.path}"), logs.text())
    }

    @Test
    fun eachDatagramIsARecordOfItsTimeItsLengthAndItsOctets() {
        val times = ArrayDeque(listOf(1_700_000_000_000_001L, 1_700_000_000_250_000L))
        val dump = VideoDatagramDump.open(tempDir, clock = { times.removeFirst() }, now = { START }, log = logs.logger())
        val video = ByteArray(1416) { it.toByte() }
        val telemetryLike = byteArrayOf(3, 1, 4)

        dump.write(video)
        dump.write(telemetryLike)
        dump.close()

        val file = ByteBuffer.wrap(Files.readAllBytes(dump.path)).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(VideoDatagramDump.MAGIC.length).also { file.get(it) }
        assertEquals(VideoDatagramDump.MAGIC, magic.decodeToString())
        assertEquals(1_700_000_000_000_001L, file.getLong())
        assertEquals(video.size, file.getInt())
        assertContentEquals(video, ByteArray(video.size).also { file.get(it) })
        assertEquals(1_700_000_000_250_000L, file.getLong())
        assertEquals(telemetryLike.size, file.getInt())
        assertContentEquals(telemetryLike, ByteArray(telemetryLike.size).also { file.get(it) })
        assertEquals(0, file.remaining(), "nothing follows the last record")
        assertEquals(2, dump.datagramsWritten)
    }

    @Test
    fun theSizeCapStopsTheDumpWithOneWarningAndKeepsTheRecordsBeforeIt() {
        val cap = VideoDatagramDump.MAGIC.length + RECORD_HEADER + 10L + 5
        val dump = VideoDatagramDump.open(tempDir, maxBytes = cap, now = { START }, log = logs.logger())

        dump.write(ByteArray(10))
        dump.write(ByteArray(10))
        dump.write(ByteArray(1))
        dump.close()

        assertEquals(VideoDatagramDump.MAGIC.length + RECORD_HEADER + 10L, Files.size(dump.path), "only the record that fits")
        assertEquals(1, dump.datagramsWritten)
        assertEquals(1, logs.count("write(): the dump reached its size cap, it stops here"), logs.text())
    }

    @Test
    fun aWriteThatFailsStopsTheDumpWithOneWarningAndNeverThrows() {
        val stream = FailingStream()
        val dump = VideoDatagramDump(tempDir.resolve("broken.viddump"), stream, log = logs.logger())
        stream.failing = true

        dump.write(ByteArray(10))
        dump.write(ByteArray(10))
        dump.close()

        assertEquals(1, stream.failedWrites, "the dump stops at the first failure")
        assertEquals(0, dump.datagramsWritten)
        assertEquals(1, logs.count("write(): the dump cannot be written, it stops here"), logs.text())
        assertTrue(stream.closed, "the stream is still closed")
    }

    @Test
    fun writesAfterTheCloseAreIgnoredAndASecondCloseDoesNothing() {
        val dump = VideoDatagramDump.open(tempDir, now = { START }, log = logs.logger())
        dump.write(ByteArray(4))
        dump.close()

        dump.write(ByteArray(4))
        dump.close()

        assertEquals(VideoDatagramDump.MAGIC.length + RECORD_HEADER + 4L, Files.size(dump.path))
        assertEquals(1, logs.count("close(): video dump closed -> path=${dump.path} datagrams=1"), logs.text())
    }

    /** A stream that accepts the magic, then fails every write once [failing] is set. */
    private class FailingStream : OutputStream() {
        var failing = false
        var failedWrites = 0
        var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failing) {
                failedWrites++
                throw IOException("disk full")
            }
        }

        override fun close() {
            closed = true
        }
    }

    private fun LogCapture.count(fragment: String): Int = text().lines().count { fragment in it }

    private companion object {
        val START: LocalDateTime = LocalDateTime.of(2026, 10, 21, 4, 13, 43)
        const val RECORD_HEADER = 12
    }
}
