package com.mklhc.core.netstack.tcp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * Tuning parameters and error types for the minimal single-peer TCP of RFC 9293.
 *
 * Original code. The keepalive defaults are the one place that mirrors an external project; see the
 * note on [TcpConfig].
 */

/**
 * The knobs of the connection layer, all with defaults sized for the kart's RCD channels.
 *
 * Business logic: a minimal, single-peer TCP (one connection per local port). The receive window is
 * fixed and out-of-order segments are dropped, so these values trade generality for simplicity on a
 * short, dedicated link. Durations are injected rather than read from a wall clock so tests drive
 * every timer with virtual time.
 *
 * Keepalive defaults mirror OpenKart (https://github.com/OpenKart-SDK) openkartd/rcd.py
 * (BSD-3-Clause) socket options: SO_KEEPALIVE, TCP_KEEPIDLE=10, TCP_KEEPINTVL=1, TCP_KEEPCNT=4.
 *
 * @param mss the maximum segment size we advertise and cap outbound segments to (RFC 9293 default is 536; 1460 fits a 1500-octet link).
 * @param receiveWindow the fixed receive window we advertise, in octets.
 * @param initialRto the retransmission timeout for the first attempt; it doubles on each retry.
 * @param maxRto the ceiling the backed-off retransmission timeout is clamped to.
 * @param maxRetransmits how many times a segment is retransmitted before the connection is reset.
 * @param keepaliveIdle idle time on an established connection before the first keepalive probe.
 * @param keepaliveInterval the gap between keepalive probes.
 * @param keepaliveCount how many unanswered probes reset the connection.
 * @param timeWait how long [TcpState.TIME_WAIT] lasts before the connection becomes [TcpState.CLOSED];
 * bounded and configurable (short in tests).
 */
class TcpConfig(
    val mss: Int = DEFAULT_MSS,
    val receiveWindow: Int = DEFAULT_RECEIVE_WINDOW,
    val initialRto: Duration = 200.milliseconds,
    val maxRto: Duration = 60.seconds,
    val maxRetransmits: Int = 5,
    val keepaliveIdle: Duration = 10.seconds,
    val keepaliveInterval: Duration = 1.seconds,
    val keepaliveCount: Int = 4,
    val timeWait: Duration = 30.seconds,
) {
    init {
        require(mss in 1..MAX_MSS) { "mss must be 1..$MAX_MSS: mss=$mss" }
        require(receiveWindow in 1..MAX_WINDOW) { "receiveWindow must be 1..$MAX_WINDOW: receiveWindow=$receiveWindow" }
        require(initialRto > Duration.ZERO) { "initialRto must be positive: initialRto=$initialRto" }
        require(maxRto >= initialRto) { "maxRto must be at least initialRto: maxRto=$maxRto initialRto=$initialRto" }
        require(maxRetransmits >= 1) { "maxRetransmits must be at least 1: maxRetransmits=$maxRetransmits" }
        require(keepaliveCount >= 1) { "keepaliveCount must be at least 1: keepaliveCount=$keepaliveCount" }
        require(timeWait > Duration.ZERO) { "timeWait must be positive: timeWait=$timeWait" }
    }

    companion object {
        /**
         * Maximum segment size we advertise: a full 1500-octet Ethernet/802.11 payload minus 20+20
         * headers. A deliberate departure from RFC 9293's conservative 536-octet default, chosen for
         * the kart's single dedicated 1500-octet link where fragmentation is not a concern.
         */
        const val DEFAULT_MSS = 1460

        /** Fixed receive window, 16 KiB. */
        const val DEFAULT_RECEIVE_WINDOW = 16 * 1024

        private const val MAX_MSS = 0xFFFF
        private const val MAX_WINDOW = 0xFFFF
    }
}

/** Base type for the errors the connection layer surfaces to the application. */
open class TcpException(message: String) : RuntimeException(message)

/**
 * The connection was reset: a RST was received or sent, or retransmission or keepalive gave up. A
 * pending [TcpConnection.connect] fails with it, and [TcpConnection.read] / [TcpConnection.write]
 * throw it after an abort.
 */
class TcpResetException(message: String) : TcpException(message)
