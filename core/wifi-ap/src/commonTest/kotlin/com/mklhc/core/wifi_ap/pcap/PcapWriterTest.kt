package com.mklhc.core.wifi_ap.pcap

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [PcapWriter]: the libpcap global header (magic, version, snaplen, link type), the per-frame record
 * header, the radiotap prefix and the frame bytes, all little-endian and checked byte for byte against golden
 * hex, plus empty and multi-frame captures, the snap-length cap, the injected clock and the flush-per-record contract.
 */
class PcapWriterTest {

    /** Collects everything written and counts flushes. */
    private class MemorySink : PcapSink {
        private var bytes = ByteArray(0)
        var flushCount = 0
            private set
        var writeCount = 0
            private set

        fun captured(): ByteArray = bytes

        override fun write(bytes: ByteArray) {
            this.bytes += bytes
            writeCount++
        }

        override fun flush() {
            flushCount++
        }
    }

    /** One decoded record of the stream: the header fields and the packet octets (radiotap plus 802.11 frame). */
    private class Record(val tsSec: Long, val tsUsec: Long, val inclLen: Int, val origLen: Int, val packet: ByteArray)

    private fun ByteArray.u32le(offset: Int): Long =
        (0 until 4).sumOf { index -> (this[offset + index].toLong() and 0xFF) shl (index * 8) }

    private fun ByteArray.u16le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    /** Splits the octets after the 24-octet global header into records, asserting the framing is consistent. */
    private fun records(stream: ByteArray): List<Record> {
        val result = ArrayList<Record>()
        var offset = 24
        while (offset < stream.size) {
            val inclLen = stream.u32le(offset + 8).toInt()
            val origLen = stream.u32le(offset + 12).toInt()
            val packet = stream.copyOfRange(offset + 16, offset + 16 + inclLen)
            result += Record(stream.u32le(offset), stream.u32le(offset + 4), inclLen, origLen, packet)
            offset += 16 + inclLen
        }
        assertEquals(stream.size, offset, "the records must tile the stream exactly")
        return result
    }

    // A short, recognisable 802.11-like frame: management frame control, then a counting pattern.
    private val frameA = "80000000ffffffffffff".hexToByteArray()
    private val frameB = "0840000000".hexToByteArray()

    // Radiotap: version 0, pad 0, length 8 (LE), present 0.
    private val radiotap = "0000080000000000"

    // ---- Global header ----------------------------------------------------------------------

    @Test
    fun `an empty capture is exactly the 24 octet global header`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })

        val expected =
            "d4c3b2a1" + // magic 0xa1b2c3d4, little-endian
                "0200" + // version major 2
                "0400" + // version minor 4
                "00000000" + // thiszone
                "00000000" + // sigfigs
                "ffff0000" + // snaplen 65535
                "7f000000" // network 127 = IEEE802_11_RADIOTAP
        assertEquals(expected, sink.captured().toHexString())
        assertEquals(24, sink.captured().size)
        assertEquals(0, writer.framesWritten)
        assertEquals(24L, writer.bytesWritten)
    }

    @Test
    fun `global header fields are little-endian and carry the snap length`() {
        val sink = MemorySink()
        PcapWriter(sink, snapLength = 0x0102, clock = { 0L })
        val header = sink.captured()

        assertEquals(0xA1B2C3D4L, header.u32le(0))
        assertEquals(2, header.u16le(4))
        assertEquals(4, header.u16le(6))
        assertEquals(0L, header.u32le(8))
        assertEquals(0L, header.u32le(12))
        assertEquals(0x0102L, header.u32le(16))
        assertEquals(127L, header.u32le(20))
        // Byte order is observable: the magic's low octet comes first, the snap length's low octet first.
        assertEquals("d4c3b2a1", header.copyOfRange(0, 4).toHexString())
        assertEquals("02010000", header.copyOfRange(16, 20).toHexString())
        assertEquals(PcapWriter.LINKTYPE_IEEE802_11_RADIOTAP, 127)
    }

    // ---- One frame --------------------------------------------------------------------------

    @Test
    fun `one frame is a 16 octet record header then the radiotap prefix then the frame`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })

        // 1_700_000_000 s and 123_456 us after the epoch.
        writer.append(frameA, timestampMicros = 1_700_000_000_123_456L)

        val stream = sink.captured().toHexString()
        val globalHeader = stream.substring(0, 48)
        val record = stream.substring(48)
        assertEquals(
            "00f15365" + // ts_sec 1_700_000_000 = 0x6553f100, little-endian
                "40e20100" + // ts_usec 123_456 = 0x0001e240, little-endian
                "12000000" + // incl_len = 8 radiotap + 10 frame = 18
                "12000000" + // orig_len = 18
                radiotap +
                "80000000ffffffffffff", // the frame, byte for byte
            record,
        )
        assertEquals(48, globalHeader.length)
        assertEquals(24 + 16 + 8 + frameA.size, sink.captured().size)
        assertEquals(1, writer.framesWritten)
        assertEquals((24 + 16 + 8 + frameA.size).toLong(), writer.bytesWritten)
    }

    @Test
    fun `record header lengths are radiotap plus frame and orig_len equals incl_len when nothing is truncated`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })

        writer.append(frameB, timestampMicros = 5L)

        val record = records(sink.captured()).single()
        assertEquals(8 + frameB.size, record.inclLen)
        assertEquals(8 + frameB.size, record.origLen)
        assertEquals(record.inclLen, record.packet.size)
    }

    @Test
    fun `the radiotap prefix has version 0 pad 0 length 8 and no present flags`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })

        writer.append(frameA, timestampMicros = 0L)

        val packet = records(sink.captured()).single().packet
        assertContentEquals(radiotap.hexToByteArray(), packet.copyOfRange(0, 8))
        assertEquals(0, packet[0].toInt(), "radiotap version")
        assertEquals(0, packet[1].toInt(), "radiotap pad")
        assertEquals(8, packet.u16le(2), "radiotap length is the whole header, little-endian")
        assertEquals(0L, packet.u32le(4), "radiotap present bitmap")
        // The 802.11 frame starts right after the radiotap header, at the offset the length field announces.
        assertContentEquals(frameA, packet.copyOfRange(packet.u16le(2), packet.size))
    }

    @Test
    fun `an empty frame is a record holding only the radiotap header`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })

        writer.append(ByteArray(0), timestampMicros = 0L)

        val record = records(sink.captured()).single()
        assertEquals(8, record.inclLen)
        assertEquals(8, record.origLen)
        assertEquals(radiotap, record.packet.toHexString())
    }

    // ---- Several frames ---------------------------------------------------------------------

    @Test
    fun `several frames are appended in order each with its own header`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })

        writer.append(frameA, timestampMicros = 1_000_001L)
        writer.append(frameB, timestampMicros = 2_999_999L)
        writer.append(frameA, timestampMicros = 3_000_000L)

        val decoded = records(sink.captured())
        assertEquals(3, decoded.size)
        assertEquals(listOf(1L, 2L, 3L), decoded.map { it.tsSec })
        assertEquals(listOf(1L, 999_999L, 0L), decoded.map { it.tsUsec })
        assertEquals(listOf(18, 13, 18), decoded.map { it.inclLen })
        assertContentEquals(frameA, decoded[0].packet.copyOfRange(8, decoded[0].packet.size))
        assertContentEquals(frameB, decoded[1].packet.copyOfRange(8, decoded[1].packet.size))
        assertContentEquals(frameA, decoded[2].packet.copyOfRange(8, decoded[2].packet.size))
        assertEquals(3, writer.framesWritten)
        assertEquals(sink.captured().size.toLong(), writer.bytesWritten)
    }

    @Test
    fun `the injected clock stamps every frame appended without an explicit timestamp`() {
        val sink = MemorySink()
        var now = 10_000_000L // 10 s
        val writer = PcapWriter(sink, clock = { now.also { now += 250_000L } })

        writer.append(frameA)
        writer.append(frameB)
        writer.append(frameA, timestampMicros = 99_000_000L) // explicit wins and does not consume the clock
        writer.append(frameB)

        val decoded = records(sink.captured())
        assertEquals(listOf(10L, 10L, 99L, 10L), decoded.map { it.tsSec })
        assertEquals(listOf(0L, 250_000L, 0L, 500_000L), decoded.map { it.tsUsec })
    }

    @Test
    fun `the default clock produces a plausible current timestamp`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink)

        writer.append(frameA)

        val seconds = records(sink.captured()).single().tsSec
        // After 2020-01-01 and well before the u32 rollover in 2106.
        assertTrue(seconds in 1_577_836_800L..4_000_000_000L, "ts_sec=$seconds")
    }

    // ---- Snap length ------------------------------------------------------------------------

    @Test
    fun `a frame longer than the snap length is cut but orig_len keeps the full size`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, snapLength = 12, clock = { 0L })
        val frame = "00112233445566778899".hexToByteArray() // 10 octets

        writer.append(frame, timestampMicros = 0L)

        val record = records(sink.captured()).single()
        assertEquals(12, record.inclLen, "stored octets are capped at the snap length")
        assertEquals(18, record.origLen, "orig_len is radiotap 8 + frame 10")
        assertEquals(radiotap + "00112233", record.packet.toHexString())
        assertEquals(12L, sink.captured().u32le(16), "the global header advertises the snap length")
    }

    @Test
    fun `a snap length equal to the radiotap header keeps only the radiotap prefix`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, snapLength = 8, clock = { 0L })

        writer.append(frameA, timestampMicros = 0L)

        val record = records(sink.captured()).single()
        assertEquals(8, record.inclLen)
        assertEquals(18, record.origLen)
        assertEquals(radiotap, record.packet.toHexString())
    }

    // ---- Streaming contract and validation --------------------------------------------------

    @Test
    fun `every unit written is flushed so a killed process leaves a usable capture`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })
        assertEquals(1, sink.writeCount, "the global header is one write")
        assertEquals(1, sink.flushCount)

        writer.append(frameA, timestampMicros = 0L)
        writer.append(frameB, timestampMicros = 0L)

        assertEquals(3, sink.writeCount, "each record is exactly one write")
        assertEquals(3, sink.flushCount)
    }

    @Test
    fun `a record keeps the frame as it was when appended`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })
        val frame = frameA.copyOf()

        writer.append(frame, timestampMicros = 0L)
        frame.fill(0x55)

        assertContentEquals(frameA, records(sink.captured()).single().packet.copyOfRange(8, 18))
    }

    @Test
    fun `a negative timestamp and a snap length below the radiotap header are refused`() {
        val sink = MemorySink()
        val writer = PcapWriter(sink, clock = { 0L })

        assertFailsWith<IllegalArgumentException> { writer.append(frameA, timestampMicros = -1L) }
        assertFailsWith<IllegalArgumentException> { PcapWriter(MemorySink(), snapLength = 7) }
        assertEquals(0, writer.framesWritten, "a refused append writes nothing")
        assertEquals(24, sink.captured().size)
    }
}
