// Layout per a community protocol wiki (Fuji kart protocol); format spec, no code copied.
package com.mklhc.core.kart_protocol.teleop

/**
 * Encodes [DriveCommand]s into the 32-octet teleoperation datagram the kart receives on its drive port
 * (UDP, [com.mklhc.core.kart_protocol.fuji.FujiPorts.DRIVE]).
 *
 * Business logic: while driving, the app sends the current drive state about 30 times a second. Each
 * datagram is self-contained and carries a packet counter that lets the kart tell a fresh datagram from
 * a repeated or reordered one:
 *
 * ```
 * [0]      throttle      signed octet, -128 full reverse .. +127 full forward
 * [1]      steering      signed octet, -128 full left    .. +127 full right
 * [2]      brake light   0x00 off, 0x01 on
 * [3]      0x00
 * [4..7]   packet counter, u32 little-endian
 * [8..31]  0x00
 * ```
 *
 * The counter is owned by the encoder: the first packet carries [initialCounter] (0 by default) and every
 * later packet one more, wrapping from 2^32 - 1 to 0. [neutral] is the safe-state packet for the deadman
 * path (input lost, link degraded, app paused); it is an ordinary packet and consumes a counter value
 * like any other, so the kart never sees the same counter twice in a row.
 *
 * Not thread-safe: the counter is plain state, so one coroutine (the 30 Hz send loop) must own the
 * encoder, or callers must serialise access.
 *
 * @param initialCounter the counter value of the first encoded packet, 0..2^32 - 1.
 * @throws IllegalArgumentException if [initialCounter] does not fit in an unsigned 32-bit value.
 */
class TeleopEncoder(initialCounter: Long = 0L) {

    private var counter: Long

    init {
        require(initialCounter in 0..COUNTER_MASK) { "initial counter $initialCounter does not fit in 32 bits" }
        counter = initialCounter
    }

    /**
     * Encodes [command] into a fresh [PACKET_SIZE]-octet datagram and advances the counter.
     *
     * @return the datagram to send to the kart's drive port.
     */
    fun encode(command: DriveCommand): ByteArray {
        val packet = ByteArray(PACKET_SIZE)
        packet[THROTTLE_OFFSET] = command.throttle.toByte()
        packet[STEERING_OFFSET] = command.steering.toByte()
        packet[BRAKE_LIGHT_OFFSET] = if (command.brakeLight) BRAKE_LIGHT_ON else BRAKE_LIGHT_OFF
        for (i in 0 until COUNTER_SIZE) {
            packet[COUNTER_OFFSET + i] = (counter ushr (8 * i)).toByte()
        }
        counter = (counter + 1) and COUNTER_MASK
        return packet
    }

    /** Encodes [DriveCommand.NEUTRAL]: no throttle, no steering, brake light off. */
    fun neutral(): ByteArray = encode(DriveCommand.NEUTRAL)

    companion object {
        /** Octets in every teleoperation datagram. */
        const val PACKET_SIZE = 32

        private const val THROTTLE_OFFSET = 0
        private const val STEERING_OFFSET = 1
        private const val BRAKE_LIGHT_OFFSET = 2
        private const val COUNTER_OFFSET = 4
        private const val COUNTER_SIZE = 4
        private const val COUNTER_MASK = 0xFFFFFFFFL
        private const val BRAKE_LIGHT_ON: Byte = 0x01
        private const val BRAKE_LIGHT_OFF: Byte = 0x00
    }
}
