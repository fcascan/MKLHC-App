package com.mklhc.core.kart_protocol.fuji

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.mklhc.core.kart_protocol.rcd.InMemoryDuplex
import com.mklhc.core.kart_protocol.rcd.RcdErrorBadRequest
import com.mklhc.core.kart_protocol.rcd.RcdMsg
import com.mklhc.core.kart_protocol.rcd.RcdProtocolException
import com.mklhc.core.kart_protocol.rcd.ThrowingReceiveTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [FujiEventChannel]: the kart sends requests over the event connection (the far end of an
 * in-memory duplex here) and the channel answers each one. The default reply is status 0 with an empty
 * payload (mk-live behaviour), every event is logged for later analysis, and the reply policy is
 * pluggable because the real event semantics are still unknown.
 */
class FujiEventChannelTest {

    /** Collects every Kermit log call so a test can assert what the channel logged. */
    private class CapturingLogWriter : LogWriter() {
        class Entry(val severity: Severity, val message: String)

        val entries = mutableListOf<Entry>()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            entries += Entry(severity, message)
        }
    }

    private fun capturingLogger(writer: CapturingLogWriter) =
        Logger(StaticConfig(logWriterList = listOf(writer)), tag = "FujiEventChannelTest")

    private fun event(command: Int, vararg data: Int, service: Int = FujiEventChannel.SERVICE_ID) =
        RcdMsg(service, command, status = 0, isResponse = false, data = ByteArray(data.size) { data[it].toByte() })

    @Test
    fun `service id is 0x103`() {
        assertEquals(0x103, FujiEventChannel.SERVICE_ID)
    }

    @Test
    fun `replies to an event with status 0 and an empty payload`() = runTest {
        val duplex = InMemoryDuplex()
        backgroundScope.launch { FujiEventChannel(duplex.client).run() }

        duplex.server.send(event(command = 5, 1, 2, 3))
        val reply = duplex.server.receive()

        assertEquals(FujiEventChannel.SERVICE_ID, reply.service)
        assertEquals(5, reply.command, "the reply echoes the command")
        assertEquals(0, reply.status)
        assertTrue(reply.isResponse, "the reply has the response flag set")
        assertEquals(0, reply.data.size, "the default reply payload is empty")
    }

    @Test
    fun `answers every event in order`() = runTest {
        val duplex = InMemoryDuplex()
        backgroundScope.launch { FujiEventChannel(duplex.client).run() }

        duplex.server.send(event(command = 1))
        duplex.server.send(event(command = 2))
        duplex.server.send(event(command = 3))

        assertEquals(listOf(1, 2, 3), List(3) { duplex.server.receive().command })
    }

    @Test
    fun `echoes whatever service the kart used`() = runTest {
        val duplex = InMemoryDuplex()
        backgroundScope.launch { FujiEventChannel(duplex.client).run() }

        duplex.server.send(event(command = 9, service = 0x104))
        val reply = duplex.server.receive()

        assertEquals(0x104, reply.service, "the kart matches the reply to its request by service")
        assertEquals(0, reply.status)
    }

    @Test
    fun `logs every event with service, command and a bounded payload head`() = runTest {
        val writer = CapturingLogWriter()
        val duplex = InMemoryDuplex()
        backgroundScope.launch { FujiEventChannel(duplex.client, log = capturingLogger(writer)).run() }

        duplex.server.send(event(7, *IntArray(20) { it }))
        duplex.server.receive()

        val logged = writer.entries.single { it.message.contains("command=7") }
        assertEquals(Severity.Debug, logged.severity)
        assertTrue(logged.message.contains("service=0x0103"), logged.message)
        assertTrue(logged.message.contains("size=20"), logged.message)
        assertTrue(logged.message.contains("000102030405060708090a0b0c0d0e0f"), "payload head: ${logged.message}")
        assertFalse(logged.message.contains("000102030405060708090a0b0c0d0e0f10"), "head is bounded to 16 octets")
    }

    @Test
    fun `uses the configured response policy`() = runTest {
        val duplex = InMemoryDuplex()
        val policy = FujiEventResponsePolicy { request -> byteArrayOf(request.command.toByte(), 0x55) }
        backgroundScope.launch { FujiEventChannel(duplex.client, policy).run() }

        duplex.server.send(event(command = 4))
        val reply = duplex.server.receive()

        assertEquals(0, reply.status)
        assertEquals("0455", reply.data.toHexString())
    }

    @Test
    fun `a policy RcdError becomes the reply status`() = runTest {
        val duplex = InMemoryDuplex()
        val policy = FujiEventResponsePolicy { throw RcdErrorBadRequest() }
        backgroundScope.launch { FujiEventChannel(duplex.client, policy).run() }

        duplex.server.send(event(command = 4))
        val reply = duplex.server.receive()

        assertEquals(STATUS_BAD_REQUEST, reply.status)
        assertEquals(0, reply.data.size)
        assertTrue(reply.isResponse)
    }

    @Test
    fun `a failing policy falls back to the empty reply and keeps the channel alive`() = runTest {
        val writer = CapturingLogWriter()
        val duplex = InMemoryDuplex()
        var calls = 0
        val policy = FujiEventResponsePolicy {
            calls++
            if (calls == 1) error("policy bug") else ByteArray(0)
        }
        backgroundScope.launch { FujiEventChannel(duplex.client, policy, capturingLogger(writer)).run() }

        duplex.server.send(event(command = 1))
        val first = duplex.server.receive()
        duplex.server.send(event(command = 2))
        val second = duplex.server.receive()

        assertEquals(0, first.status, "a policy failure must not make the kart see an error")
        assertEquals(0, first.data.size)
        assertEquals(2, second.command, "the channel keeps serving after a policy failure")
        assertTrue(writer.entries.any { it.severity == Severity.Error }, "the policy failure is logged as an error")
    }

    @Test
    fun `ignores a message that is flagged as a response`() = runTest {
        val duplex = InMemoryDuplex()
        backgroundScope.launch { FujiEventChannel(duplex.client).run() }

        duplex.server.send(event(command = 1).copy(isResponse = true))
        duplex.server.send(event(command = 2))
        val reply = duplex.server.receive()

        assertEquals(2, reply.command, "a stray response gets no reply; the next request is answered")
    }

    @Test
    fun `run closes the transport and ends without throwing on a framing error`() = runTest {
        val transport = ThrowingReceiveTransport { RcdProtocolException("peer declared an oversize payload") }

        // run() must return normally rather than let the framing error escape without closing.
        FujiEventChannel(transport).run()

        assertTrue(transport.closed, "a framing error from receive() closes the event connection")
    }

    @Test
    fun `run returns when the kart closes the connection`() = runTest {
        val duplex = InMemoryDuplex()
        val running = backgroundScope.launch { FujiEventChannel(duplex.client).run() }

        duplex.server.send(event(command = 1))
        duplex.server.receive()
        duplex.server.close()
        running.join()

        assertTrue(running.isCompleted, "run() ends normally at end of stream")
        assertFalse(running.isCancelled, "run() must not fail at end of stream")
    }
}
