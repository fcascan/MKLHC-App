package com.mklhc.core.data.session

import app.cash.turbine.test
import com.mklhc.core.domain.KartInfo
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.ImuFlags
import com.mklhc.core.kart_protocol.telemetry.Quaternion
import com.mklhc.core.kart_protocol.telemetry.QuaternionRaw
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.kart_protocol.telemetry.Vector3Int
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FakeKartSession]: the scriptable stand-in the feature ViewModel tests use in place of the real
 * [KartSessionRepository]. These tests pin the behaviour those tests will rely on: every flow starts empty and
 * can be pushed into, every command is recorded in order, and the fake stays interchangeable with the real
 * session through the [KartSession] interface.
 */
class FakeKartSessionTest {

    private fun battery(bars: Int) = Telemetry.BatteryStatus(cableFlags = 0, batteryBars = bars)

    private fun imu(totalSamples: Int = 1) = Telemetry.Imu(
        totalSampleCount = totalSamples,
        flags = ImuFlags(0),
        timerMicros = 0L,
        quaternion = Quaternion.IDENTITY,
        quaternionRaw = QuaternionRaw(0, 0, 0, 0x40000000),
        positionA = Vector3Int(0, 0, 0),
        positionB = Vector3Int(0, 0, 0),
        velocityA = Vector3Int(0, 0, 0),
        velocityB = Vector3Int(0, 0, 0),
        samples = emptyList(),
        truncated = false,
    )

    private val info = KartInfo(
        serial = "SN-0012345",
        character = 1,
        firmwareSystem = 1 to 4,
        firmwareBoot = 0 to 9,
        sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709",
    )

    // ---- initial state ---------------------------------------------------------------------

    @Test
    fun startsWithNoAdapterAndNothingElse() {
        val session = FakeKartSession()
        assertEquals(LinkState.NoAdapter, session.linkState.value)
        assertNull(session.kartInfo.value)
        assertNull(session.telemetry.value)
        assertNull(session.battery.value)
        assertNull(session.pairingQr.value)
        assertNull(session.lastDriveCommand)
        assertEquals(emptyList(), session.driveCommands)
        assertEquals(emptyList(), session.calls)
        assertFalse(session.startCalled)
        assertFalse(session.startPairingCalled)
        assertFalse(session.captureEnabled)
    }

    @Test
    fun initialLinkStateCanBeChosen() {
        assertEquals(LinkState.Driving, FakeKartSession(initialLinkState = LinkState.Driving).linkState.value)
    }

    @Test
    fun isUsableThroughTheKartSessionInterface() {
        val session: KartSession = FakeKartSession()
        assertEquals(LinkState.NoAdapter, session.linkState.value)
    }

    // ---- pushing state in ------------------------------------------------------------------

    @Test
    fun linkStateChangesAreObservable() = runTest {
        val session = FakeKartSession()
        session.linkState.test {
            assertEquals(LinkState.NoAdapter, awaitItem())
            session.setLinkState(LinkState.RadioStarting)
            assertEquals(LinkState.RadioStarting, awaitItem())
            session.setLinkState(LinkState.Recovering("link lost"))
            assertEquals(LinkState.Recovering("link lost"), awaitItem())
        }
    }

    @Test
    fun kartInfoAndPairingQrAreObservable() = runTest {
        val session = FakeKartSession()
        session.setKartInfo(info)
        assertEquals(info, session.kartInfo.value)
        session.setKartInfo(null)
        assertNull(session.kartInfo.value)

        val qr = byteArrayOf(1, 2, 3)
        session.setPairingQr(qr)
        assertContentEquals(qr, session.pairingQr.value)
        session.setPairingQr(null)
        assertNull(session.pairingQr.value)
    }

    @Test
    fun emittedBatteryTelemetryUpdatesTheTelemetryAndBatteryFlowsLikeTheRealSession() {
        val session = FakeKartSession()
        session.emitTelemetry(battery(3))
        assertEquals(battery(3), session.telemetry.value)
        assertEquals(battery(3), session.battery.value)
    }

    @Test
    fun emittedImuTelemetryUpdatesTelemetryAndFeedsTheImuFlowButNotBattery() = runTest {
        val session = FakeKartSession()
        session.imu.test {
            val packet = imu()
            session.emitTelemetry(packet)
            assertEquals(packet, awaitItem())
            assertEquals(packet, session.telemetry.value)
            assertNull(session.battery.value)
        }
    }

    @Test
    fun unknownTelemetryOnlyUpdatesTheTelemetryFlow() {
        val session = FakeKartSession()
        val unknown = Telemetry.Unknown(type = 3, raw = byteArrayOf(9))
        session.emitTelemetry(unknown)
        assertEquals(unknown, session.telemetry.value)
        assertNull(session.battery.value)
    }

    @Test
    fun videoAccessUnitsAreDeliveredToACollectorInOrder() = runTest {
        val session = FakeKartSession()
        session.videoAccessUnits.test {
            session.emitVideoAccessUnit(byteArrayOf(1))
            session.emitVideoAccessUnit(byteArrayOf(2, 2))
            assertContentEquals(byteArrayOf(1), awaitItem())
            assertContentEquals(byteArrayOf(2, 2), awaitItem())
        }
    }

    // ---- recorded commands -----------------------------------------------------------------

    @Test
    fun driveCommandsAreRecordedInOrderAndTheLastIsExposed() {
        val session = FakeKartSession()
        val forward = DriveCommand(throttle = 64, steering = 0, brakeLight = false)
        val left = DriveCommand(throttle = 64, steering = -128, brakeLight = false)
        session.setDriveCommand(forward)
        session.setDriveCommand(left)
        session.setDriveCommand(DriveCommand.NEUTRAL)

        assertEquals(listOf(forward, left, DriveCommand.NEUTRAL), session.driveCommands)
        assertEquals(DriveCommand.NEUTRAL, session.lastDriveCommand)
    }

    @Test
    fun suspendingCommandsAreRecordedWithCounts() = runTest {
        val session = FakeKartSession()
        session.start()
        session.startPairing()
        session.sleepKart()
        session.shutdownKart()
        session.stop()

        assertTrue(session.startCalled)
        assertTrue(session.startPairingCalled)
        assertEquals(1, session.startCount)
        assertEquals(1, session.startPairingCount)
        assertEquals(1, session.sleepKartCount)
        assertEquals(1, session.shutdownKartCount)
        assertEquals(1, session.stopCount)
    }

    @Test
    fun cancelPairingIsRecordedAndClearsTheQrLikeTheRealSession() {
        val session = FakeKartSession()
        session.setPairingQr(byteArrayOf(1, 2, 3))

        session.cancelPairing()

        assertEquals(1, session.cancelPairingCount)
        assertNull(session.pairingQr.value, "the real repository clears the QR synchronously on cancel")
    }

    @Test
    fun captureEnabledIsTrackedAndEveryCallRecorded() {
        val session = FakeKartSession()
        session.setCaptureEnabled(true)
        assertTrue(session.captureEnabled)
        session.setCaptureEnabled(false)
        assertFalse(session.captureEnabled)
        assertEquals(listOf(true, false), session.captureEnabledCalls)
    }

    @Test
    fun callsAreLoggedInTheOrderTheyHappenedSoSafetyOrderingCanBeAsserted() = runTest {
        val session = FakeKartSession()
        session.start()
        session.setDriveCommand(DriveCommand(throttle = 10, steering = 0, brakeLight = false))
        session.setDriveCommand(DriveCommand.NEUTRAL)
        session.sleepKart()
        session.stop()

        assertEquals(
            listOf(
                FakeKartSession.Call.START,
                FakeKartSession.Call.SET_DRIVE_COMMAND,
                FakeKartSession.Call.SET_DRIVE_COMMAND,
                FakeKartSession.Call.SLEEP_KART,
                FakeKartSession.Call.STOP,
            ),
            session.calls,
        )
    }
}
