package com.mklhc.core.radio

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests for [requestDeviceList], the LIST_DEVICES request / DEVICE_LIST reply helper over [RadioLink]: each request
 * carries a new id, only the reply with that id answers it (a late reply to an earlier request is ignored), and a
 * missing reply times out as null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListRequestTest {

    private val adapter = UsbDeviceEntry(vid = 0x2357, pid = 0x012d, bus = 1, address = 4, deviceClass = 0, access = UsbAccess.OPENABLE)
    private val mouse = UsbDeviceEntry(vid = 0x046d, pid = 0xc077, bus = 1, address = 2, deviceClass = 0, access = UsbAccess.NOT_PROBED)

    /** The request id of the [index]-th LIST_DEVICES [link] has sent, waiting for it to be sent. */
    private suspend fun requestIdOf(link: FakeRadioLink, index: Int): UInt =
        link.sentMessagesFlow.first { it.size > index }.filterIsInstance<RadioMessage.ListDevices>()[index].requestId

    @Test
    fun `returns the devices of the DEVICE_LIST that answers the request`() = runTest {
        val link = FakeRadioLink()
        link.deviceListReply = listOf(adapter, mouse)

        val devices = link.requestDeviceList(timeoutMs = 1_000)

        assertThat(devices).isEqualTo(listOf(adapter, mouse))
        assertThat(link.sentOfType<RadioMessage.ListDevices>().size).isEqualTo(1)
    }

    @Test
    fun `every request carries a new id`() = runTest {
        val link = FakeRadioLink()
        link.deviceListReply = emptyList()

        link.requestDeviceList(timeoutMs = 1_000)
        link.requestDeviceList(timeoutMs = 1_000)

        val ids = link.sentOfType<RadioMessage.ListDevices>().map { it.requestId }
        assertThat(ids[1]).isNotEqualTo(ids[0])
    }

    @Test
    fun `waits past other events and other ids for its own DEVICE_LIST`() = runTest {
        val link = FakeRadioLink()

        val request = async { link.requestDeviceList(timeoutMs = 5_000) }
        val id = requestIdOf(link, 0)
        link.emitEvent(RadioMessage.Log(RadioMessage.Log.LEVEL_INFO, "scanning"))
        link.emitEvent(RadioMessage.Status(rx = 1u, tx = 0u, txErrors = 0u, beacons = 0u))
        link.emitEvent(RadioMessage.DeviceList(requestId = id + 1u, devices = listOf(mouse)))
        link.emitEvent(RadioMessage.DeviceList(requestId = id, devices = listOf(adapter)))

        assertThat(request.await()).isEqualTo(listOf(adapter))
    }

    @Test
    fun `a late reply to a timed out request does not answer the next one`() = runTest {
        val link = FakeRadioLink()
        assertThat(link.requestDeviceList(timeoutMs = 1_000)).isNull()
        val staleId = requestIdOf(link, 0)

        val request = async { link.requestDeviceList(timeoutMs = 5_000) }
        val freshId = requestIdOf(link, 1)
        link.emitEvent(RadioMessage.DeviceList(requestId = staleId, devices = listOf(mouse)))
        link.emitEvent(RadioMessage.DeviceList(requestId = freshId, devices = listOf(adapter)))

        assertThat(request.await()).isEqualTo(listOf(adapter))
    }

    @Test
    fun `an empty DEVICE_LIST is an empty list, not a timeout`() = runTest {
        val link = FakeRadioLink()
        link.deviceListReply = emptyList()

        val devices = link.requestDeviceList(timeoutMs = 1_000)

        assertThat(devices).isEqualTo(emptyList())
    }

    @Test
    fun `returns null when no DEVICE_LIST arrives in time`() = runTest {
        val link = FakeRadioLink()

        val devices = link.requestDeviceList(timeoutMs = 2_000)

        assertThat(devices).isNull()
        assertThat(currentTime).isEqualTo(2_000L)
        assertThat(link.sentOfType<RadioMessage.ListDevices>().size).isEqualTo(1)
    }

    @Test
    fun `a send failure propagates and leaves nothing recorded`() = runTest {
        val link = FakeRadioLink()
        link.failure = IllegalStateException("radiod is not connected")

        assertFailsWith<IllegalStateException> { link.requestDeviceList(timeoutMs = 1_000) }
        assertThat(link.sentMessages).isEmpty()
    }
}
