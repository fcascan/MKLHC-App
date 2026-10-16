package com.mklhc.core.wifi_ap.pcap

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/*
 * libpcap capture writer for 802.11 frames.
 *
 * Original code; no third-party source was consulted or copied. Implemented from the public file-format
 * specifications:
 *  - the libpcap savefile format (the pcap-savefile man page and the "libpcap file format" description
 *    published by the tcpdump / Wireshark projects): a 24-octet global header followed by one 16-octet
 *    record header plus the captured octets per packet;
 *  - the link-layer header type LINKTYPE_IEEE802_11_RADIOTAP = 127 (the tcpdump.org link-type registry);
 *  - the radiotap header format (https://www.radiotap.org): version, pad, length, present-flags bitmap.
 */

/**
 * Where [PcapWriter] sends the capture bytes: a file, a socket, an in-memory buffer.
 *
 * Business logic: the writer lives in common code, which has no file API, so the platform supplies the
 * destination. [write] receives the bytes in stream order (each call is one complete unit: the global header,
 * or one complete record); [flush] is called after every complete unit so a capture survives the process being
 * killed. The default [flush] does nothing, which suits a sink that does not buffer.
 */
fun interface PcapSink {
    /** Appends [bytes] to the capture. */
    fun write(bytes: ByteArray)

    /** Pushes anything buffered through to the destination. */
    fun flush() {}
}

/**
 * Writes a classic libpcap stream whose packets are raw 802.11 frames, each preceded by a minimal radiotap header.
 *
 * Business logic: the spike (and later the capture toggle in the link diagnostics) records the frames the access
 * point sees, after software decryption, so Wireshark and the protocol analysis tooling read plaintext. The writer is
 * append-style: the global header is written once, when the writer is created, so even an empty capture is a valid
 * file; then every [append] writes one record and flushes the [PcapSink], so a capture streamed to disk is usable
 * even if the process dies. Timestamps come from an injectable [clock] (or are passed explicitly), so tests are
 * deterministic.
 *
 * Byte layout (every multi-octet field is little-endian, which is what the `a1b2c3d4` magic number written in the
 * writer's own byte order means; Wireshark detects the order from the magic):
 *
 * ```
 * Global header, 24 octets, written once
 *   0  u32  magic number            0xA1B2C3D4  (bytes d4 c3 b2 a1: microsecond timestamps, little-endian file)
 *   4  u16  version major           2
 *   6  u16  version minor           4
 *   8  i32  thiszone                0  (timestamps are UTC)
 *  12  u32  sigfigs                 0
 *  16  u32  snaplen                 maximum captured length of a packet ([snapLength])
 *  20  u32  network                 127  (LINKTYPE_IEEE802_11_RADIOTAP)
 *
 * Record, one per appended frame
 *   0  u32  ts_sec                  whole seconds since the Unix epoch (low 32 bits)
 *   4  u32  ts_usec                 microseconds within that second, 0..999999
 *   8  u32  incl_len                octets stored in the record = min(8 + frame length, snaplen)
 *  12  u32  orig_len                octets on the wire = 8 + frame length
 *  16  ...  the packet: radiotap header (8 octets) followed by the 802.11 frame (incl_len - 8 octets)
 *
 * Radiotap header, 8 octets, identical for every record
 *   0  u8   version                 0
 *   1  u8   pad                     0
 *   2  u16  length                  8  (the whole header, so the 802.11 frame starts at offset 8)
 *   4  u32  present                 0  (no radiotap fields follow and the "extended bitmap" bit 31 is clear)
 * ```
 *
 * The 802.11 frame is stored as given, without an FCS (radiod strips it), and the radiotap header carries no
 * per-frame metadata (no rate, no signal), which keeps every record self-describing for Wireshark while staying
 * 8 octets. [snapLength] defaults to 65535, far above any 802.11 MPDU, so nothing is normally truncated; if a frame
 * is longer than the snap length the record is cut at it and `orig_len` still reports the full size.
 *
 * Not thread-safe: append from one coroutine or thread.
 *
 * @param sink where the bytes go.
 * @param snapLength the snaplen field and the cap on stored octets per record; at least the radiotap header size.
 * @param clock the current time as microseconds since the Unix epoch; the default is the system clock.
 */
class PcapWriter(
    private val sink: PcapSink,
    private val snapLength: Int = DEFAULT_SNAP_LENGTH,
    private val clock: () -> Long = ::currentEpochMicros,
) {

    /** How many frames have been appended so far. */
    var framesWritten: Int = 0
        private set

    /** How many octets have been handed to the sink so far, global header included. */
    var bytesWritten: Long = 0
        private set

    init {
        require(snapLength >= RADIOTAP_HEADER_BYTES) {
            "snap length must be at least the radiotap header size ($RADIOTAP_HEADER_BYTES): snapLength=$snapLength"
        }
        emit(globalHeader())
    }

    /**
     * Appends one 802.11 [frame] (no FCS) as a record stamped with [timestampMicros], which defaults to the
     * writer's clock, and flushes the sink.
     *
     * @param frame the 802.11 MPDU; copied into the record, so later changes to the array do not matter.
     * @param timestampMicros microseconds since the Unix epoch, not negative.
     * @throws IllegalArgumentException if [timestampMicros] is negative.
     */
    fun append(frame: ByteArray, timestampMicros: Long = clock()) {
        require(timestampMicros >= 0) { "timestamp must not be negative: timestampMicros=$timestampMicros" }
        val originalLength = RADIOTAP_HEADER_BYTES + frame.size
        val includedLength = minOf(originalLength, snapLength)

        val record = ByteArray(RECORD_HEADER_BYTES + includedLength)
        putLe32(record, 0, timestampMicros / MICROS_PER_SECOND)
        putLe32(record, 4, timestampMicros % MICROS_PER_SECOND)
        putLe32(record, 8, includedLength.toLong())
        putLe32(record, 12, originalLength.toLong())
        RADIOTAP_HEADER.copyInto(record, destinationOffset = RECORD_HEADER_BYTES)
        frame.copyInto(
            record,
            destinationOffset = RECORD_HEADER_BYTES + RADIOTAP_HEADER_BYTES,
            startIndex = 0,
            endIndex = includedLength - RADIOTAP_HEADER_BYTES,
        )
        emit(record)
        framesWritten++
    }

    private fun globalHeader(): ByteArray {
        val header = ByteArray(GLOBAL_HEADER_BYTES)
        putLe32(header, 0, PCAP_MAGIC)
        putLe16(header, 4, VERSION_MAJOR)
        putLe16(header, 6, VERSION_MINOR)
        putLe32(header, 8, 0) // thiszone: UTC
        putLe32(header, 12, 0) // sigfigs
        putLe32(header, 16, snapLength.toLong())
        putLe32(header, 20, LINKTYPE_IEEE802_11_RADIOTAP.toLong())
        return header
    }

    private fun emit(bytes: ByteArray) {
        sink.write(bytes)
        sink.flush()
        bytesWritten += bytes.size
    }

    companion object {
        /** `network` field of the global header: radiotap header followed by an 802.11 frame. */
        const val LINKTYPE_IEEE802_11_RADIOTAP = 127

        /** The default snap length: larger than any 802.11 MPDU, so nothing is truncated. */
        const val DEFAULT_SNAP_LENGTH = 65_535

        /** Octets in the global header. */
        const val GLOBAL_HEADER_BYTES = 24

        /** Octets in each record header. */
        const val RECORD_HEADER_BYTES = 16

        /** Octets in the radiotap header every record starts its packet with. */
        const val RADIOTAP_HEADER_BYTES = 8

        private const val PCAP_MAGIC = 0xA1B2C3D4L
        private const val VERSION_MAJOR = 2
        private const val VERSION_MINOR = 4
        private const val MICROS_PER_SECOND = 1_000_000L

        /** Radiotap: version 0, pad 0, length 8 (little-endian u16), present bitmap 0 (u32). */
        private val RADIOTAP_HEADER = byteArrayOf(0, 0, RADIOTAP_HEADER_BYTES.toByte(), 0, 0, 0, 0, 0)

        private fun putLe16(target: ByteArray, offset: Int, value: Int) {
            target[offset] = value.toByte()
            target[offset + 1] = (value shr Byte.SIZE_BITS).toByte()
        }

        /** Stores the low 32 bits of [value] little-endian at [offset]. */
        private fun putLe32(target: ByteArray, offset: Int, value: Long) {
            for (index in 0 until Int.SIZE_BYTES) {
                target[offset + index] = (value shr (index * Byte.SIZE_BITS)).toByte()
            }
        }
    }
}

/** Default clock for [PcapWriter]: the current Unix time in microseconds. */
@OptIn(ExperimentalTime::class)
private fun currentEpochMicros(): Long {
    val now = Clock.System.now()
    return now.epochSeconds * 1_000_000L + now.nanosecondsOfSecond / 1_000
}
