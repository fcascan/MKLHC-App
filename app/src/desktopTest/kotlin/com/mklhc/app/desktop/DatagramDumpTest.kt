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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [DatagramDump]: the file it opens for each stream, the record layout, the size cap, a failing write and the close.
 */
class DatagramDumpTest {

    private val tempDir: Path = Files.createTempDirectory("mklhc-datagram-dump-test")
    private val logs = LogCapture()

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun openCreatesTheFolderAndANewFileNamedAfterTheTimeHoldingTheMagic() {
        val folder = tempDir.resolve("captures").resolve("video")

        val first = DatagramDump.open(DatagramDump.Stream.VIDEO, folder, now = { START }, log = logs.logger())
        val second = first.use { DatagramDump.open(DatagramDump.Stream.VIDEO, folder, now = { START }, log = logs.logger()) }
        second.close()

        assertEquals(folder.resolve("video-20261021-041343.viddump"), first.path)
        assertEquals(folder.resolve("video-20261021-041343-2.viddump"), second.path, "a run in the same second gets its own file")
        assertContentEquals("MKLHCVD1".encodeToByteArray(), Files.readAllBytes(first.path), "the video format is unchanged")
        assertTrue(logs.has("open(): dumping the raw video datagrams -> path=${first.path}"), logs.text())
    }

    @Test
    fun theTelemetryDumpHasItsOwnFileNameMagicAndLogLines() {
        val video = DatagramDump.open(DatagramDump.Stream.VIDEO, tempDir, now = { START }, log = logs.logger())
        val telemetry = DatagramDump.open(DatagramDump.Stream.TELEMETRY, tempDir, now = { START }, log = logs.logger())
        telemetry.write(byteArrayOf(3, 1, 4))
        telemetry.close()
        video.close()

        assertEquals(tempDir.resolve("telemetry-20261021-041343.teldump"), telemetry.path)
        assertNotEquals(video.path, telemetry.path, "both streams dumped into one folder in the same second keep apart")
        val bytes = Files.readAllBytes(telemetry.path)
        assertContentEquals("MKLHCTD1".encodeToByteArray(), bytes.copyOfRange(0, 8))
        assertEquals(8 + RECORD_HEADER + 3, bytes.size, "the same record layout as the video dump")
        assertTrue(logs.has("open(): dumping the raw telemetry datagrams -> path=${telemetry.path}"), logs.text())
        assertTrue(logs.has("close(): telemetry dump closed -> path=${telemetry.path} datagrams=1"), logs.text())
    }

    @Test
    fun eachDatagramIsARecordOfItsTimeItsLengthAndItsOctets() {
        val times = ArrayDeque(listOf(1_700_000_000_000_001L, 1_700_000_000_250_000L))
        val dump = DatagramDump.open(
            DatagramDump.Stream.VIDEO,
            tempDir,
            clock = { times.removeFirst() },
            now = { START },
            log = logs.logger(),
        )
        val video = ByteArray(1416) { it.toByte() }
        val short = byteArrayOf(3, 1, 4)

        dump.write(video)
        dump.write(short)
        dump.close()

        val file = ByteBuffer.wrap(Files.readAllBytes(dump.path)).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(DatagramDump.Stream.VIDEO.magic.length).also { file.get(it) }
        assertEquals(DatagramDump.Stream.VIDEO.magic, magic.decodeToString())
        assertEquals(1_700_000_000_000_001L, file.getLong())
        assertEquals(video.size, file.getInt())
        assertContentEquals(video, ByteArray(video.size).also { file.get(it) })
        assertEquals(1_700_000_000_250_000L, file.getLong())
        assertEquals(short.size, file.getInt())
        assertContentEquals(short, ByteArray(short.size).also { file.get(it) })
        assertEquals(0, file.remaining(), "nothing follows the last record")
        assertEquals(2, dump.datagramsWritten)
    }

    @Test
    fun theSizeCapStopsTheDumpWithOneWarningAndKeepsTheRecordsBeforeIt() {
        val cap = MAGIC_BYTES + RECORD_HEADER + 10L + 5
        val dump = DatagramDump.open(DatagramDump.Stream.TELEMETRY, tempDir, maxBytes = cap, now = { START }, log = logs.logger())

        dump.write(ByteArray(10))
        dump.write(ByteArray(10))
        dump.write(ByteArray(1))
        dump.close()

        assertEquals(MAGIC_BYTES + RECORD_HEADER + 10L, Files.size(dump.path), "only the record that fits")
        assertEquals(1, dump.datagramsWritten)
        assertEquals(1, logs.count("write(): the dump reached its size cap, it stops here"), logs.text())
    }

    @Test
    fun aWriteThatFailsStopsTheDumpWithOneWarningAndNeverThrows() {
        val stream = FailingStream()
        val dump = DatagramDump(DatagramDump.Stream.VIDEO, tempDir.resolve("broken.viddump"), stream, log = logs.logger())
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
        val dump = DatagramDump.open(DatagramDump.Stream.VIDEO, tempDir, now = { START }, log = logs.logger())
        dump.write(ByteArray(4))
        dump.close()

        dump.write(ByteArray(4))
        dump.close()

        assertEquals(MAGIC_BYTES + RECORD_HEADER + 4L, Files.size(dump.path))
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
        const val MAGIC_BYTES = 8
        const val RECORD_HEADER = 12
    }
}
