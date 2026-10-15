// Ported from OpenKart https://github.com/OpenKart-SDK, openkartd/rcd.py (BSD-3-Clause).
// Copyright 2021 Sam Edwards.
package com.mklhc.core.kart_protocol.rcd

import co.touchlab.kermit.Logger
import com.mklhc.core.netstack.tcp.TcpConnection
import com.mklhc.core.netstack.tcp.TcpException

/**
 * The message boundary RCD runs over: it exchanges whole [RcdMsg]s, hiding how they are framed on the
 * underlying stream.
 *
 * Business logic: keeping RCD above a message boundary (rather than raw bytes) lets the client and
 * server be unit-tested over an in-memory duplex, while production runs them over a real TCP byte
 * stream via [FramedRcdTransport]. [receive] throws [RcdClosedException] at end of stream.
 */
interface RcdTransport {
    /** Reads the next whole message. @throws RcdClosedException at end of stream. */
    suspend fun receive(): RcdMsg

    /** Sends a whole message. */
    suspend fun send(msg: RcdMsg)

    /** Closes the transport; further [receive]s on either end eventually see [RcdClosedException]. */
    suspend fun close()
}

/** Signals the transport reached end of stream (the peer closed, or the stream was torn down). */
class RcdClosedException(message: String = "RCD transport closed") : Exception(message)

/** Signals a peer violated the framing or the request/response contract. */
class RcdProtocolException(message: String) : Exception(message)

/**
 * A reliable, ordered byte stream RCD can be framed over (the subset of a TCP connection RCD needs).
 *
 * [read] returns an empty array at a clean end of stream.
 */
interface RcdByteStream {
    /** Reads up to [max] octets, suspending until some arrive; an empty array means end of stream. */
    suspend fun read(max: Int): ByteArray

    /** Writes all of [bytes]. */
    suspend fun write(bytes: ByteArray)

    /** Closes the stream. */
    suspend fun close()
}

/**
 * Frames [RcdMsg]s over an [RcdByteStream]: each message is its 16-octet header followed by its
 * payload. On receive it reads the header, learns the payload size from it, and reads exactly that
 * many more octets; a declared payload larger than [RcdMsg.MAX_DATA_SIZE] is rejected rather than
 * buffered, mirroring the reference daemon terminating an over-large peer.
 */
class FramedRcdTransport(private val stream: RcdByteStream) : RcdTransport {

    private val log = Logger.withTag(TAG)

    override suspend fun send(msg: RcdMsg) {
        require(msg.data.size <= RcdMsg.MAX_DATA_SIZE) {
            "payload of ${msg.data.size} octets exceeds the ${RcdMsg.MAX_DATA_SIZE}-octet maximum"
        }
        stream.write(msg.encode())
    }

    override suspend fun receive(): RcdMsg {
        val header = readFully(RcdMsg.HEADER_SIZE)
        // A declared length with the high bit set reads negative (see RcdMsg.size); flag either a
        // malformed or an over-large payload here rather than let a negative size reach ByteArray().
        val dataSize = try {
            RcdMsg.size(header) - RcdMsg.HEADER_SIZE
        } catch (malformed: RcdProtocolException) {
            log.w { "receive(): rejecting a malformed header -> error=${malformed.message}" }
            throw malformed
        }
        if (dataSize < 0 || dataSize > RcdMsg.MAX_DATA_SIZE) {
            log.w { "receive(): rejecting an out-of-range payload -> declared=$dataSize max=${RcdMsg.MAX_DATA_SIZE}" }
            throw RcdProtocolException("peer declared an out-of-range payload of $dataSize octets")
        }
        val data = readFully(dataSize)
        return RcdMsg.decode(header + data)
    }

    override suspend fun close() {
        log.d { "close(): closing the framed transport" }
        stream.close()
    }

    /** Reads exactly [count] octets, or throws [RcdClosedException] if the stream ends first. */
    private suspend fun readFully(count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val chunk = stream.read(count - offset)
            if (chunk.isEmpty()) throw RcdClosedException("stream ended after $offset of $count octets")
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    private companion object {
        const val TAG = "FramedRcdTransport"
    }
}

/**
 * Adapts a netstack [TcpConnection] to an [RcdByteStream] so RCD can run over real TCP in production.
 *
 * On the kart's RF link a reset is the NORMAL teardown, so [TcpConnection.read] throws a
 * [com.mklhc.core.netstack.tcp.TcpResetException] and [TcpConnection.write] a
 * [TcpException]/[com.mklhc.core.netstack.tcp.TcpResetException] on a dead connection. Those are
 * translated here to a single [RcdClosedException] so RCD above only ever sees one clean
 * "stream closed" signal rather than a raw TCP exception. A clean FIN is already reported as an empty
 * read, which the framing layer turns into [RcdClosedException].
 */
class TcpRcdByteStream(private val connection: TcpConnection) : RcdByteStream {
    override suspend fun read(max: Int): ByteArray =
        try {
            connection.read(max)
        } catch (torn: TcpException) {
            throw RcdClosedException("TCP connection torn down: ${torn.message}")
        }

    override suspend fun write(bytes: ByteArray) =
        try {
            connection.write(bytes)
        } catch (torn: TcpException) {
            throw RcdClosedException("TCP connection torn down: ${torn.message}")
        }

    override suspend fun close() = connection.close()
}

/** Builds the production transport: RCD framed over [this] TCP connection. */
fun TcpConnection.asRcdTransport(): RcdTransport = FramedRcdTransport(TcpRcdByteStream(this))
