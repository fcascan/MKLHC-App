package com.mklhc.core.kart_protocol.rcd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden tests for [RcdMsg] framing.
 *
 * The expected bytes come from `tools/gen_rcd_vectors.py`, which calls OpenKart's `RcdMsg.encode`,
 * `RcdMsg.decode` and `RcdMsg.size` (openkartd/rcd.py) directly, so any drift from the big-endian
 * `>HHIIBxxx` header layout fails here.
 */
class RcdMsgTest {

    private val vectors = RcdVectors.load()

    @Test
    fun `encode matches the OpenKart golden bytes`() {
        assertTrue(vectors.msgEncode.isNotEmpty(), "the generator emits encode vectors")
        for (v in vectors.msgEncode) {
            val msg = RcdMsg(v.service, v.command, v.status, v.isResponse, v.data)
            assertEquals(v.encoded.toHexString(), msg.encode().toHexString(), "encode for ${v.name}")
        }
    }

    @Test
    fun `decode recovers every field from the golden bytes`() {
        for (v in vectors.msgEncode) {
            val msg = RcdMsg.decode(v.encoded)
            assertEquals(v.service, msg.service, "service for ${v.name}")
            assertEquals(v.command, msg.command, "command for ${v.name}")
            assertEquals(v.status, msg.status, "status for ${v.name}")
            assertEquals(v.isResponse, msg.isResponse, "is_response for ${v.name}")
            assertEquals(v.data.toHexString(), msg.data.toHexString(), "data for ${v.name}")
        }
    }

    @Test
    fun `decode reads is_response from flag bit 0 and ignores spare bits`() {
        for (v in vectors.msgDecode) {
            val msg = RcdMsg.decode(v.buffer)
            assertEquals(v.service, msg.service, "service for ${v.name}")
            assertEquals(v.command, msg.command, "command for ${v.name}")
            assertEquals(v.status, msg.status, "status for ${v.name}")
            assertEquals(v.isResponse, msg.isResponse, "is_response for ${v.name}")
            assertEquals(v.data.toHexString(), msg.data.toHexString(), "data for ${v.name}")
        }
    }

    @Test
    fun `size matches OpenKart including a buffer shorter than the header`() {
        for (v in vectors.msgSize) {
            assertEquals(v.size, RcdMsg.size(v.buffer), "size for ${v.name}")
        }
    }

    @Test
    fun `encode then decode round-trips`() {
        val original = RcdMsg(service = 0x0042, command = 9, status = 0x12345, isResponse = true, data = ByteArray(200) { it.toByte() })
        val decoded = RcdMsg.decode(original.encode())
        assertEquals(original, decoded)
    }

    @Test
    fun `decode rejects a buffer shorter than the message it declares`() {
        val complete = RcdMsg(1, 3, 0, true, ByteArray(16) { it.toByte() }).encode()
        val truncated = complete.copyOfRange(0, complete.size - 1)
        assertFailsWith<IllegalArgumentException> { RcdMsg.decode(truncated) }
    }

    @Test
    fun `size rejects a declared length with the high bit set`() {
        // Defense in depth: a declared data size whose high bit is set reads as a negative Int; size()
        // must flag it as malformed rather than return a nonsensical (overflowing) total length.
        val highBit = ByteArray(RcdMsg.HEADER_SIZE).also { it[4] = 0x80.toByte() }
        val allOnes = ByteArray(RcdMsg.HEADER_SIZE) { if (it in 4..7) 0xFF.toByte() else 0 }

        assertFailsWith<RcdProtocolException> { RcdMsg.size(highBit) }
        assertFailsWith<RcdProtocolException> { RcdMsg.size(allOnes) }
    }

    @Test
    fun `decode rejects a declared length with the high bit set`() {
        // The negative declared length must not slip into copyOfRange / ByteArray(negative).
        val highBit = ByteArray(RcdMsg.HEADER_SIZE).also { it[4] = 0x80.toByte() }
        val allOnes = ByteArray(RcdMsg.HEADER_SIZE) { if (it in 4..7) 0xFF.toByte() else 0 }

        assertFailsWith<RcdProtocolException> { RcdMsg.decode(highBit) }
        assertFailsWith<RcdProtocolException> { RcdMsg.decode(allOnes) }
    }
}
