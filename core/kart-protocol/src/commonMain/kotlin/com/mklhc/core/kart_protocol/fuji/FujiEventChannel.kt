// Adapted from mk-live https://github.com/olus85/mk-live, patches/openkartd/fuji.py (MIT).
package com.mklhc.core.kart_protocol.fuji

import co.touchlab.kermit.Logger
import com.mklhc.core.kart_protocol.rcd.RcdClosedException
import com.mklhc.core.kart_protocol.rcd.RcdError
import com.mklhc.core.kart_protocol.rcd.RcdMsg
import com.mklhc.core.kart_protocol.rcd.RcdProtocolException
import com.mklhc.core.kart_protocol.rcd.RcdTransport
import kotlin.coroutines.cancellation.CancellationException

/**
 * Decides the payload of the app's reply to one event the kart sent over the event channel.
 *
 * Business logic: the meaning of the kart's event requests is not understood yet, so the reply is a
 * pluggable policy. [Empty] (the default) answers every event with an empty payload, which is what
 * mk-live does and is enough to keep the kart happy. A policy may throw an
 * [com.mklhc.core.kart_protocol.rcd.RcdError] to answer with that error status instead.
 */
fun interface FujiEventResponsePolicy {
    /** Returns the response payload for [request] (always a request, never a response). */
    suspend fun respond(request: RcdMsg): ByteArray

    companion object {
        /** Answers every event with an empty payload. */
        val Empty: FujiEventResponsePolicy = FujiEventResponsePolicy { ByteArray(0) }
    }
}

/**
 * The app's side of the kart's event connection (TCP port [FujiPorts.EVENT], service 0x103).
 *
 * Business logic: unlike the control and pairing connections, here the kart is the requester. The app
 * connects to the kart's event port and then [run]s this channel: it reads each request the kart sends,
 * logs it at debug level (service, command, size and the first octets of the payload) so the event
 * semantics can be worked out later from captured logs, and answers it with status 0 and the payload the
 * [policy] chooses (empty by default, as mk-live does). The reply echoes the request's service and
 * command and sets the response flag, because the kart matches a reply to its request by those. The
 * channel answers any service id on the connection, not only 0x103.
 *
 * A message that arrives already flagged as a response is not a request, so it is logged and ignored
 * (a response must never be answered with a response). A policy that fails unexpectedly does not drop
 * the connection: the failure is logged and the empty reply is sent instead, because losing the event
 * connection can make the kart reset.
 *
 * @param transport the message boundary of the connection to the kart's event port.
 * @param policy chooses the reply payload for each event; defaults to [FujiEventResponsePolicy.Empty].
 * @param log the logger; replaceable so tests can capture what is logged.
 */
class FujiEventChannel(
    private val transport: RcdTransport,
    private val policy: FujiEventResponsePolicy = FujiEventResponsePolicy.Empty,
    private val log: Logger = Logger.withTag(TAG),
) {

    /**
     * Reads and answers the kart's events until it closes the connection.
     *
     * A clean end of stream ends the loop normally. A framing violation ([RcdProtocolException]) is
     * unrecoverable on this single stream, so it is logged, the transport is closed and the loop ends
     * cleanly rather than letting the exception escape without tearing the connection down.
     * [kotlin.coroutines.cancellation.CancellationException] is never swallowed.
     */
    suspend fun run() {
        while (true) {
            val request = try {
                transport.receive()
            } catch (closed: RcdClosedException) {
                log.i { "run(): kart closed the event connection -> stopping" }
                return
            } catch (malformed: RcdProtocolException) {
                log.w { "run(): kart violated the framing, closing -> error=${malformed.message}" }
                transport.close()
                return
            }

            if (request.isResponse) {
                log.w { "run(): ignoring a response on the event channel -> service=${hex16(request.service)} command=${request.command}" }
                continue
            }

            logEvent(request)
            transport.send(answer(request))
        }
    }

    /** Closes the underlying transport. */
    suspend fun close() {
        transport.close()
    }

    /** Logs one event at debug level: its service, command, size and the first octets of its payload. */
    private fun logEvent(request: RcdMsg) {
        log.d {
            "run(): event received -> service=${hex16(request.service)} command=${request.command} " +
                "size=${request.data.size} head=${request.data.copyOf(minOf(request.data.size, HEAD_SIZE)).toHexString()}"
        }
    }

    /** Builds the reply to [request]: the policy's payload, the policy's error status, or the empty fallback. */
    private suspend fun answer(request: RcdMsg): RcdMsg {
        val data = try {
            policy.respond(request)
        } catch (error: RcdError) {
            log.w { "answer(): policy raised an error status -> command=${request.command} code=0x${error.code.toString(16)}" }
            return request.copy(status = error.code, data = EMPTY, isResponse = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.e(failure) { "answer(): policy failed, replying empty -> command=${request.command}" }
            EMPTY
        }
        return request.copy(status = 0, data = data, isResponse = true)
    }

    /** Formats a service id as `0x` plus four hex digits, as the design documents it (0x0103). */
    private fun hex16(value: Int): String = "0x" + value.toString(16).padStart(4, '0')

    companion object {
        /** The RCD service id of the event channel. */
        const val SERVICE_ID = 0x103

        private const val TAG = "FujiEventChannel"

        /** How many leading payload octets an event log line shows. */
        private const val HEAD_SIZE = 16

        private val EMPTY = ByteArray(0)
    }
}
