// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

import co.touchlab.kermit.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Serves RCD requests arriving on a [RcdTransport], routing each to the registered [RcdService].
 *
 * Business logic: [serve] loops reading requests until the transport closes. Each request is
 * dispatched to the service named by its `service` field; the reply reuses the request's service and
 * command, sets the response flag, and carries either the handler's payload with status 0 or, when
 * the handler raised an [RcdError], an empty payload with the error's code as the status. A request
 * for an unknown service, or one that arrives already flagged as a response, is answered with
 * [RcdErrorBadRequest]. A peer that violates the framing ([RcdProtocolException]) has its transport
 * torn down and serving stops cleanly, and any non-[RcdError] failure likewise tears the transport
 * down (then propagates), matching the reference daemon dropping a client that broke the framing or
 * triggered an internal error. [CancellationException] is never swallowed.
 *
 * @param transport the message boundary this server reads requests from and writes responses to.
 * @param services the services this server exposes, keyed by their [RcdService.serviceId].
 */
class RcdServer(
    private val transport: RcdTransport,
    services: List<RcdService>,
) {
    private val log = Logger.withTag(TAG)
    private val services: Map<Int, RcdService> = services.associateBy { it.serviceId }

    /** Reads and answers requests until the transport reaches end of stream. */
    suspend fun serve() {
        while (true) {
            val request = try {
                transport.receive()
            } catch (closed: RcdClosedException) {
                log.i { "serve(): peer closed the transport -> stopping" }
                break
            } catch (malformed: RcdProtocolException) {
                // A framing violation (oversize/negative/desynced frame) cannot be recovered on a
                // single stream: tear the transport down and stop serving cleanly, as the reference
                // daemon drops a peer that broke the framing.
                log.w { "serve(): peer violated the framing, closing -> error=${malformed.message}" }
                closeTransportQuietly()
                break
            }

            val response = try {
                val data = dispatch(request)
                request.copy(status = 0, data = data, isResponse = true)
            } catch (error: RcdError) {
                log.w {
                    "serve(): command failed -> service=${request.service} command=${request.command} " +
                        "code=0x${error.code.toString(16)}"
                }
                request.copy(status = error.code, data = EMPTY, isResponse = true)
            } catch (other: Throwable) {
                if (other is CancellationException) throw other
                log.e {
                    "serve(): internal error, closing -> service=${request.service} " +
                        "command=${request.command} error=${other.message}"
                }
                closeTransportQuietly()
                throw other
            }

            transport.send(response)
        }
    }

    /** Closes every registered service, then the transport. */
    suspend fun close() {
        services.values.forEach { it.close() }
        transport.close()
    }

    /**
     * Closes the transport during teardown. Runs under [NonCancellable] so a cancellation that is
     * already tearing [serve] down does not skip the close, and swallows any failure from the close
     * itself so teardown cannot raise a second exception over the one being handled.
     */
    private suspend fun closeTransportQuietly() {
        withContext(NonCancellable) {
            try {
                transport.close()
            } catch (failure: Throwable) {
                log.w { "closeTransportQuietly(): ignoring a transport-close failure -> error=${failure.message}" }
            }
        }
    }

    private suspend fun dispatch(msg: RcdMsg): ByteArray {
        val service = services[msg.service]
        if (msg.isResponse || service == null) {
            log.w { "dispatch(): unknown service or response flagged as request -> service=${msg.service} isResponse=${msg.isResponse}" }
            throw RcdErrorBadRequest()
        }
        return service.dispatch(msg)
    }

    private companion object {
        const val TAG = "RcdServer"
        val EMPTY = ByteArray(0)
    }
}
