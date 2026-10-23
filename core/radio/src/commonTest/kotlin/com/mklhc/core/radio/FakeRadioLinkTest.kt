package com.mklhc.core.radio

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for [FakeRadioLink], the in-memory [RadioLink] test double: command recording, defensive
 * copies, pushing frames and events into the flows, and failure injection.
 */
class FakeRadioLinkTest {

    @Test
    fun `records every command in call order`() = runTest {
        val link = FakeRadioLink()

        link.openDevice(vid = 0x0bda, pid = 0xc812)
        link.init(channel = 6, bandwidthMhz = 20, txPowerIndex = 30)
        link.setIdentity(byteArrayOf(2, 0, 0, 0, 0, 1))
        link.startBeacon(intervalTu = 100, beacon = byteArrayOf(1, 2))
        link.updateBeacon(byteArrayOf(3))
        link.txFrame(rate = "6M", retryLimit = 7, flags = 0, frame = byteArrayOf(0x80.toByte()))
        link.stopBeacon()
        link.listDevices(requestId = 12u)
        link.shutdown()

        assertThat(link.sentMessages).isEqualTo(
            listOf(
                RadioMessage.OpenDevice(0x0bda, 0xc812),
                RadioMessage.Init(6, 20, 30),
                RadioMessage.SetIdentity(byteArrayOf(2, 0, 0, 0, 0, 1)),
                RadioMessage.StartBeacon(100, byteArrayOf(1, 2)),
                RadioMessage.UpdateBeacon(byteArrayOf(3)),
                RadioMessage.TxFrame("6M", 7, 0, byteArrayOf(0x80.toByte())),
                RadioMessage.StopBeacon,
                RadioMessage.ListDevices(12u),
                RadioMessage.Shutdown,
            ),
        )
    }

    @Test
    fun `listDevices answers with nothing unless a device list reply is configured, echoing the request id`() = runTest {
        val link = FakeRadioLink()
        val device = UsbDeviceEntry(vid = 0x2357, pid = 0x012d, bus = 1, address = 4, deviceClass = 0, access = UsbAccess.OPENABLE)

        link.events.test {
            link.listDevices(requestId = 1u)
            expectNoEvents()

            link.deviceListReply = listOf(device)
            link.listDevices(requestId = 2u)
            assertThat(awaitItem()).isEqualTo(RadioMessage.DeviceList(requestId = 2u, devices = listOf(device)))
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(link.sentOfType<RadioMessage.ListDevices>()).hasSize(2)
    }

    @Test
    fun `a configured failure also stops the device list reply`() = runTest {
        val link = FakeRadioLink()
        link.deviceListReply = emptyList()
        link.failure = IllegalStateException("radiod is not connected")

        link.events.test {
            assertFailsWith<IllegalStateException> { link.listDevices(requestId = 1u) }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sentOfType filters recorded commands`() = runTest {
        val link = FakeRadioLink()

        link.txFrame("6M", 1, 0, byteArrayOf(1))
        link.stopBeacon()
        link.txFrame("12M", 2, 0, byteArrayOf(2))

        assertThat(link.sentOfType<RadioMessage.TxFrame>().map { it.rate }).isEqualTo(listOf("6M", "12M"))
    }

    @Test
    fun `byte arrays are copied so later mutation does not change the record`() = runTest {
        val link = FakeRadioLink()
        val frame = byteArrayOf(1, 2, 3)

        link.txFrame("6M", 0, 0, frame)
        frame[0] = 99

        assertThat(link.sentOfType<RadioMessage.TxFrame>().single().frame.toList())
            .isEqualTo(listOf<Byte>(1, 2, 3))
    }

    @Test
    fun `clearSentMessages forgets the record`() = runTest {
        val link = FakeRadioLink()
        link.stopBeacon()

        link.clearSentMessages()

        assertThat(link.sentMessages).isEmpty()
    }

    @Test
    fun `emitted frames reach collectors of rxFrames`() = runTest {
        val link = FakeRadioLink()
        val frame = RadioFrame(rssiDbm = -50, rate = 12, tsf = 1uL, frame = byteArrayOf(0x40))

        link.rxFrames.test {
            link.emitRxFrame(frame)
            assertThat(awaitItem()).isEqualTo(frame)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emitted events reach collectors of events`() = runTest {
        val link = FakeRadioLink()
        val ready = RadioMessage.Ready(chipId = "RTL8812CU", firmware = "v1", devourerVersion = "0.1")
        val error = RadioMessage.Error(code = 5u, text = "boom")

        link.events.test {
            link.emitEvent(ready)
            link.emitEvent(error)
            assertThat(awaitItem()).isEqualTo(ready)
            assertThat(awaitItem()).isEqualTo(error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a configured failure makes commands throw and skips recording`() = runTest {
        val link = FakeRadioLink()
        link.failure = IllegalStateException("radiod is not connected")

        assertFailsWith<IllegalStateException> { link.stopBeacon() }
        assertThat(link.sentMessages).hasSize(0)

        link.failure = null
        link.stopBeacon()
        assertThat(link.sentMessages).hasSize(1)
    }
}
