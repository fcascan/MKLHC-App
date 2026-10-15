package com.mklhc.core.kart_protocol.rcd

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [FramedRcdTransport]: it must reassemble a message from a byte stream regardless of how
 * the reads chunk it, reject an over-large declared payload rather than buffering it, and report end
 * of stream. This is the framing OpenKart's RcdProtocol does on its TCP socket (openkartd/rcd.py).
 */
class FramedRcdTransportTest {

    private fun ByteArray.intoChunks(size: Int): List<ByteArray> =
        toList().chunked(size).map { it.toByteArray() }

    @Test
    fun `receive reassembles a message split across many small reads`() = runTest {
        val message = RcdMsg(service = 0x0001, command = 3, status = 0, isResponse = true, data = ByteArray(40) { it.toByte() })
        val stream = FakeByteStream(message.encode().intoChunks(5))
        val transport = FramedRcdTransport(stream)

        assertEquals(message, transport.receive())
    }

    @Test
    fun `receive reads two consecutive messages`() = runTest {
        val first = RcdMsg(1, 1, 0, false, byteArrayOf(1, 2, 3))
        val second = RcdMsg(1, 2, 0, true, byteArrayOf(4, 5))
        val stream = FakeByteStream(listOf(first.encode() + second.encode()))
        val transport = FramedRcdTransport(stream)

        assertEquals(first, transport.receive())
        assertEquals(second, transport.receive())
    }

    @Test
    fun `send writes the exact encoded bytes`() = runTest {
        val message = RcdMsg(0x1234, 7, 0, false, byteArrayOf(0xA.toByte(), 0xB.toByte()))
        val stream = FakeByteStream(emptyList())
        val transport = FramedRcdTransport(stream)

        transport.send(message)

        val written = stream.written.fold(ByteArray(0)) { acc, b -> acc + b }
        assertEquals(message.encode().toHexString(), written.toHexString())
    }

    @Test
    fun `receive rejects a payload larger than the max`() = runTest {
        // A header that declares an oversize payload; receive must reject before reading the body.
        val oversizeHeader = RcdMsg(1, 1, 0, false, ByteArray(RcdMsg.MAX_DATA_SIZE + 1))
            .encode()
            .copyOfRange(0, RcdMsg.HEADER_SIZE)
        val stream = FakeByteStream(listOf(oversizeHeader))
        val transport = FramedRcdTransport(stream)

        assertFailsWith<RcdProtocolException> { transport.receive() }
    }

    @Test
    fun `receive rejects a header declaring a high-bit payload size`() = runTest {
        // A declared data size with the high bit set (0x80000000) is read as a NEGATIVE Int by the
        // signed readU32; it must be rejected as a protocol error, never reach ByteArray(negative).
        val header = ByteArray(RcdMsg.HEADER_SIZE).also { it[4] = 0x80.toByte() }
        val transport = FramedRcdTransport(FakeByteStream(listOf(header)))

        assertFailsWith<RcdProtocolException> { transport.receive() }
    }

    @Test
    fun `receive rejects a header declaring 0xFFFFFFFF payload size`() = runTest {
        val header = ByteArray(RcdMsg.HEADER_SIZE) { if (it in 4..7) 0xFF.toByte() else 0 }
        val transport = FramedRcdTransport(FakeByteStream(listOf(header)))

        assertFailsWith<RcdProtocolException> { transport.receive() }
    }

    @Test
    fun `send rejects a payload larger than the max`() = runTest {
        val transport = FramedRcdTransport(FakeByteStream(emptyList()))
        assertFailsWith<IllegalArgumentException> {
            transport.send(RcdMsg(1, 1, 0, false, ByteArray(RcdMsg.MAX_DATA_SIZE + 1)))
        }
    }

    @Test
    fun `receive reports end of stream`() = runTest {
        val transport = FramedRcdTransport(FakeByteStream(emptyList()))
        assertFailsWith<RcdClosedException> { transport.receive() }
    }
}
