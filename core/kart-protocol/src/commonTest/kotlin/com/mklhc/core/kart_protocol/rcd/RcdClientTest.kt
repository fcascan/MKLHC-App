package com.mklhc.core.kart_protocol.rcd

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [RcdClient.invoke] over a fake transport: a well-formed response, a non-zero status, a
 * response that does not match the request, and a timeout. Ported behaviour of OpenKart's
 * `RcdClient.invoke`/`exchange` (openkartd/rcd.py).
 */
class RcdClientTest {

    @Test
    fun `invoke sends a request and returns the response payload`() = runTest {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val transport = FakeClientTransport { req ->
            RcdMsg(req.service, req.command, status = 0, isResponse = true, data = payload)
        }
        val client = RcdClient(transport)

        val result = client.invoke(service = 0x0001, command = 7, data = byteArrayOf(1, 2))

        assertEquals(payload.toHexString(), result.toHexString())
        assertEquals(1, transport.sent.size)
        val sent = transport.sent.single()
        assertEquals(0x0001, sent.service)
        assertEquals(7, sent.command)
        assertEquals(false, sent.isResponse, "a request has the response flag clear")
        assertEquals("0102", sent.data.toHexString())
    }

    @Test
    fun `invoke raises RcdError carrying a non-zero status`() = runTest {
        val transport = FakeClientTransport { req ->
            RcdMsg(req.service, req.command, status = 0x710e8, isResponse = true, data = ByteArray(0))
        }
        val client = RcdClient(transport)

        val error = assertFailsWith<RcdError> { client.invoke(service = 0x0001, command = 1) }
        assertEquals(0x710e8, error.code)
    }

    @Test
    fun `invoke rejects a response that does not match the request`() = runTest {
        val transport = FakeClientTransport { req ->
            // Wrong command: the stream has desynced.
            RcdMsg(req.service, req.command + 1, status = 0, isResponse = true, data = ByteArray(0))
        }
        val client = RcdClient(transport)

        assertFailsWith<RcdProtocolException> { client.invoke(service = 0x0001, command = 1) }
        assertTrue(transport.closed, "a desynced stream is closed")
    }

    @Test
    fun `invoke rejects a request echoed back without the response flag`() = runTest {
        val transport = FakeClientTransport { req ->
            RcdMsg(req.service, req.command, status = 0, isResponse = false, data = ByteArray(0))
        }
        val client = RcdClient(transport)

        assertFailsWith<RcdProtocolException> { client.invoke(service = 0x0001, command = 1) }
        assertTrue(transport.closed)
    }

    @Test
    fun `invoke times out and closes the transport when no response arrives`() = runTest {
        val transport = FakeClientTransport { null } // never responds
        val client = RcdClient(transport)

        assertFailsWith<RcdTimeoutException> { client.invoke(service = 0x0001, command = 1) }
        assertTrue(transport.closed, "a timed-out stream is closed")
    }
}
