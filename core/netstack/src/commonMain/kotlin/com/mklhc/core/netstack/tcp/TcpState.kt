package com.mklhc.core.netstack.tcp

/*
 * The TCP connection states of RFC 9293 (formerly RFC 793), figure 5.
 *
 * Business logic: the connection layer is a finite state machine driven by inbound segments, timer
 * ticks and application calls. Original code; the names and transitions follow the RFC.
 */

/**
 * The eleven TCP connection states.
 *
 * - [CLOSED] is the terminal / initial state, with no connection.
 * - [LISTEN] waits for a connection request from a remote peer (a passive open).
 * - [SYN_SENT] has sent a SYN and waits for the matching SYN+ACK (an active open).
 * - [SYN_RECEIVED] has received and answered a SYN and waits for the acknowledging ACK.
 * - [ESTABLISHED] is the open connection; data flows in both directions.
 * - [FIN_WAIT_1] has sent a FIN and waits for its ACK or the peer's FIN.
 * - [FIN_WAIT_2] has had its FIN acknowledged and waits for the peer's FIN.
 * - [CLOSING] saw the peer's FIN before its own FIN was acknowledged (a simultaneous close).
 * - [TIME_WAIT] waits for a bounded time so late segments drain before reuse.
 * - [CLOSE_WAIT] saw the peer's FIN and waits for the application to close.
 * - [LAST_ACK] has sent its own FIN after the peer's and waits for the final ACK.
 */
enum class TcpState {
    CLOSED,
    LISTEN,
    SYN_SENT,
    SYN_RECEIVED,
    ESTABLISHED,
    FIN_WAIT_1,
    FIN_WAIT_2,
    CLOSING,
    TIME_WAIT,
    CLOSE_WAIT,
    LAST_ACK,
}
