package com.mklhc.app.demo

import app.cash.turbine.test
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.pairing.PAIRING_PAYLOAD_SIZE
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DemoKartSession]: the scripted, hardware-free session the desktop demo (and, for now, the Android app) runs on.
 * The tests pin the script a screen relies on: Start climbs the whole link ladder to Driving and then streams
 * battery, IMU and video; Pair shows a pairing QR and then climbs the same way; Cancel, Stop, Sleep and Shutdown
 * park the session. Everything runs on the virtual clock of `runTest`, so the multi-second script costs nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoKartSessionTest {

    private val timing = DemoTiming()

    private val ladderToDriving = listOf(
        LinkState.RadioStarting,
        LinkState.Beaconing(KartNetworkKind.GAME),
        LinkState.KartAssociated,
        LinkState.KartLeased,
        LinkState.KartHandshaked,
        LinkState.KartReady,
        LinkState.Driving,
    )

    // ---- start: the normal flow --------------------------------------------------------------

    @Test
    fun startsParkedWithNoAdapterAndNothingElse() = runTest {
        val session = DemoKartSession(backgroundScope, timing)

        assertEquals(LinkState.NoAdapter, session.linkState.value)
        assertNull(session.kartInfo.value)
        assertNull(session.telemetry.value)
        assertNull(session.battery.value)
        assertNull(session.pairingQr.value)
    }

    @Test
    fun startReturnsAtOnceAndTheLinkWalksTheWholeLadderToDriving() = runTest {
        val session = DemoKartSession(backgroundScope, timing)

        session.linkState.test {
            assertEquals(LinkState.NoAdapter, awaitItem())
            session.start()
            for (expected in ladderToDriving) {
                assertEquals(expected, awaitItem())
            }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun kartInfoIsPublishedOnceTheKartHasHandshaked() = runTest {
        val session = DemoKartSession(backgroundScope, timing)

        session.start()
        // Not yet identified while the radio is still coming up.
        advanceTimeBy(timing.stepDelayMs)
        assertNull(session.kartInfo.value)

        session.linkState.test {
            // Skip forward to Driving, then the kart must be identified.
            while (awaitItem() != LinkState.Driving) { /* keep climbing */ }
            cancelAndIgnoreRemainingEvents()
        }
        val info = assertNotNull(session.kartInfo.value)
        assertTrue(info.serial.isNotBlank())
        assertTrue(info.serial.startsWith("DEMO"), "the sample kart must be labelled as a demo")
    }

    @Test
    fun drivingStreamsBatteryImuAndVideo() = runTest {
        val session = DemoKartSession(backgroundScope, timing)

        session.battery.filterNotNull().test {
            session.start()
            val battery = awaitItem()
            assertTrue(battery.batteryBars in 1..4, "a few bars, got ${battery.batteryBars}")
            cancelAndIgnoreRemainingEvents()
        }
        session.imu.test {
            val packet = awaitItem()
            assertEquals(1, packet.samples.size)
            cancelAndIgnoreRemainingEvents()
        }
        session.videoAccessUnits.test {
            assertTrue(awaitItem().isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        // The generic telemetry flow follows the stream too.
        assertNotNull(session.telemetry.value)
    }

    @Test
    fun startWhileDrivingRestartsTheClimb() = runTest {
        val session = DemoKartSession(backgroundScope, timing)
        session.start()
        session.linkState.test {
            while (awaitItem() != LinkState.Driving) { /* keep climbing */ }

            session.start()

            assertEquals(LinkState.RadioStarting, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- pairing -----------------------------------------------------------------------------

    @Test
    fun startPairingShowsAQrWhileBeaconingThenClimbsToDriving() = runTest {
        val session = DemoKartSession(backgroundScope, timing, Random(7))

        session.linkState.test {
            assertEquals(LinkState.NoAdapter, awaitItem())
            session.startPairing()
            assertEquals(LinkState.RadioStarting, awaitItem())
            assertEquals(LinkState.Beaconing(KartNetworkKind.PAIRING), awaitItem())

            val qr = assertNotNull(session.pairingQr.value, "the QR is shown while the pairing network is beaconed")
            assertEquals(PAIRING_PAYLOAD_SIZE, qr.size)

            // The demo kart "scans" the code: the QR goes away and the link climbs like a normal session.
            assertEquals(LinkState.KartAssociated, awaitItem())
            assertNull(session.pairingQr.value)
            assertEquals(LinkState.KartLeased, awaitItem())
            assertEquals(LinkState.KartHandshaked, awaitItem())
            assertEquals(LinkState.KartReady, awaitItem())
            assertEquals(LinkState.Driving, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNotNull(session.kartInfo.value)
    }

    @Test
    fun theQrCarriesTheSampleSsidAndAFreshSeedEachTime() = runTest {
        val random = Random(1)
        val session = DemoKartSession(backgroundScope, timing, random)

        val first = captureQr(session)
        // Cancelling clears the code, so the second capture sees the next attempt's code and not a replay of the first.
        session.cancelPairing()
        val second = captureQr(session)

        // SSID field starts at octet 0x10 of the payload.
        val ssid = first.copyOfRange(0x10, 0x10 + DemoTelemetry.PAIRING_SSID.encodeToByteArray().size).decodeToString()
        assertEquals(DemoTelemetry.PAIRING_SSID, ssid)
        assertFalse(first.copyOfRange(0, 0x10).contentEquals(second.copyOfRange(0, 0x10)), "a new seed per attempt")
    }

    private suspend fun captureQr(session: DemoKartSession): ByteArray {
        var qr: ByteArray? = null
        session.pairingQr.filterNotNull().test {
            session.startPairing()
            qr = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return assertNotNull(qr)
    }

    @Test
    fun cancelPairingClearsTheQrAndSettlesOnAFailedLinkThatStaysPut() = runTest {
        val session = DemoKartSession(backgroundScope, timing)
        session.startPairing()
        session.pairingQr.filterNotNull().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        session.cancelPairing()

        assertNull(session.pairingQr.value)
        assertEquals(LinkState.Failed("pairing cancelled"), session.linkState.value)
        // The cancelled script must not keep climbing in the background.
        advanceTimeBy(timing.pairingScanDelayMs + 10 * timing.stepDelayMs)
        assertEquals(LinkState.Failed("pairing cancelled"), session.linkState.value)
        assertNull(session.kartInfo.value)
    }

    @Test
    fun cancelPairingAfterTheKartIsLiveClearsEveryStreamAndSettlesOnFailed() = runTest {
        val session = DemoKartSession(backgroundScope, timing)
        session.startPairing()

        // Follow the battery stream: null at first, then live once the kart handshakes. Cancelling while the
        // telemetry is live must clear it (the cleanup joins the stopped script before clearing), not leave it stale.
        session.battery.test {
            assertNull(awaitItem())
            assertNotNull(awaitItem())
            assertNotNull(session.kartInfo.value)

            session.cancelPairing()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(LinkState.Failed("pairing cancelled"), session.linkState.value)
        assertNull(session.telemetry.value)
        assertNull(session.kartInfo.value)
        assertNull(session.pairingQr.value)
    }

    // ---- parking -----------------------------------------------------------------------------

    @Test
    fun stopParksTheSessionAndTheStreamsGoQuiet() = runTest {
        val session = DemoKartSession(backgroundScope, timing)
        session.start()
        session.linkState.test {
            while (awaitItem() != LinkState.Driving) { /* keep climbing */ }
            cancelAndIgnoreRemainingEvents()
        }
        session.setDriveCommand(DriveCommand(throttle = 40, steering = -10, brakeLight = false))

        session.stop()

        assertParked(session)
        session.imu.test {
            advanceTimeBy(5_000)
            expectNoEvents()
        }
    }

    @Test
    fun sleepKartAndShutdownKartParkTheSessionToo() = runTest {
        val sleeping = DemoKartSession(backgroundScope, timing)
        sleeping.start()
        sleeping.linkState.test {
            while (awaitItem() != LinkState.Driving) { /* keep climbing */ }
            cancelAndIgnoreRemainingEvents()
        }
        sleeping.sleepKart()
        assertParked(sleeping)

        val off = DemoKartSession(backgroundScope, timing)
        off.start()
        off.linkState.test {
            while (awaitItem() != LinkState.Driving) { /* keep climbing */ }
            cancelAndIgnoreRemainingEvents()
        }
        off.shutdownKart()
        assertParked(off)
    }

    @Test
    fun aParkedSessionCanBeStartedAgainSoTheDemoCanBeWalkedRepeatedly() = runTest {
        val session = DemoKartSession(backgroundScope, timing)
        session.start()
        session.stop()

        session.linkState.test {
            assertEquals(LinkState.NoAdapter, awaitItem())
            session.start()
            assertEquals(LinkState.RadioStarting, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun assertParked(session: DemoKartSession) {
        assertEquals(LinkState.NoAdapter, session.linkState.value)
        assertNull(session.kartInfo.value)
        assertNull(session.telemetry.value)
        assertNull(session.battery.value)
        assertNull(session.pairingQr.value)
        assertEquals(DriveCommand.NEUTRAL, session.driveCommand.value)
    }

    // ---- commands ----------------------------------------------------------------------------

    @Test
    fun setDriveCommandIsRecordedAndCaptureIsRemembered() = runTest {
        val session = DemoKartSession(backgroundScope, timing)
        assertEquals(DriveCommand.NEUTRAL, session.driveCommand.value)
        assertFalse(session.captureEnabled)

        val command = DriveCommand(throttle = 64, steering = 20, brakeLight = true)
        session.setDriveCommand(command)
        session.setCaptureEnabled(true)

        assertEquals(command, session.driveCommand.value)
        assertTrue(session.captureEnabled)
    }

    @Test
    fun theHeldDriveCommandShowsUpInTheGMeterOfTheNextImuPackets() = runTest {
        val session = DemoKartSession(backgroundScope, timing)
        session.start()
        session.imu.test {
            awaitItem()
            // Full throttle forward and full steering to the right.
            session.setDriveCommand(DriveCommand(throttle = 127, steering = 127, brakeLight = false))

            // The newest packets must carry the bias: forward is a negative IMU Y, right is a positive IMU X.
            var biased = false
            repeat(PACKETS_TO_INSPECT) {
                val accel = awaitItem().samples.single().accel
                if (accel.y < -BIAS_THRESHOLD_G * DemoTelemetry.GRAVITY && accel.x > BIAS_THRESHOLD_G * DemoTelemetry.GRAVITY) {
                    biased = true
                }
            }
            assertTrue(biased, "the held command must bias the G meter")
            cancelAndIgnoreRemainingEvents()
        }
        assertNotEquals(DriveCommand.NEUTRAL, session.driveCommand.value)
    }

    private companion object {
        const val PACKETS_TO_INSPECT = 10
        const val BIAS_THRESHOLD_G = 0.2
    }
}
