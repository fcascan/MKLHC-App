package com.mklhc.core.kart_protocol.rcd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests for [RcdServer] driven by a real [RcdClient] over an in-memory duplex: a request
 * routed to its service, an unknown service and an unknown command (both [RcdErrorBadRequest]), and a
 * service error surfaced as the response status. Ported behaviour of OpenKart's `RcdServer`
 * serve/dispatch loop (openkartd/rcd.py).
 */
class RcdServerTest {

    private val errorCodes = RcdVectors.load().errorCodes

    private class EchoService(override val serviceId: Int) : RcdService {
        override suspend fun dispatch(msg: RcdMsg): ByteArray = when (msg.command) {
            CMD_ECHO -> msg.data
            CMD_FAIL -> throw RcdErrorMissizedPayload()
            else -> throw RcdErrorBadRequest()
        }

        companion object {
            const val CMD_ECHO = 1
            const val CMD_FAIL = 2
        }
    }

    @Test
    fun `server routes a request to its service and returns the payload`() = runTest {
        val duplex = InMemoryDuplex()
        val server = RcdServer(duplex.server, listOf(EchoService(serviceId = 5)))
        backgroundScope.launch { server.serve() }
        val client = RcdClient(duplex.client)

        val echoed = client.invoke(service = 5, command = EchoService.CMD_ECHO, data = byteArrayOf(9, 8, 7))

        assertEquals("090807", echoed.toHexString())
    }

    @Test
    fun `server answers an unknown service with BadRequest`() = runTest {
        val duplex = InMemoryDuplex()
        val server = RcdServer(duplex.server, listOf(EchoService(serviceId = 5)))
        backgroundScope.launch { server.serve() }
        val client = RcdClient(duplex.client)

        val error = assertFailsWith<RcdError> { client.invoke(service = 99, command = 1) }
        assertEquals(errorCodes["bad_request"], error.code)
    }

    @Test
    fun `server answers an unknown command with BadRequest`() = runTest {
        val duplex = InMemoryDuplex()
        val server = RcdServer(duplex.server, listOf(EchoService(serviceId = 5)))
        backgroundScope.launch { server.serve() }
        val client = RcdClient(duplex.client)

        val error = assertFailsWith<RcdError> { client.invoke(service = 5, command = 9) }
        assertEquals(errorCodes["bad_request"], error.code)
    }

    @Test
    fun `serve closes the transport and ends without throwing on a framing error`() = runTest {
        val transport = ThrowingReceiveTransport { RcdProtocolException("peer declared an oversize payload") }
        val server = RcdServer(transport, listOf(EchoService(serviceId = 5)))

        // serve() must return normally (the framing error is handled), not propagate the exception.
        server.serve()

        assertTrue(transport.closed, "a framing error from receive() tears the transport down")
    }

    @Test
    fun `serve does not swallow cancellation from receive`() = runTest {
        val transport = ThrowingReceiveTransport { CancellationException("cancelled") }
        val server = RcdServer(transport, listOf(EchoService(serviceId = 5)))

        assertFailsWith<CancellationException> { server.serve() }
    }

    @Test
    fun `server surfaces a service error as the response status`() = runTest {
        val duplex = InMemoryDuplex()
        val server = RcdServer(duplex.server, listOf(EchoService(serviceId = 5)))
        backgroundScope.launch { server.serve() }
        val client = RcdClient(duplex.client)

        val error = assertFailsWith<RcdError> { client.invoke(service = 5, command = EchoService.CMD_FAIL) }
        assertEquals(errorCodes["missized_payload"], error.code)
    }
}
