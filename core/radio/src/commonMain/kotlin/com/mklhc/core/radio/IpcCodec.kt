package com.mklhc.core.radio

import kotlin.text.CharacterCodingException

/**
 * Message type ids of the radiod IPC protocol, as carried in the first two bytes of every header.
 *
 * Ids below `0x0100` are app -> radiod commands, ids from `0x0100` up are radiod -> app
 * notifications. radiod must use exactly these values.
 */
enum class IpcMessageType(val id: Int) {
    HELLO(0x0001),
    OPEN_DEVICE(0x0002),
    INIT(0x0003),
    SET_IDENTITY(0x0004),
    START_BEACON(0x0005),
    UPDATE_BEACON(0x0006),
    STOP_BEACON(0x0007),
    TX_FRAME(0x0008),
    SHUTDOWN(0x0009),
    READY(0x0101),
    RX_FRAME(0x0102),
    STATUS(0x0103),
    LOG(0x0104),
    ERROR(0x0105),
    ;

    companion object {
        /** Returns the type with wire id [id], or null when the id is unknown. */
        fun fromId(id: Int): IpcMessageType? = entries.firstOrNull { it.id == id }
    }
}

/** Thrown by [IpcCodec] when bytes do not form a valid IPC message. */
class IpcDecodeException(message: String) : IllegalArgumentException(message)

/**
 * Codec for the radiod IPC wire format. Pure Kotlin, no platform APIs, little-endian throughout.
 *
 * Business logic: every message is a fixed 8 byte header followed by a payload:
 *
 * ```
 * u16 type | u16 flags | u32 length | payload[length]
 * ```
 *
 * `flags` is reserved: it is written as 0 and ignored on decode. `length` is the payload size in
 * bytes, at most [MAX_PAYLOAD_LENGTH]. A variable-length field (UTF-8 string or byte array) is
 * written as a `u32` byte count followed by the bytes; this is written `lp(...)` below. Payloads:
 *
 * | Type | Payload |
 * |---|---|
 * | HELLO | u16 protocolVersion, lp(token utf8) |
 * | OPEN_DEVICE | u16 vid, u16 pid |
 * | INIT | u8 channel, u8 bandwidthMhz, u8 txPowerIndex |
 * | SET_IDENTITY | mac[6] |
 * | START_BEACON | u16 intervalTu, lp(beacon) |
 * | UPDATE_BEACON | lp(beacon) |
 * | STOP_BEACON | (empty) |
 * | TX_FRAME | lp(rate utf8), u8 retryLimit, u8 flags, lp(frame) |
 * | SHUTDOWN | (empty) |
 * | READY | lp(chipId utf8), lp(firmware utf8), lp(devourerVersion utf8) |
 * | RX_FRAME | i16 rssi, u8 rate, u64 tsf, lp(frame) |
 * | STATUS | u32 rx, u32 tx, u32 txErrors, u32 beacons |
 * | LOG | u8 level, lp(text utf8) |
 * | ERROR | u32 code, lp(text utf8) |
 *
 * Decoding is strict: a short header, an unknown type, a declared length that does not match the
 * buffer, a field that overruns the payload, invalid UTF-8 or unread bytes left in the payload are
 * all rejected ([decode] throws [IpcDecodeException], [decodeOrNull] returns null).
 *
 * For stream transports, read [HEADER_SIZE] bytes, ask [payloadLengthOf] how many payload bytes
 * follow, read them, and hand the whole message to [decode].
 */
object IpcCodec {
    /** Size in bytes of the message header. */
    const val HEADER_SIZE = 8

    /** Protocol version sent in HELLO. */
    const val PROTOCOL_VERSION = 1

    /** Largest payload accepted on encode and decode (1 MiB); protects against corrupt lengths. */
    const val MAX_PAYLOAD_LENGTH = 1 shl 20

    /** Encodes [msg] to header plus payload bytes. Throws if the payload exceeds [MAX_PAYLOAD_LENGTH]. */
    fun encode(msg: RadioMessage): ByteArray {
        val writer = PayloadWriter()
        val type = when (msg) {
            is RadioMessage.Hello -> {
                writer.u16(msg.protocolVersion)
                writer.lengthPrefixed(msg.token.encodeToByteArray())
                IpcMessageType.HELLO
            }
            is RadioMessage.OpenDevice -> {
                writer.u16(msg.vid)
                writer.u16(msg.pid)
                IpcMessageType.OPEN_DEVICE
            }
            is RadioMessage.Init -> {
                writer.u8(msg.channel)
                writer.u8(msg.bandwidthMhz)
                writer.u8(msg.txPowerIndex)
                IpcMessageType.INIT
            }
            is RadioMessage.SetIdentity -> {
                writer.raw(msg.mac)
                IpcMessageType.SET_IDENTITY
            }
            is RadioMessage.StartBeacon -> {
                writer.u16(msg.intervalTu)
                writer.lengthPrefixed(msg.beacon)
                IpcMessageType.START_BEACON
            }
            is RadioMessage.UpdateBeacon -> {
                writer.lengthPrefixed(msg.beacon)
                IpcMessageType.UPDATE_BEACON
            }
            RadioMessage.StopBeacon -> IpcMessageType.STOP_BEACON
            is RadioMessage.TxFrame -> {
                writer.lengthPrefixed(msg.rate.encodeToByteArray())
                writer.u8(msg.retryLimit)
                writer.u8(msg.flags)
                writer.lengthPrefixed(msg.frame)
                IpcMessageType.TX_FRAME
            }
            RadioMessage.Shutdown -> IpcMessageType.SHUTDOWN
            is RadioMessage.Ready -> {
                writer.lengthPrefixed(msg.chipId.encodeToByteArray())
                writer.lengthPrefixed(msg.firmware.encodeToByteArray())
                writer.lengthPrefixed(msg.devourerVersion.encodeToByteArray())
                IpcMessageType.READY
            }
            is RadioMessage.RxFrame -> {
                writer.u16(msg.rssiDbm and 0xFFFF)
                writer.u8(msg.rate)
                writer.u64(msg.tsf)
                writer.lengthPrefixed(msg.frame)
                IpcMessageType.RX_FRAME
            }
            is RadioMessage.Status -> {
                writer.u32(msg.rx)
                writer.u32(msg.tx)
                writer.u32(msg.txErrors)
                writer.u32(msg.beacons)
                IpcMessageType.STATUS
            }
            is RadioMessage.Log -> {
                writer.u8(msg.level)
                writer.lengthPrefixed(msg.text.encodeToByteArray())
                IpcMessageType.LOG
            }
            is RadioMessage.Error -> {
                writer.u32(msg.code)
                writer.lengthPrefixed(msg.text.encodeToByteArray())
                IpcMessageType.ERROR
            }
        }
        return writer.finish(type)
    }

    /**
     * Decodes exactly one complete message (header plus payload) from [bytes].
     *
     * @throws IpcDecodeException when [bytes] is not a single well-formed message.
     */
    fun decode(bytes: ByteArray): RadioMessage {
        if (bytes.size < HEADER_SIZE) {
            throw IpcDecodeException("truncated header: size=${bytes.size}")
        }
        val typeId = readU16(bytes, 0)
        val type = IpcMessageType.fromId(typeId)
            ?: throw IpcDecodeException("unknown message type: id=0x${typeId.toString(16)}")
        val length = payloadLengthOf(bytes)
        val available = bytes.size - HEADER_SIZE
        if (length > available) {
            throw IpcDecodeException("declared length exceeds buffer: type=$type length=$length available=$available")
        }
        if (length < available) {
            throw IpcDecodeException("bytes after declared payload: type=$type length=$length available=$available")
        }

        val reader = PayloadReader(bytes, HEADER_SIZE, HEADER_SIZE + length)
        val message: RadioMessage = when (type) {
            IpcMessageType.HELLO -> RadioMessage.Hello(
                protocolVersion = reader.u16(),
                token = reader.string(),
            )
            IpcMessageType.OPEN_DEVICE -> RadioMessage.OpenDevice(vid = reader.u16(), pid = reader.u16())
            IpcMessageType.INIT -> RadioMessage.Init(
                channel = reader.u8(),
                bandwidthMhz = reader.u8(),
                txPowerIndex = reader.u8(),
            )
            IpcMessageType.SET_IDENTITY -> RadioMessage.SetIdentity(mac = reader.raw(RadioMessage.SetIdentity.MAC_SIZE))
            IpcMessageType.START_BEACON -> RadioMessage.StartBeacon(
                intervalTu = reader.u16(),
                beacon = reader.lengthPrefixed(),
            )
            IpcMessageType.UPDATE_BEACON -> RadioMessage.UpdateBeacon(beacon = reader.lengthPrefixed())
            IpcMessageType.STOP_BEACON -> RadioMessage.StopBeacon
            IpcMessageType.TX_FRAME -> RadioMessage.TxFrame(
                rate = reader.string(),
                retryLimit = reader.u8(),
                flags = reader.u8(),
                frame = reader.lengthPrefixed(),
            )
            IpcMessageType.SHUTDOWN -> RadioMessage.Shutdown
            IpcMessageType.READY -> RadioMessage.Ready(
                chipId = reader.string(),
                firmware = reader.string(),
                devourerVersion = reader.string(),
            )
            IpcMessageType.RX_FRAME -> RadioMessage.RxFrame(
                rssiDbm = reader.i16(),
                rate = reader.u8(),
                tsf = reader.u64(),
                frame = reader.lengthPrefixed(),
            )
            IpcMessageType.STATUS -> RadioMessage.Status(
                rx = reader.u32(),
                tx = reader.u32(),
                txErrors = reader.u32(),
                beacons = reader.u32(),
            )
            IpcMessageType.LOG -> RadioMessage.Log(level = reader.u8(), text = reader.string())
            IpcMessageType.ERROR -> RadioMessage.Error(code = reader.u32(), text = reader.string())
        }
        reader.requireFullyConsumed(type)
        return message
    }

    /** Like [decode] but returns null instead of throwing when [bytes] is malformed. */
    fun decodeOrNull(bytes: ByteArray): RadioMessage? =
        try {
            decode(bytes)
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * Returns the payload length declared by the header at the start of [header] (at least
     * [HEADER_SIZE] bytes). Used by stream transports to know how many bytes to read next.
     *
     * @throws IpcDecodeException when the header is short or declares more than [MAX_PAYLOAD_LENGTH].
     */
    fun payloadLengthOf(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) {
            throw IpcDecodeException("truncated header: size=${header.size}")
        }
        val length = readU32(header, 4)
        if (length > MAX_PAYLOAD_LENGTH) {
            throw IpcDecodeException("declared length above maximum: length=$length max=$MAX_PAYLOAD_LENGTH")
        }
        return length.toInt()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

/** Growable little-endian writer that reserves the header in front of the payload. */
private class PayloadWriter {
    private var buffer = ByteArray(IpcCodec.HEADER_SIZE + INITIAL_PAYLOAD_CAPACITY)
    private var size = IpcCodec.HEADER_SIZE

    fun u8(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    fun u16(value: Int) {
        ensureCapacity(2)
        buffer[size++] = value.toByte()
        buffer[size++] = (value ushr 8).toByte()
    }

    fun u32(value: UInt) {
        ensureCapacity(4)
        val v = value.toInt()
        for (shift in 0 until 32 step 8) {
            buffer[size++] = (v ushr shift).toByte()
        }
    }

    fun u64(value: ULong) {
        ensureCapacity(8)
        val v = value.toLong()
        for (shift in 0 until 64 step 8) {
            buffer[size++] = (v ushr shift).toByte()
        }
    }

    fun raw(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        bytes.copyInto(buffer, destinationOffset = size)
        size += bytes.size
    }

    fun lengthPrefixed(bytes: ByteArray) {
        u32(bytes.size.toUInt())
        raw(bytes)
    }

    /** Fills in the header for [type] and returns the finished message. */
    fun finish(type: IpcMessageType): ByteArray {
        val payloadLength = size - IpcCodec.HEADER_SIZE
        require(payloadLength <= IpcCodec.MAX_PAYLOAD_LENGTH) {
            "payload too large: type=$type length=$payloadLength max=${IpcCodec.MAX_PAYLOAD_LENGTH}"
        }
        buffer[0] = type.id.toByte()
        buffer[1] = (type.id ushr 8).toByte()
        // bytes 2..3: reserved flags, left as 0
        for (i in 0 until 4) {
            buffer[4 + i] = (payloadLength ushr (8 * i)).toByte()
        }
        return buffer.copyOf(size)
    }

    private fun ensureCapacity(extra: Int) {
        val needed = size + extra
        if (needed <= buffer.size) return
        var newCapacity = buffer.size * 2
        while (newCapacity < needed) newCapacity *= 2
        buffer = buffer.copyOf(newCapacity)
    }

    private companion object {
        const val INITIAL_PAYLOAD_CAPACITY = 64
    }
}

/** Bounds-checked little-endian reader over one message's payload. */
private class PayloadReader(
    private val data: ByteArray,
    start: Int,
    private val end: Int,
) {
    private var position = start

    fun u8(): Int {
        need(1)
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val value = (data[position].toInt() and 0xFF) or ((data[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value
    }

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): UInt {
        need(4)
        var value = 0
        for (i in 0 until 4) {
            value = value or ((data[position + i].toInt() and 0xFF) shl (8 * i))
        }
        position += 4
        return value.toUInt()
    }

    fun u64(): ULong {
        need(8)
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((data[position + i].toLong() and 0xFF) shl (8 * i))
        }
        position += 8
        return value.toULong()
    }

    fun raw(count: Int): ByteArray {
        need(count)
        val result = data.copyOfRange(position, position + count)
        position += count
        return result
    }

    fun lengthPrefixed(): ByteArray {
        val count = u32()
        if (count > (end - position).toUInt()) {
            throw IpcDecodeException("field length overruns payload: length=$count remaining=${end - position}")
        }
        return raw(count.toInt())
    }

    fun string(): String {
        val bytes = lengthPrefixed()
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw IpcDecodeException("invalid UTF-8 text: length=${bytes.size}")
        }
    }

    fun requireFullyConsumed(type: IpcMessageType) {
        if (position != end) {
            throw IpcDecodeException("unread payload bytes: type=$type unread=${end - position}")
        }
    }

    private fun need(count: Int) {
        if (end - position < count) {
            throw IpcDecodeException("payload too short: need=$count remaining=${end - position}")
        }
    }
}
