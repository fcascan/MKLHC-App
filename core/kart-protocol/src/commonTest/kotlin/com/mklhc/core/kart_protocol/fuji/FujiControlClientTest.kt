package com.mklhc.core.kart_protocol.fuji

import com.mklhc.core.kart_protocol.rcd.RcdError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [FujiControlClient]: every command is driven over a fake RCD transport that captures the
 * outgoing request, and the request bytes are compared with the golden vectors generated from
 * OpenKart's `FujiControlClient` (openkartd/fuji.py). ReadApplicationData (command 0x12) is compared
 * with the layout replicated from mk-live's patch.
 */
class FujiControlClientTest {

    private val vectors = FujiVectors.load()

    @Test
    fun `service id and ports match the reference`() {
        assertEquals(vectors.controlServiceId, FujiControlClient.SERVICE_ID)
        assertEquals(vectors.controlPort, FujiPorts.CONTROL)
        assertEquals(vectors.drivePort, FujiPorts.DRIVE)
        assertEquals(vectors.pairingPort, FujiPorts.PAIRING)
        assertEquals(vectors.eventPort, FujiPorts.EVENT)
    }

    @Test
    fun `getSystemInfo sends command 1 and decodes the response`() = runTest {
        val v = vectors.getSystemInfo
        val fixture = FujiFixture(response = v.response)

        val info = FujiControlClient(fixture.rcd).getSystemInfo()

        assertRequest(v.call, fixture.sentRequest(), "getSystemInfo")
        assertEquals(v.bootVersion, info.bootVersion)
        assertEquals(v.systemVersion, info.systemVersion)
        assertEquals(v.sha1, info.sha1)
    }

    @Test
    fun `setParam builds the padded name, padded big-endian length and value`() = runTest {
        for (v in vectors.setParam) {
            val fixture = FujiFixture()

            FujiControlClient(fixture.rcd).setParam(v.param, v.value)

            assertRequest(v.call, fixture.sentRequest(), "setParam ${v.name}")
        }
    }

    @Test
    fun `setParam rejects a name that does not fit in 0x80 octets`() = runTest {
        val client = FujiControlClient(FujiFixture().rcd)

        assertFailsWith<IllegalArgumentException> { client.setParam("n".repeat(0x80), ByteArray(1)) }
    }

    @Test
    fun `setParam rejects a value larger than a 16-bit length`() = runTest {
        val client = FujiControlClient(FujiFixture().rcd)

        assertFailsWith<IllegalArgumentException> { client.setParam("p", ByteArray(0x10000)) }
    }

    @Test
    fun `setConnectionInfo packs the little-endian connection_info value`() = runTest {
        for (v in vectors.connectionInfo) {
            val fixture = FujiFixture()

            FujiControlClient(fixture.rcd).setConnectionInfo(
                telemetryPort = v.telemetryPort,
                videoControlPort = v.videoControlPort,
                videoStreamPort = v.videoStreamPort,
                timestamp = v.timestamp,
                unk = v.unk,
            )

            assertRequest(v.call, fixture.sentRequest(), "setConnectionInfo ${v.name}")
        }
    }

    @Test
    fun `setConnectionInfo defaults unk to 1`() = runTest {
        val v = vectors.connectionInfo.first { it.unk == 1 }
        val fixture = FujiFixture()

        FujiControlClient(fixture.rcd).setConnectionInfo(
            telemetryPort = v.telemetryPort,
            videoControlPort = v.videoControlPort,
            videoStreamPort = v.videoStreamPort,
            timestamp = v.timestamp,
        )

        assertRequest(v.call, fixture.sentRequest(), "setConnectionInfo with default unk")
    }

    @Test
    fun `setConnectionInfo rejects a port outside 16 bits`() = runTest {
        val client = FujiControlClient(FujiFixture().rcd)

        assertFailsWith<IllegalArgumentException> {
            client.setConnectionInfo(telemetryPort = 0x10000, videoControlPort = 1, videoStreamPort = 2, timestamp = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            client.setConnectionInfo(telemetryPort = 1, videoControlPort = -1, videoStreamPort = 2, timestamp = 0)
        }
    }

    @Test
    fun `getParam sends the padded name and returns the length-prefixed slice`() = runTest {
        for (v in vectors.getParam) {
            val fixture = FujiFixture(response = v.response)

            val result = FujiControlClient(fixture.rcd).getParam(v.param)

            assertRequest(v.call, fixture.sentRequest(), "getParam ${v.name}")
            assertBytes(v.result, result, "getParam result for ${v.name}")
        }
    }

    @Test
    fun `getParam tolerates a response shorter than its header`() = runTest {
        val fixture = FujiFixture(response = byteArrayOf(0x00))

        val result = FujiControlClient(fixture.rcd).getParam("p")

        assertEquals(0, result.size)
    }

    @Test
    fun `getProductCode requests product_code and decodes the value`() = runTest {
        val v = vectors.productCodeFlow
        val fixture = FujiFixture(response = v.response)

        val code = FujiControlClient(fixture.rcd).getProductCode()

        assertRequest(v.call, fixture.sentRequest(), "getProductCode")
        assertEquals(v.code.unk1, code.unk1)
        assertEquals(v.code.character, code.character)
        assertEquals(v.code.unk2, code.unk2)
        assertEquals(v.code.serial, code.serial)
    }

    @Test
    fun `setState sends the state octet padded to 0x10`() = runTest {
        for (v in vectors.setState) {
            val fixture = FujiFixture()

            FujiControlClient(fixture.rcd).setState(v.state)

            assertRequest(v.call, fixture.sentRequest(), "setState(${v.state})")
        }
    }

    @Test
    fun `setState rejects a value that does not fit one octet`() = runTest {
        val client = FujiControlClient(FujiFixture().rcd)

        assertFailsWith<IllegalArgumentException> { client.setState(256) }
        assertFailsWith<IllegalArgumentException> { client.setState(-1) }
    }

    @Test
    fun `state constants are drive 1 and sleep 0`() {
        assertEquals(1, FujiControlClient.STATE_DRIVE)
        assertEquals(0, FujiControlClient.STATE_SLEEP)
    }

    @Test
    fun `shutdown sends command 9 with no payload`() = runTest {
        val fixture = FujiFixture()

        FujiControlClient(fixture.rcd).shutdown()

        assertRequest(vectors.shutdown, fixture.sentRequest(), "shutdown")
    }

    @Test
    fun `readApplicationData sends the padded id and big-endian max size`() = runTest {
        for (v in vectors.readApplicationData) {
            val fixture = FujiFixture()

            FujiControlClient(fixture.rcd).readApplicationData(v.applicationId, v.maxSize)

            assertRequest(v.call, fixture.sentRequest(), "readApplicationData ${v.name}")
        }
    }

    @Test
    fun `readApplicationData defaults max size to 0x800 and returns the payload`() = runTest {
        val v = vectors.readApplicationData.first { it.maxSize == 0x800 }
        val payload = byteArrayOf(9, 8, 7, 6)
        val fixture = FujiFixture(response = payload)

        val result = FujiControlClient(fixture.rcd).readApplicationData(v.applicationId)

        assertRequest(v.call, fixture.sentRequest(), "readApplicationData default size")
        assertBytes(payload, result, "readApplicationData returns the response payload")
    }

    @Test
    fun `readApplicationData rejects an id longer than 0x10 octets`() = runTest {
        val client = FujiControlClient(FujiFixture().rcd)

        assertFailsWith<IllegalArgumentException> { client.readApplicationData("A".repeat(0x11)) }
    }

    @Test
    fun `the application id constant matches the vectors`() {
        assertEquals(vectors.applicationId, FujiControlClient.APPLICATION_ID)
    }

    @Test
    fun `a non-zero status surfaces as RcdError from every command`() = runTest {
        val commands: List<Pair<String, suspend (FujiControlClient) -> Unit>> = listOf(
            "getSystemInfo" to { c -> c.getSystemInfo() },
            "setParam" to { c -> c.setParam("p", byteArrayOf(1)) },
            "setConnectionInfo" to { c -> c.setConnectionInfo(1, 2, 3, timestamp = 4) },
            "getParam" to { c -> c.getParam("p") },
            "getProductCode" to { c -> c.getProductCode() },
            "setState" to { c -> c.setState(1) },
            "shutdown" to { c -> c.shutdown() },
            "readApplicationData" to { c -> c.readApplicationData(FujiControlClient.APPLICATION_ID) },
        )

        for ((name, command) in commands) {
            val client = FujiControlClient(FujiFixture(status = STATUS_BAD_REQUEST).rcd)

            val error = assertFailsWith<RcdError>("$name must raise RcdError") { command(client) }

            assertEquals(STATUS_BAD_REQUEST, error.code, "status for $name")
        }
    }
}
