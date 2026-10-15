// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

/**
 * An RCD protocol error, identified by its 20-bit-ish numeric [code].
 *
 * Business logic: a server encodes the error as the message's `status` field; a non-zero status on a
 * response is surfaced to the caller as an [RcdError]. The concrete subclasses carry the exact code
 * values the reference daemon uses, so a ported handler can throw the right one and a peer can match
 * on it. The base class is open and directly instantiable for a status whose code is not one of the
 * known subclasses (for example a code a newer peer invented): `RcdError(status)`.
 */
open class RcdError(val code: Int) : Exception("RcdError(0x${code.toString(16)})")

/** A command payload had the wrong length. */
class RcdErrorMissizedPayload : RcdError(0x708e8)

/** The service or command was unknown, or a request arrived flagged as a response. */
class RcdErrorBadRequest : RcdError(0x710e8)

/** The handshake offered an unsupported handshake-protocol version. */
class RcdErrorHandshakeVersionMismatch : RcdError(0x800e8)

/** A handshake command arrived out of the expected order. */
class RcdErrorHandshakeSequence : RcdError(0x810e8)

/** No version offered by the device is supported by the server. */
class RcdErrorHandshakeBadVersions : RcdError(0x820e8)

/** The device's running-hash digest did not match the server's at finalize. */
class RcdErrorHandshakeHash : RcdError(0x830e8)

/** The device presented a pairing id the server does not recognize (and pairing is off). */
class RcdErrorHandshakeUnrecognizedDevice : RcdError(0x850e8)
