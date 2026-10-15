// Adapted from mk-live https://github.com/olus85/mk-live, src/mk_live/video_worker.py (MIT).
package com.mklhc.core.kart_protocol.video

/**
 * Encoder of the "LVNI" keepalive the app sends on the video (LSP) control TCP channel.
 *
 * Business logic: while video is playing the app sends one LVNI record per second ([KEEPALIVE_INTERVAL_MS])
 * so the kart keeps streaming. The record is the ASCII tag "LVNI" followed by little-endian fields,
 * 24 octets in all ([PACKET_SIZE]):
 *
 * ```
 * [0..3]    "LVNI"                      4c 56 4e 49
 * [4..11]   previous frame id           u64 little-endian
 * [12..19]  current frame id            u64 little-endian
 * [20..23]  flags                       u32 little-endian
 * ```
 *
 * OPEN UNKNOWN: which values the kart expects in the two frame-id fields is NOT established. The reference
 * implementation itself picks the id with several heuristic modes, which shows its source is not settled.
 * This object therefore only lays out the bytes; the CALLER chooses the ids and flags, and no extractor is
 * provided on purpose so that no single heuristic gets baked in. The only built-in counter near this code,
 * [LspVideoReassembler.framesEmitted], is a local count and is not claimed to equal the kart's frame id.
 * The id semantics (a FRAM header field, a running local count, or something else) and the meaning of
 * [flags] must be confirmed against captures of the real video control channel at the hardware spike.
 *
 * Stateless; safe to share between threads.
 */
object Lvni {
    /** Octets in every LVNI record: 4 tag + 8 + 8 + 4. */
    const val PACKET_SIZE = 24

    /** How often the keepalive is sent, in milliseconds (once per second). */
    const val KEEPALIVE_INTERVAL_MS = 1_000L

    private const val ID_SIZE = 8
    private const val FLAGS_SIZE = 4
    private const val PREVIOUS_ID_OFFSET = 4
    private const val CURRENT_ID_OFFSET = PREVIOUS_ID_OFFSET + ID_SIZE
    private const val FLAGS_OFFSET = CURRENT_ID_OFFSET + ID_SIZE

    private val ASCII_TAG = "LVNI".encodeToByteArray()

    /**
     * Builds one LVNI record.
     *
     * The ids and the flags are written as their raw bit patterns (an unsigned 64-bit id above `Long.MAX_VALUE`
     * is passed as a negative [Long], and a flags word with bit 31 set as a negative [Int]), so nothing is
     * rejected.
     *
     * @param previousFrameId the "previous frame" id field.
     * @param currentFrameId the "current frame" id field.
     * @param flags the flags field.
     * @return a fresh [PACKET_SIZE]-octet array.
     */
    fun encode(previousFrameId: Long, currentFrameId: Long, flags: Int): ByteArray {
        val packet = ByteArray(PACKET_SIZE)
        ASCII_TAG.copyInto(packet)
        for (i in 0 until ID_SIZE) {
            packet[PREVIOUS_ID_OFFSET + i] = (previousFrameId ushr (8 * i)).toByte()
            packet[CURRENT_ID_OFFSET + i] = (currentFrameId ushr (8 * i)).toByte()
        }
        for (i in 0 until FLAGS_SIZE) {
            packet[FLAGS_OFFSET + i] = (flags ushr (8 * i)).toByte()
        }
        return packet
    }
}
