package com.mklhc.core.kart_protocol.rcd

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [RcdHandshakeService] state machine.
 *
 * The full transcript (requests, responses and the running-SHA-256 finalize digest) comes from
 * `tools/gen_rcd_vectors.py` driving OpenKart's real `RcdHandshakeService` (openkartd/rcd.py) with
 * the server challenge pinned, so the ported state machine is asserted byte for byte. The sequencing
 * and version-negotiation cases are direct unit tests.
 */
class RcdHandshakeServiceTest {

    private val vectors = RcdVectors.load()
    private val handshake = vectors.handshake
    private val pairing = vectors.pairingHandshake

    /** Records the connect/disconnect callbacks so a pairing test can assert they fire. */
    private class RecordingHandler : RcdDeviceHandler {
        val connected = mutableListOf<RcdDevice>()
        val disconnected = mutableListOf<RcdDevice>()
        override fun deviceConnected(device: RcdDevice) { connected += device }
        override fun deviceDisconnected(device: RcdDevice) { disconnected += device }
    }

    private fun buildService(pairing: Boolean = false): RcdHandshakeService {
        val info = RcdServerInfo(
            name = handshake.serverName,
            ident = handshake.serverIdent,
            masterKey = handshake.masterKey,
            versions = handshake.versions,
        )
        return RcdHandshakeService(
            serverInfo = info,
            peerAddress = "10.0.0.2",
            pairing = pairing,
            randomChallenge = { n ->
                assertEquals(0x20, n, "begin challenge length")
                handshake.beginChallenge
            },
        )
    }

    private fun buildPairingService(handler: RcdDeviceHandler): RcdHandshakeService {
        val info = RcdServerInfo(
            name = pairing.serverName,
            ident = pairing.serverIdent,
            masterKey = pairing.masterKey,
            versions = pairing.versions,
        )
        return RcdHandshakeService(
            serverInfo = info,
            peerAddress = "10.0.0.3",
            handler = handler,
            pairing = true,
            randomChallenge = { n ->
                assertEquals(0x20, n, "begin challenge length")
                pairing.beginChallenge
            },
        )
    }

    private suspend fun RcdHandshakeService.run(command: Int, data: ByteArray): ByteArray =
        dispatch(RcdMsg(RcdHandshakeService.SERVICE_ID, command, 0, false, data))

    private fun step(command: Int): RcdVectors.HandshakeStep = handshake.steps.first { it.command == command }

    private fun pairingStep(command: Int): RcdVectors.HandshakeStep = pairing.steps.first { it.command == command }

    @Test
    fun `full handshake reproduces the OpenKart transcript byte-for-byte`() = runTest {
        val service = buildService()

        val begin = step(1)
        val resp1 = service.run(1, begin.request)
        assertEquals(begin.response.toHexString(), resp1.toHexString(), "begin response")

        val device = assertNotNull(service.device, "begin produces a device")
        assertEquals(handshake.deviceName, device.name)
        assertEquals(handshake.deviceIdent.toHexString(), device.ident.toHexString())
        assertEquals(handshake.pairingId.toHexString(), device.pairingId.toHexString())
        assertEquals(handshake.secretKey.toHexString(), device.secretKey.toHexString())
        assertEquals("10.0.0.2", device.address)

        val negotiate = step(2)
        val resp2 = service.run(2, negotiate.request)
        assertEquals(negotiate.response.toHexString(), resp2.toHexString(), "negotiate response")
        assertEquals(handshake.selectedVersion, service.device?.version, "negotiated version")

        val finalize = step(4)
        val resp4 = service.run(4, finalize.request)
        assertEquals(finalize.finalizeDigest!!.toHexString(), resp4.toHexString(), "finalize digest")
    }

    @Test
    fun `finalize accepts the golden running-hash digest`() = runTest {
        val service = buildService()
        service.run(1, step(1).request)
        service.run(2, step(2).request)
        // The golden finalize_check_input IS the running-hash digest the server expects; sending it
        // and getting the golden digest back (no HandshakeHash) proves the running hash matches.
        val resp = service.run(4, step(4).finalizeCheckInput!!)
        assertEquals(step(4).finalizeDigest!!.toHexString(), resp.toHexString())
    }

    @Test
    fun `a command out of order is rejected with HandshakeSequence`() = runTest {
        val service = buildService()
        // A correctly-sized negotiate payload sent first: size passes, so the sequence check fires.
        assertFailsWith<RcdErrorHandshakeSequence> {
            service.run(2, handshake.pairingId + byteArrayOf(1) + byteArrayOf(1))
        }
    }

    @Test
    fun `negotiate picks the server's most preferred offered version`() = runTest {
        val service = buildService()
        service.run(1, step(1).request)
        // Offer [1, 2]; the server prefers 2 (first of its (2, 1)).
        val resp = service.run(2, handshake.pairingId + byteArrayOf(2) + byteArrayOf(1, 2))
        assertEquals(2, resp[0x20].toInt(), "selected version octet")
        assertEquals(2, service.device?.version)
    }

    @Test
    fun `negotiate falls back to a lesser version when it is the only one offered`() = runTest {
        val service = buildService()
        service.run(1, step(1).request)
        val resp = service.run(2, handshake.pairingId + byteArrayOf(1) + byteArrayOf(1))
        assertEquals(1, resp[0x20].toInt(), "selected version octet")
        assertEquals(1, service.device?.version)
    }

    @Test
    fun `negotiate rejects versions the server does not support`() = runTest {
        val service = buildService()
        service.run(1, step(1).request)
        assertFailsWith<RcdErrorHandshakeBadVersions> {
            service.run(2, handshake.pairingId + byteArrayOf(1) + byteArrayOf(3))
        }
    }

    @Test
    fun `negotiate rejects an unrecognized pairing id when pairing is off`() = runTest {
        val service = buildService(pairing = false)
        service.run(1, step(1).request)
        val wrongPairingId = ByteArray(0x20) { 0x00 }
        assertFailsWith<RcdErrorHandshakeUnrecognizedDevice> {
            service.run(2, wrongPairingId + byteArrayOf(1) + byteArrayOf(1))
        }
    }

    @Test
    fun `finalize rejects a wrong running-hash digest with HandshakeHash`() = runTest {
        val service = buildService()
        service.run(1, step(1).request)
        service.run(2, step(2).request)
        assertFailsWith<RcdErrorHandshakeHash> {
            service.run(4, ByteArray(0x20) { 0x00 })
        }
    }

    @Test
    fun `begin rejects a payload of the wrong size`() = runTest {
        val service = buildService()
        assertFailsWith<RcdErrorMissizedPayload> { service.run(1, ByteArray(0x10)) }
    }

    @Test
    fun `begin rejects an unsupported handshake version`() = runTest {
        val service = buildService()
        val bad = step(1).request.copyOf()
        bad[0] = 2 // handshake_version must be 1
        assertFailsWith<RcdErrorHandshakeVersionMismatch> { service.run(1, bad) }
    }

    @Test
    fun `an unknown command is rejected with BadRequest`() = runTest {
        val service = buildService()
        assertFailsWith<RcdErrorBadRequest> { service.run(9, ByteArray(0)) }
    }

    @Test
    fun `pairing handshake completes through get-secret-key and reproduces the transcript`() = runTest {
        val handler = RecordingHandler()
        val service = buildPairingService(handler)

        val begin = pairingStep(1)
        val resp1 = service.run(1, begin.request)
        assertEquals(begin.response.toHexString(), resp1.toHexString(), "begin response")

        // An unknown device offers a pairing id the server does not recognize; with pairing on, this
        // routes to get-secret-key (cmd 3) rather than straight to finalize.
        val negotiate = pairingStep(2)
        val resp2 = service.run(2, negotiate.request)
        assertEquals(negotiate.response.toHexString(), resp2.toHexString(), "negotiate response")
        assertEquals(pairing.selectedVersion, service.device?.version, "negotiated version")

        val getSecret = pairingStep(3)
        val resp3 = service.run(3, getSecret.request)
        assertEquals(getSecret.response.toHexString(), resp3.toHexString(), "get-secret-key returns the secret key")

        // The returned key is exactly the one the server derives for this device.
        val expectedSecret = RcdServerInfo(
            name = pairing.serverName,
            ident = pairing.serverIdent,
            masterKey = pairing.masterKey,
            versions = pairing.versions,
        ).getPairingKeys(pairing.deviceIdent, pairing.deviceName).second
        assertEquals(expectedSecret.toHexString(), resp3.toHexString(), "get-secret-key equals getPairingKeys().second")

        assertTrue(handler.connected.isEmpty(), "the device is not connected until finalize succeeds")

        val finalize = pairingStep(4)
        val resp4 = service.run(4, finalize.request)
        assertEquals(finalize.finalizeDigest!!.toHexString(), resp4.toHexString(), "finalize digest")

        val connected = assertNotNull(handler.connected.singleOrNull(), "deviceConnected fires once on completion")
        assertEquals(pairing.deviceName, connected.name)
        assertEquals(pairing.selectedVersion, connected.version)
        assertEquals(expectedSecret.toHexString(), connected.secretKey.toHexString())

        service.close()
        assertEquals(connected, handler.disconnected.singleOrNull(), "deviceDisconnected fires on close")
    }
}
