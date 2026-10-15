package com.mklhc.core.kart_protocol.fuji

import com.mklhc.core.kart_protocol.rcd.FakeClientTransport
import com.mklhc.core.kart_protocol.rcd.RcdClient
import com.mklhc.core.kart_protocol.rcd.RcdMsg
import kotlin.test.assertEquals

/** The RCD status the reference daemon uses for a malformed or unknown request. */
internal const val STATUS_BAD_REQUEST = 0x710e8

/**
 * A Fuji client fixture: an [RcdClient] over a [FakeClientTransport] that records every request and
 * answers each with a status-0 response carrying [response].
 */
internal class FujiFixture(response: ByteArray = ByteArray(0), status: Int = 0) {
    val transport = FakeClientTransport { req ->
        RcdMsg(req.service, req.command, status = status, isResponse = true, data = response)
    }
    val rcd = RcdClient(transport)

    /** The single request the client under test sent. */
    fun sentRequest(): RcdMsg = transport.sent.single()
}

/** Asserts [actual] holds exactly the octets of [expected], reporting both as hex on a mismatch. */
internal fun assertBytes(expected: ByteArray, actual: ByteArray, message: String) {
    assertEquals(expected.toHexString(), actual.toHexString(), message)
}

/** Asserts [request] is the golden [call]: same service, command, response flag clear, same data. */
internal fun assertRequest(call: FujiVectors.Call, request: RcdMsg, message: String) {
    assertEquals(call.service, request.service, "service for $message")
    assertEquals(call.command, request.command, "command for $message")
    assertEquals(false, request.isResponse, "a request has the response flag clear for $message")
    assertBytes(call.request, request.data, "request bytes for $message")
}
