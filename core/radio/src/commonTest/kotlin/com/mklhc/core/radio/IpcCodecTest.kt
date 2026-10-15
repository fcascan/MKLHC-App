package com.mklhc.core.radio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [IpcCodec], the little-endian codec of the radiod IPC protocol.
 *
 * Covers a round-trip for every message type, byte-exact layouts for the header and a few payloads,
 * and the rejection paths for malformed input (truncation, unknown type, bad lengths, bad UTF-8).
 */
class IpcCodecTest {

    private fun roundTrip(message: RadioMessage) {
        val decoded = IpcCodec.decode(IpcCodec.encode(message))
        assertThat(decoded).isEqualTo(message)
    }

    /** Builds a raw message with an arbitrary header, for the malformed-input tests. */
    private fun rawMessage(type: Int, flags: Int = 0, declaredLength: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(IpcCodec.HEADER_SIZE)
        header[0] = type.toByte()
        header[1] = (type ushr 8).toByte()
        header[2] = flags.toByte()
        header[3] = (flags ushr 8).toByte()
        for (i in 0 until 4) {
            header[4 + i] = (declaredLength ushr (8 * i)).toByte()
        }
        return header + payload
    }

    // ---- round-trips, one per message type ----

    @Test
    fun `encodes and decodes a TX_FRAME round-trip`() {
        val msg = RadioMessage.TxFrame(
            rate = "6M",
            retryLimit = 7,
            flags = 0,
            frame = byteArrayOf(0x80.toByte(), 0, 0, 0),
        )
        val bytes = IpcCodec.encode(msg)
        val decoded = IpcCodec.decode(bytes)
        assertThat(decoded).isEqualTo(msg)
    }

    @Test
    fun `HELLO round-trips`() = roundTrip(RadioMessage.Hello(protocolVersion = 1, token = "demo-token"))

    @Test
    fun `OPEN_DEVICE round-trips`() = roundTrip(RadioMessage.OpenDevice(vid = 0x0bda, pid = 0xc812))

    @Test
    fun `INIT round-trips`() =
        roundTrip(RadioMessage.Init(channel = 6, bandwidthMhz = 20, txPowerIndex = 42))

    @Test
    fun `SET_IDENTITY round-trips`() = roundTrip(
        RadioMessage.SetIdentity(
            mac = byteArrayOf(0x02, 0x4d, 0x4b, 0x4c, 0x48, 0x43),
        ),
    )

    @Test
    fun `START_BEACON round-trips`() = roundTrip(
        RadioMessage.StartBeacon(
            intervalTu = 100,
            beacon = ByteArray(96) { (it * 3).toByte() },
        ),
    )

    @Test
    fun `UPDATE_BEACON round-trips`() =
        roundTrip(RadioMessage.UpdateBeacon(beacon = byteArrayOf(0x80.toByte(), 0, 1, 2, 3)))

    @Test
    fun `STOP_BEACON round-trips`() = roundTrip(RadioMessage.StopBeacon)

    @Test
    fun `SHUTDOWN round-trips`() = roundTrip(RadioMessage.Shutdown)

    @Test
    fun `READY round-trips`() = roundTrip(
        RadioMessage.Ready(chipId = "RTL8812CU", firmware = "v9.4", devourerVersion = "0.3.1"),
    )

    @Test
    fun `RX_FRAME round-trips`() = roundTrip(
        RadioMessage.RxFrame(
            rssiDbm = -52,
            rate = 12,
            tsf = 0x0102030405060708uL,
            frame = byteArrayOf(0x40, 0x00, 0x3a, 0x01),
        ),
    )

    @Test
    fun `STATUS round-trips`() = roundTrip(
        RadioMessage.Status(rx = 1_000u, tx = 2_000u, txErrors = 3u, beacons = 4_000_000_000u),
    )

    @Test
    fun `LOG round-trips`() = roundTrip(RadioMessage.Log(level = 2, text = "usb: transfer timeout"))

    @Test
    fun `ERROR round-trips`() = roundTrip(RadioMessage.Error(code = 0xDEADBEEFu, text = "firmware upload failed"))

    // ---- value edge cases ----

    @Test
    fun `non ASCII text survives a round-trip`() {
        roundTrip(RadioMessage.Hello(protocolVersion = 1, token = "tóken-漢字"))
        roundTrip(RadioMessage.Log(level = 1, text = "café → ok"))
    }

    @Test
    fun `empty variable length fields survive a round-trip`() {
        roundTrip(RadioMessage.Hello(protocolVersion = 1, token = ""))
        roundTrip(RadioMessage.UpdateBeacon(beacon = ByteArray(0)))
        roundTrip(RadioMessage.TxFrame(rate = "", retryLimit = 0, flags = 0, frame = ByteArray(0)))
        roundTrip(RadioMessage.RxFrame(rssiDbm = 0, rate = 0, tsf = 0uL, frame = ByteArray(0)))
    }

    @Test
    fun `extreme numeric values survive a round-trip`() {
        roundTrip(RadioMessage.RxFrame(rssiDbm = Short.MIN_VALUE.toInt(), rate = 255, tsf = ULong.MAX_VALUE, frame = byteArrayOf(1)))
        roundTrip(RadioMessage.RxFrame(rssiDbm = Short.MAX_VALUE.toInt(), rate = 0, tsf = 0uL, frame = byteArrayOf(1)))
        roundTrip(RadioMessage.Status(rx = UInt.MAX_VALUE, tx = UInt.MAX_VALUE, txErrors = UInt.MAX_VALUE, beacons = UInt.MAX_VALUE))
        roundTrip(RadioMessage.OpenDevice(vid = 0xFFFF, pid = 0xFFFF))
        roundTrip(RadioMessage.TxFrame(rate = "54M", retryLimit = 255, flags = 255, frame = byteArrayOf(0)))
    }

    @Test
    fun `a large frame survives a round-trip`() =
        roundTrip(RadioMessage.UpdateBeacon(beacon = ByteArray(8_000) { (it % 251).toByte() }))

    // ---- byte-exact layouts ----

    @Test
    fun `header is type flags and length in little-endian order`() {
        val bytes = IpcCodec.encode(RadioMessage.StopBeacon)

        assertContentEquals(
            byteArrayOf(0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            bytes,
        )
    }

    @Test
    fun `TX_FRAME has the documented byte layout`() {
        val bytes = IpcCodec.encode(
            RadioMessage.TxFrame(
                rate = "6M",
                retryLimit = 7,
                flags = 0,
                frame = byteArrayOf(0x80.toByte(), 0, 0, 0),
            ),
        )

        assertContentEquals(
            byteArrayOf(
                // header: type 0x0008, flags 0, length 16
                0x08, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00,
                // rate: u32 length 2, "6M"
                0x02, 0x00, 0x00, 0x00, 0x36, 0x4d,
                // retryLimit u8, flags u8
                0x07, 0x00,
                // frame: u32 length 4, bytes
                0x04, 0x00, 0x00, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00,
            ),
            bytes,
        )
    }

    @Test
    fun `RX_FRAME has the documented byte layout`() {
        val bytes = IpcCodec.encode(
            RadioMessage.RxFrame(
                rssiDbm = -52,
                rate = 12,
                tsf = 0x0102030405060708uL,
                frame = byteArrayOf(0x40, 0x01),
            ),
        )

        assertContentEquals(
            byteArrayOf(
                // header: type 0x0102, flags 0, length 17
                0x02, 0x01, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00,
                // rssi i16 (-52 = 0xFFCC), rate u8
                0xCC.toByte(), 0xFF.toByte(), 0x0c,
                // tsf u64 little-endian
                0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
                // frame: u32 length 2, bytes
                0x02, 0x00, 0x00, 0x00, 0x40, 0x01,
            ),
            bytes,
        )
    }

    @Test
    fun `STATUS has the documented byte layout`() {
        val bytes = IpcCodec.encode(RadioMessage.Status(rx = 1u, tx = 2u, txErrors = 3u, beacons = 0x01020304u))

        assertContentEquals(
            byteArrayOf(
                // header: type 0x0103, flags 0, length 16
                0x03, 0x01, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00,
                0x02, 0x00, 0x00, 0x00,
                0x03, 0x00, 0x00, 0x00,
                0x04, 0x03, 0x02, 0x01,
            ),
            bytes,
        )
    }

    @Test
    fun `every message type uses a distinct type id`() {
        val samples = listOf(
            RadioMessage.Hello(1, "t"),
            RadioMessage.OpenDevice(1, 2),
            RadioMessage.Init(1, 20, 0),
            RadioMessage.SetIdentity(ByteArray(6)),
            RadioMessage.StartBeacon(100, ByteArray(1)),
            RadioMessage.UpdateBeacon(ByteArray(1)),
            RadioMessage.StopBeacon,
            RadioMessage.TxFrame("6M", 0, 0, ByteArray(1)),
            RadioMessage.Shutdown,
            RadioMessage.Ready("c", "f", "d"),
            RadioMessage.RxFrame(0, 0, 0uL, ByteArray(1)),
            RadioMessage.Status(0u, 0u, 0u, 0u),
            RadioMessage.Log(0, "l"),
            RadioMessage.Error(0u, "e"),
        )

        val ids = samples.map { message ->
            val bytes = IpcCodec.encode(message)
            (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        }

        assertThat(ids.toSet().size).isEqualTo(samples.size)
    }

    @Test
    fun `decode ignores the reserved header flags`() {
        val payload = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x36, 0x4d, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00)
        val bytes = rawMessage(type = 0x0008, flags = 0xABCD, declaredLength = payload.size.toLong(), payload = payload)

        val decoded = IpcCodec.decode(bytes)

        assertThat(decoded).isEqualTo(RadioMessage.TxFrame(rate = "6M", retryLimit = 7, flags = 0, frame = ByteArray(0)))
    }

    // ---- malformed input ----

    @Test
    fun `decode rejects a truncated header`() {
        assertThat(IpcCodec.decodeOrNull(byteArrayOf(1, 2, 3))).isNull()
        assertFailsWith<IpcDecodeException> { IpcCodec.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `decode rejects an empty buffer`() {
        assertThat(IpcCodec.decodeOrNull(ByteArray(0))).isNull()
    }

    @Test
    fun `decode throws on an unknown type id`() {
        val bytes = rawMessage(type = 0x7777, declaredLength = 0, payload = ByteArray(0))

        assertFailsWith<IpcDecodeException> { IpcCodec.decode(bytes) }
        assertThat(IpcCodec.decodeOrNull(bytes)).isNull()
    }

    @Test
    fun `decodeOrNull rejects a declared length longer than the buffer`() {
        val bytes = rawMessage(type = 0x0006, declaredLength = 100, payload = ByteArray(4))

        assertThat(IpcCodec.decodeOrNull(bytes)).isNull()
        assertFailsWith<IpcDecodeException> { IpcCodec.decode(bytes) }
    }

    @Test
    fun `decode rejects bytes after the declared payload`() {
        val valid = IpcCodec.encode(RadioMessage.StopBeacon)

        assertThat(IpcCodec.decodeOrNull(valid + byteArrayOf(0))).isNull()
    }

    @Test
    fun `decode rejects a payload with unread bytes`() {
        val bytes = rawMessage(type = 0x0007, declaredLength = 1, payload = byteArrayOf(0))

        assertThat(IpcCodec.decodeOrNull(bytes)).isNull()
    }

    @Test
    fun `decode rejects a payload shorter than its fixed fields`() {
        // INIT needs 3 bytes.
        val bytes = rawMessage(type = 0x0003, declaredLength = 2, payload = byteArrayOf(6, 20))

        assertThat(IpcCodec.decodeOrNull(bytes)).isNull()
    }

    @Test
    fun `decode rejects a field length that overruns the payload`() {
        // UPDATE_BEACON declares 50 beacon bytes but only carries 2.
        val payload = byteArrayOf(50, 0, 0, 0, 1, 2)
        val bytes = rawMessage(type = 0x0006, declaredLength = payload.size.toLong(), payload = payload)

        assertThat(IpcCodec.decodeOrNull(bytes)).isNull()
    }

    @Test
    fun `decode rejects a SET_IDENTITY payload that is not 6 bytes`() {
        val bytes = rawMessage(type = 0x0004, declaredLength = 5, payload = ByteArray(5))

        assertThat(IpcCodec.decodeOrNull(bytes)).isNull()
    }

    @Test
    fun `decode rejects invalid UTF-8 text`() {
        // HELLO: version 1, token length 2, token bytes 0xC3 0x28 (invalid sequence).
        val payload = byteArrayOf(1, 0, 2, 0, 0, 0, 0xC3.toByte(), 0x28)
        val bytes = rawMessage(type = 0x0001, declaredLength = payload.size.toLong(), payload = payload)

        assertThat(IpcCodec.decodeOrNull(bytes)).isNull()
    }

    @Test
    fun `decode rejects a declared length above the maximum payload`() {
        val bytes = rawMessage(type = 0x0006, declaredLength = 0xFFFFFFFFL, payload = ByteArray(0))

        assertThat(IpcCodec.decodeOrNull(bytes)).isNull()
    }

    @Test
    fun `encode rejects a payload above the maximum size`() {
        val tooBig = RadioMessage.UpdateBeacon(beacon = ByteArray(IpcCodec.MAX_PAYLOAD_LENGTH))

        assertFailsWith<IllegalArgumentException> { IpcCodec.encode(tooBig) }
    }

    // ---- stream helpers used by the desktop transport ----

    @Test
    fun `payloadLengthOf reads the declared length from a header`() {
        val encoded = IpcCodec.encode(RadioMessage.UpdateBeacon(beacon = ByteArray(10)))

        assertThat(IpcCodec.payloadLengthOf(encoded)).isEqualTo(14)
    }

    @Test
    fun `payloadLengthOf rejects a short header and an oversized length`() {
        assertFailsWith<IpcDecodeException> { IpcCodec.payloadLengthOf(ByteArray(7)) }
        assertFailsWith<IpcDecodeException> {
            IpcCodec.payloadLengthOf(rawMessage(type = 1, declaredLength = 0xFFFFFFFFL, payload = ByteArray(0)))
        }
    }

    @Test
    fun `decodeOrNull returns the message for a valid buffer`() {
        val msg = RadioMessage.Hello(protocolVersion = 1, token = "abc")

        assertThat(IpcCodec.decodeOrNull(IpcCodec.encode(msg))).isNotNull()
    }
}
