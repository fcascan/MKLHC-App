// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

/**
 * One RCD service: a command namespace an [RcdServer] routes requests to by [serviceId].
 *
 * Business logic: [dispatch] handles a single request and returns the response payload, or throws an
 * [RcdError] (which the server turns into an error status). An unknown command within the service is
 * reported as [RcdErrorBadRequest]. [close] is called when the connection the service was serving
 * goes away, so stateful services (for example the handshake) can react.
 */
interface RcdService {
    /** The service id this service answers to (the message's `service` field). */
    val serviceId: Int

    /**
     * Handles [msg] (always a request) and returns the response payload.
     *
     * @throws RcdError to answer with a non-zero status.
     */
    suspend fun dispatch(msg: RcdMsg): ByteArray

    /** Called when the serving connection closes; the default does nothing. */
    fun close() {}
}
