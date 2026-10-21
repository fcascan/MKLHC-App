package com.mklhc.core.data.radio

import app.cash.turbine.test
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.netstack.EtherType
import com.mklhc.core.netstack.LinkPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FakeKartRadioAp]: the controllable stand-in for the radio + access-point layer. These tests pin the
 * behaviour the session-repository tests will rely on: scripted adapter and association state, recorded
 * lifecycle and beacon calls, the station dropped when the beacon switches networks, injectable failures,
 * and a frame link with a station on the other end.
 */
class FakeKartRadioApTest {

    @Test
    fun adapterIsReadyAndStationAbsentByDefault() {
        val radio = FakeKartRadioAp()
        assertEquals(AdapterState.READY, radio.adapterState.value)
        assertFalse(radio.associatedStation.value)
        assertFalse(radio.isStarted)
        assertNull(radio.currentBeacon)
        assertEquals(emptyList(), radio.beaconCalls)
    }

    @Test
    fun initialAdapterStateCanBeChosen() {
        assertEquals(AdapterState.ABSENT, FakeKartRadioAp(AdapterState.ABSENT).adapterState.value)
        assertEquals(AdapterState.PERMISSION_REQUIRED, FakeKartRadioAp(AdapterState.PERMISSION_REQUIRED).adapterState.value)
    }

    @Test
    fun adapterStateChangesAreObservable() = runTest {
        val radio = FakeKartRadioAp(AdapterState.ABSENT)
        radio.adapterState.test {
            assertEquals(AdapterState.ABSENT, awaitItem())
            radio.setAdapterState(AdapterState.PERMISSION_REQUIRED)
            assertEquals(AdapterState.PERMISSION_REQUIRED, awaitItem())
            radio.setAdapterState(AdapterState.READY)
            assertEquals(AdapterState.READY, awaitItem())
        }
    }

    @Test
    fun permissionRequestsAreCountedAndCanAutoGrant() {
        val manual = FakeKartRadioAp(AdapterState.PERMISSION_REQUIRED)
        manual.requestAdapterPermission()
        assertEquals(1, manual.permissionRequestCount)
        assertEquals(AdapterState.PERMISSION_REQUIRED, manual.adapterState.value, "the test decides when it is granted")

        val auto = FakeKartRadioAp(AdapterState.PERMISSION_REQUIRED, grantPermissionOnRequest = true)
        auto.requestAdapterPermission()
        assertEquals(AdapterState.READY, auto.adapterState.value)

        val alreadyReady = FakeKartRadioAp(AdapterState.READY, grantPermissionOnRequest = true)
        alreadyReady.requestAdapterPermission()
        assertEquals(AdapterState.READY, alreadyReady.adapterState.value)

        val absent = FakeKartRadioAp(AdapterState.ABSENT, grantPermissionOnRequest = true)
        absent.requestAdapterPermission()
        assertEquals(AdapterState.ABSENT, absent.adapterState.value, "permission cannot be granted to an absent adapter")
    }

    @Test
    fun startAndStopAreRecorded() = runTest {
        val radio = FakeKartRadioAp()
        radio.start()
        assertTrue(radio.isStarted)
        assertEquals(1, radio.startCount)

        radio.stop()
        assertFalse(radio.isStarted)
        assertEquals(1, radio.stopCount)

        radio.stop()
        assertEquals(2, radio.stopCount, "stop() is idempotent but still counted")
    }

    @Test
    fun startFailureIsThrownAndLeavesTheRadioStopped() = runTest {
        val radio = FakeKartRadioAp()
        radio.startFailure = IllegalStateException("helper did not come up")

        val failure = assertFailsWith<IllegalStateException> { radio.start() }
        assertEquals("helper did not come up", failure.message)
        assertFalse(radio.isStarted)
        assertEquals(1, radio.startCount, "the attempt is still counted")
    }

    @Test
    fun beaconRecordsEveryCallAndTheCurrentNetwork() = runTest {
        val radio = FakeKartRadioAp()
        radio.start()

        val pairing = PairingNetwork(ssid = "mklhc-pair_abc", seed = ByteArray(0x10) { it.toByte() }, channel = 6)
        radio.beacon(KartNetworkKind.PAIRING, pairing)
        assertEquals(KartNetworkKind.PAIRING, radio.currentBeacon)
        assertEquals(pairing, radio.lastPairingNetwork, "the pairing network the beacon carried is recorded")

        radio.beacon(KartNetworkKind.GAME)
        assertEquals(KartNetworkKind.GAME, radio.currentBeacon)
        assertEquals(listOf(KartNetworkKind.PAIRING, KartNetworkKind.GAME), radio.beaconCalls)
    }

    @Test
    fun beaconingAnotherNetworkDropsTheStation() = runTest {
        val radio = FakeKartRadioAp()
        radio.start()
        radio.beacon(KartNetworkKind.GAME)
        radio.setStationAssociated(true)

        val pairing = PairingNetwork(ssid = "mklhc-pair_abc", seed = ByteArray(0x10) { it.toByte() }, channel = 6)
        radio.beacon(KartNetworkKind.PAIRING, pairing)
        assertFalse(radio.associatedStation.value, "switching to the pairing network drops the station")

        radio.setStationAssociated(true)
        radio.beacon(KartNetworkKind.PAIRING, PairingNetwork(ssid = "mklhc-pair_xyz", seed = ByteArray(0x10), channel = 6))
        assertFalse(radio.associatedStation.value, "another pairing network is another network too")

        radio.setStationAssociated(true)
        radio.beacon(KartNetworkKind.GAME)
        assertFalse(radio.associatedStation.value, "switching back to the game network drops the station")
    }

    @Test
    fun beaconingTheServedNetworkAgainKeepsTheStation() = runTest {
        val radio = FakeKartRadioAp()
        radio.start()
        radio.beacon(KartNetworkKind.GAME)
        radio.setStationAssociated(true)

        radio.beacon(KartNetworkKind.GAME)
        assertTrue(radio.associatedStation.value, "the game network already served is a no-op")

        val pairing = PairingNetwork(ssid = "mklhc-pair_abc", seed = ByteArray(0x10) { it.toByte() }, channel = 6)
        radio.beacon(KartNetworkKind.PAIRING, pairing)
        radio.setStationAssociated(true)
        radio.beacon(KartNetworkKind.PAIRING, pairing.copy(seed = pairing.seed.copyOf()))
        assertTrue(radio.associatedStation.value, "the same pairing network, compared by content, is a no-op")
        assertEquals(
            listOf(KartNetworkKind.GAME, KartNetworkKind.GAME, KartNetworkKind.PAIRING, KartNetworkKind.PAIRING),
            radio.beaconCalls,
            "every call is still recorded",
        )
    }

    @Test
    fun theBeaconedNetworkFollowsTheBeaconAndStop() = runTest {
        val radio = FakeKartRadioAp()
        radio.start()
        radio.beaconedNetwork.test {
            assertNull(awaitItem())
            radio.beacon(KartNetworkKind.GAME)
            assertEquals(KartNetworkKind.GAME, awaitItem())
            radio.stop()
            assertNull(awaitItem())
        }
    }

    @Test
    fun beaconRequiresAPairingNetworkOnlyWhenPairing() = runTest {
        val radio = FakeKartRadioAp()
        radio.start()

        val pairing = PairingNetwork(ssid = "mklhc-pair_abc", seed = ByteArray(0x10), channel = 1)
        assertFailsWith<IllegalArgumentException> { radio.beacon(KartNetworkKind.PAIRING) }
        assertFailsWith<IllegalArgumentException> { radio.beacon(KartNetworkKind.GAME, pairing) }
    }

    @Test
    fun beaconBeforeStartIsAProgrammingError() = runTest {
        val radio = FakeKartRadioAp()
        assertFailsWith<IllegalStateException> { radio.beacon(KartNetworkKind.GAME) }
        assertEquals(emptyList(), radio.beaconCalls)
    }

    @Test
    fun beaconFailureIsThrownAndNotRecorded() = runTest {
        val radio = FakeKartRadioAp()
        radio.start()
        radio.beaconFailure = IllegalStateException("tx queue full")

        assertFailsWith<IllegalStateException> { radio.beacon(KartNetworkKind.GAME) }
        assertEquals(emptyList(), radio.beaconCalls)
        assertNull(radio.currentBeacon)
    }

    @Test
    fun stopClearsTheBeaconAndTheAssociation() = runTest {
        val radio = FakeKartRadioAp()
        radio.start()
        radio.beacon(KartNetworkKind.GAME)
        radio.setStationAssociated(true)

        radio.stop()

        assertNull(radio.currentBeacon)
        assertFalse(radio.associatedStation.value)
        assertEquals(listOf(KartNetworkKind.GAME), radio.beaconCalls, "history is kept for assertions")
    }

    @Test
    fun associationChangesAreObservable() = runTest {
        val radio = FakeKartRadioAp()
        radio.associatedStation.test {
            assertFalse(awaitItem())
            radio.setStationAssociated(true)
            assertTrue(awaitItem())
            radio.setStationAssociated(false)
            assertFalse(awaitItem())
        }
    }

    @Test
    fun framesSentOnTheApLinkReachTheStationAndViceVersa() = runTest {
        val radio = FakeKartRadioAp()

        radio.frameLink.send(EtherType.IPV4, byteArrayOf(1, 2, 3))
        val atStation = withTimeout(5_000) { radio.stationLink.inbound.first() }
        assertEquals(LinkPayload(EtherType.IPV4, byteArrayOf(1, 2, 3)), atStation)

        radio.stationLink.send(EtherType.ARP, byteArrayOf(9, 8))
        val atAp = withTimeout(5_000) { radio.frameLink.inbound.first() }
        assertEquals(LinkPayload(EtherType.ARP, byteArrayOf(9, 8)), atAp)
    }

    @Test
    fun framesAreBufferedUntilTheReceiverStartsCollecting() = runTest {
        val radio = FakeKartRadioAp()
        radio.stationLink.send(EtherType.IPV4, byteArrayOf(1))
        radio.stationLink.send(EtherType.IPV4, byteArrayOf(2))

        // The FrameLink contract: frames sent before the node subscribes must not be lost.
        radio.frameLink.inbound.test {
            assertContentEquals(byteArrayOf(1), awaitItem().payload)
            assertContentEquals(byteArrayOf(2), awaitItem().payload)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sentFramesAreCapturedOnBothSides() = runTest {
        val radio = FakeKartRadioAp()
        radio.frameLink.send(EtherType.IPV4, byteArrayOf(7))
        radio.stationLink.send(EtherType.ARP, byteArrayOf(6))

        assertEquals(listOf(LinkPayload(EtherType.IPV4, byteArrayOf(7))), radio.sentByAp)
        assertEquals(listOf(LinkPayload(EtherType.ARP, byteArrayOf(6))), radio.sentByStation)
    }
}
