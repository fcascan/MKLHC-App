package com.mklhc.core.kart_protocol.fuji

import com.mklhc.core.kart_protocol.rcd.RcdError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [FujiPairingClient]: `setGroupInfo` is driven over a fake RCD transport and its request
 * (0x20-padded SSID followed by the 0x20-octet PSK) is compared with the golden vectors generated from
 * OpenKart's `FujiPairingClient.set_group_info` (openkartd/fuji.py).
 */
class FujiPairingClientTest {

    private val vectors = FujiVectors.load()

    @Test
    fun `service id matches the reference`() {
        assertEquals(vectors.pairingServiceId, FujiPairingClient.SERVICE_ID)
    }

    @Test
    fun `setGroupInfo sends the padded ssid followed by the psk`() = runTest {
        for (v in vectors.setGroupInfo) {
            val fixture = FujiFixture()

            FujiPairingClient(fixture.rcd).setGroupInfo(v.ssid, v.psk)

            assertRequest(v.call, fixture.sentRequest(), "setGroupInfo ${v.name}")
        }
    }

    @Test
    fun `setGroupInfo rejects a psk that is not 0x20 octets`() = runTest {
        val client = FujiPairingClient(FujiFixture().rcd)

        assertFailsWith<IllegalArgumentException> { client.setGroupInfo("net", ByteArray(0x1F)) }
        assertFailsWith<IllegalArgumentException> { client.setGroupInfo("net", ByteArray(0x21)) }
    }

    @Test
    fun `setGroupInfo rejects an ssid of 0x20 octets or more`() = runTest {
        val client = FujiPairingClient(FujiFixture().rcd)

        assertFailsWith<IllegalArgumentException> { client.setGroupInfo("s".repeat(0x20), ByteArray(0x20)) }
    }

    @Test
    fun `a non-zero status surfaces as RcdError`() = runTest {
        val client = FujiPairingClient(FujiFixture(status = STATUS_BAD_REQUEST).rcd)

        val error = assertFailsWith<RcdError> { client.setGroupInfo("net", ByteArray(0x20)) }

        assertEquals(STATUS_BAD_REQUEST, error.code)
    }
}
