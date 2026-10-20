package com.mklhc.app.spike

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Test support for the spike's tests: the pcap reader. The station the tests play (the kart) is FakeStation from
 * :core:wifi-ap, shared with that module's own tests.
 */

/** One decoded pcap record: its timestamp and the 802.11 frame (the radiotap prefix is stripped). */
internal class PcapFrame(val tsSec: Long, val tsUsec: Long, val frame: ByteArray)

/** A decoded pcap stream: the link type of its global header and its records. */
internal class PcapCapture(val linkType: Int, val frames: List<PcapFrame>)

/**
 * Decodes a classic little-endian pcap stream, asserting the framing on the way: the magic number, the version, one
 * radiotap prefix per record, `orig_len == incl_len` (nothing is truncated at the default snap length) and records
 * that tile the stream exactly.
 */
internal fun parsePcap(bytes: ByteArray): PcapCapture {
    fun u32(offset: Int): Long = (0 until 4).sumOf { index -> (bytes[offset + index].toLong() and 0xFF) shl (index * 8) }

    assertTrue(bytes.size >= 24, "the global header is always written")
    assertEquals(0xA1B2C3D4L, u32(0), "magic")
    assertEquals(0x00040002L, u32(4), "version 2.4")
    val linkType = u32(20).toInt()
    val frames = ArrayList<PcapFrame>()
    var offset = 24
    while (offset < bytes.size) {
        val includedLength = u32(offset + 8).toInt()
        assertEquals(includedLength.toLong(), u32(offset + 12), "orig_len equals incl_len when nothing is truncated")
        val packet = bytes.copyOfRange(offset + 16, offset + 16 + includedLength)
        assertEquals("0000080000000000", packet.copyOfRange(0, 8).toHexString(), "radiotap prefix")
        frames += PcapFrame(u32(offset), u32(offset + 4), packet.copyOfRange(8, packet.size))
        offset += 16 + includedLength
    }
    assertEquals(bytes.size, offset, "the records must tile the stream exactly")
    return PcapCapture(linkType, frames)
}
