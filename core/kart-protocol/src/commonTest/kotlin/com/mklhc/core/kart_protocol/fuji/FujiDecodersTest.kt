package com.mklhc.core.kart_protocol.fuji

import com.mklhc.core.kart_protocol.rcd.RcdProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [padTo], [FujiSystemInfo.decode] and [FujiProductCode.decode] against the golden vectors
 * generated from OpenKart's `util.pad_to`, `FujiSystemInfo.decode` and `FujiProductCode.decode`
 * (openkartd/util.py, openkartd/fuji.py).
 */
class FujiDecodersTest {

    private val vectors = FujiVectors.load()

    @Test
    fun `padTo zero fills to the requested length`() {
        for (v in vectors.padding) {
            assertBytes(v.padded, padTo(v.input, v.length), "padTo for ${v.name}")
        }
    }

    @Test
    fun `padTo returns an equal array when already full length`() {
        val full = ByteArray(8) { it.toByte() }

        assertBytes(full, padTo(full, 8), "full-length input is unchanged")
    }

    @Test
    fun `padTo rejects an input longer than the target`() {
        assertFailsWith<IllegalArgumentException> { padTo(ByteArray(5), 4) }
    }

    @Test
    fun `FujiSystemInfo decode matches the reference for every vector`() {
        for (v in vectors.systemInfo) {
            val info = FujiSystemInfo.decode(v.input)

            assertEquals(v.bootVersion, info.bootVersion, "boot version for ${v.name}")
            assertEquals(v.systemVersion, info.systemVersion, "system version for ${v.name}")
            assertEquals(v.sha1, info.sha1, "sha1 for ${v.name}")
        }
    }

    @Test
    fun `FujiSystemInfo decode treats version octets as unsigned`() {
        val info = FujiSystemInfo.decode(byteArrayOf(0, 0xFF.toByte(), 0x80.toByte(), 7))

        assertEquals(0 to 255, info.bootVersion)
        assertEquals(128 to 7, info.systemVersion)
    }

    @Test
    fun `FujiSystemInfo decode rejects a payload shorter than the version header`() {
        assertFailsWith<RcdProtocolException> { FujiSystemInfo.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `FujiProductCode decode matches the reference for every vector`() {
        for (v in vectors.productCode) {
            val code = FujiProductCode.decode(v.input)

            assertEquals(v.unk1, code.unk1, "unk1 for ${v.name}")
            assertEquals(v.character, code.character, "character for ${v.name}")
            assertEquals(v.unk2, code.unk2, "unk2 for ${v.name}")
            assertEquals(v.serial, code.serial, "serial for ${v.name}")
        }
    }

    @Test
    fun `FujiProductCode decode reads the header little-endian`() {
        // 02 01 -> 0x0102, 04 03 -> 0x0304: a big-endian read would swap them.
        val code = FujiProductCode.decode(byteArrayOf(2, 1, 4, 3, 9, 'X'.code.toByte()))

        assertEquals(0x0102, code.unk1)
        assertEquals(0x0304, code.character)
        assertEquals(9, code.unk2)
        assertEquals("X", code.serial)
    }

    @Test
    fun `FujiProductCode decode rejects a payload shorter than its header`() {
        assertFailsWith<RcdProtocolException> { FujiProductCode.decode(byteArrayOf(1, 0, 1, 0)) }
    }
}
