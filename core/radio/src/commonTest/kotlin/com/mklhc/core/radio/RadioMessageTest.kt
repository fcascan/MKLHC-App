package com.mklhc.core.radio

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for the value semantics of [RadioMessage] and [RadioFrame]: content-based equality for
 * byte arrays, range validation on construction, and the RX_FRAME to [RadioFrame] conversion.
 */
class RadioMessageTest {

    @Test
    fun `messages with equal bytes are equal and share a hash code`() {
        val a = RadioMessage.TxFrame("6M", 7, 0, byteArrayOf(1, 2, 3))
        val b = RadioMessage.TxFrame("6M", 7, 0, byteArrayOf(1, 2, 3))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `messages with different bytes are not equal`() {
        val a = RadioMessage.UpdateBeacon(byteArrayOf(1, 2, 3))
        val b = RadioMessage.UpdateBeacon(byteArrayOf(1, 2, 4))

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `messages of different types are not equal`() {
        assertThat<Any>(RadioMessage.StopBeacon).isNotEqualTo(RadioMessage.Shutdown)
    }

    @Test
    fun `radio frames compare by content`() {
        val a = RadioFrame(rssiDbm = -40, rate = 12, tsf = 99uL, frame = byteArrayOf(9, 8, 7))
        val b = RadioFrame(rssiDbm = -40, rate = 12, tsf = 99uL, frame = byteArrayOf(9, 8, 7))
        val c = RadioFrame(rssiDbm = -41, rate = 12, tsf = 99uL, frame = byteArrayOf(9, 8, 7))

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(c)
    }

    @Test
    fun `RxFrame converts to a RadioFrame`() {
        val rx = RadioMessage.RxFrame(rssiDbm = -60, rate = 24, tsf = 1234uL, frame = byteArrayOf(1, 2))

        assertThat(rx.toRadioFrame())
            .isEqualTo(RadioFrame(rssiDbm = -60, rate = 24, tsf = 1234uL, frame = byteArrayOf(1, 2)))
    }

    @Test
    fun `out of range numeric fields are rejected on construction`() {
        assertFailsWith<IllegalArgumentException> { RadioMessage.Hello(protocolVersion = 0x10000, token = "t") }
        assertFailsWith<IllegalArgumentException> { RadioMessage.OpenDevice(vid = -1, pid = 0) }
        assertFailsWith<IllegalArgumentException> { RadioMessage.OpenDevice(vid = 0, pid = 0x10000) }
        assertFailsWith<IllegalArgumentException> {
            RadioMessage.Init(channel = 256, bandwidthMhz = 20, txPowerIndex = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RadioMessage.Init(channel = 6, bandwidthMhz = -1, txPowerIndex = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RadioMessage.StartBeacon(intervalTu = 0x10000, beacon = ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> {
            RadioMessage.TxFrame("6M", retryLimit = 256, flags = 0, frame = ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> {
            RadioMessage.TxFrame("6M", retryLimit = 0, flags = -1, frame = ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> {
            RadioMessage.RxFrame(rssiDbm = 40_000, rate = 0, tsf = 0uL, frame = ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> {
            RadioMessage.RxFrame(rssiDbm = 0, rate = 256, tsf = 0uL, frame = ByteArray(1))
        }
        assertFailsWith<IllegalArgumentException> { RadioMessage.Log(level = 256, text = "x") }
    }

    @Test
    fun `SET_IDENTITY requires exactly 6 bytes`() {
        assertFailsWith<IllegalArgumentException> { RadioMessage.SetIdentity(ByteArray(5)) }
        assertFailsWith<IllegalArgumentException> { RadioMessage.SetIdentity(ByteArray(7)) }
        assertThat(RadioMessage.SetIdentity(ByteArray(6)).mac.size).isEqualTo(6)
    }

    @Test
    fun `toString never dumps frame bytes`() {
        val text = RadioMessage.TxFrame("6M", 7, 0, ByteArray(1500)).toString()

        assertThat(text).isEqualTo("TxFrame(rate=6M, retryLimit=7, flags=0, frame=1500 bytes)")
    }
}
