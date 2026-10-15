// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

import co.touchlab.kermit.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Signals an [RcdClient.invoke] did not receive its response within the timeout. */
class RcdTimeoutException(message: String) : Exception(message)

/**
 * The client half of RCD: issues a request and awaits its matching response.
 *
 * Business logic: [invoke] sends a request (response flag clear) and waits for the reply. Only one
 * exchange runs at a time -- a [Mutex] serializes them -- because the transport is a single stream
 * with no request ids, so a reply is matched to its request positionally and then checked to carry
 * the same service and command with the response flag set; a mismatch means the stream desynced and
 * is treated as a protocol error. A non-zero status is raised as the corresponding [RcdError]. If the
 * reply does not arrive within the timeout the transport is closed and [RcdTimeoutException] is
 * raised, since a stream with an outstanding, unanswered request can no longer be trusted.
 *
 * @param transport the message boundary to exchange over.
 */
class RcdClient(private val transport: RcdTransport) {
    private val log = Logger.withTag(TAG)
    private val lock = Mutex()

    /**
     * Sends a request on [service]/[command] with [data] and returns the response payload.
     *
     * @throws RcdError if the server answered with a non-zero status.
     * @throws RcdProtocolException if the response did not match the request.
     * @throws RcdTimeoutException if no response arrived within [timeout].
     * @throws RcdClosedException if the transport closed while waiting.
     */
    suspend fun invoke(
        service: Int,
        command: Int,
        data: ByteArray = EMPTY,
        timeout: Duration = 1.seconds,
    ): ByteArray {
        val response = exchange(RcdMsg(service, command, status = 0, isResponse = false, data = data), timeout)

        if (response.service != service || response.command != command || !response.isResponse) {
            log.e {
                "invoke(): response did not match request, closing -> request=$service/$command " +
                    "response=${response.service}/${response.command} isResponse=${response.isResponse}"
            }
            transport.close()
            throw RcdProtocolException(
                "response ${response.service}/${response.command} does not match request $service/$command",
            )
        }
        if (response.status != 0) {
            log.w {
                "invoke(): command returned a non-zero status -> service=$service command=$command " +
                    "status=0x${response.status.toString(16)}"
            }
            throw RcdError(response.status)
        }
        return response.data
    }

    /** Serializes the single-stream send-then-receive, failing the exchange if the reply is late. */
    private suspend fun exchange(request: RcdMsg, timeout: Duration): RcdMsg = lock.withLock {
        transport.send(request)
        try {
            withTimeout(timeout) { transport.receive() }
        } catch (timedOut: TimeoutCancellationException) {
            log.e {
                "exchange(): timed out, closing -> service=${request.service} " +
                    "command=${request.command} timeout=$timeout"
            }
            transport.close()
            throw RcdTimeoutException(
                "RCD invoke timed out after $timeout on ${request.service}/${request.command}",
            )
        }
    }

    private companion object {
        const val TAG = "RcdClient"
        val EMPTY = ByteArray(0)
    }
}
