package com.mklhc.core.data.session

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.data.radio.AdapterState
import com.mklhc.core.data.radio.FakeKartRadioAp
import com.mklhc.core.data.store.InMemoryPairingStore
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.kart_protocol.video.LspVideo
import com.mklhc.core.kart_protocol.video.Lvni
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for [KartSessionRepository], the orchestration capstone.
 *
 * Business logic: [sessionSequenceReachesDrivingThroughTheRealStack] drives the WHOLE sequence end to end
 * over the real [com.mklhc.core.netstack.NetstackNode] + RCD + Fuji stack across the paired in-memory
 * FrameLink: a [FakeKart] completes the real RCD handshake, answers the real control exchange, receives
 * the real teleop datagrams and is handed the app's real LVNI keepalive. The other tests pin the state
 * mapping, teleop safety, telemetry/video surfacing, pairing, recovery and teardown. Everything runs under
 * virtual time via `runTest`.
 */
class KartSessionRepositoryTest {

    private val crypto = CryptoPrimitives()
    private val fixedClock: () -> Long = { 1_700_000_000L }

    private fun masterSecret(): ByteArray = ByteArray(32) { (it + 7).toByte() }

    // First octet 0x02: locally administered (bit 1 set), unicast (bit 0 clear).
    private fun bssid(): ByteArray = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)

    private fun store(settings: KartSettings = KartSettings.DEFAULT): InMemoryPairingStore =
        InMemoryPairingStore(crypto = crypto, masterSecret = masterSecret(), apBssid = bssid(), channel = 1, settings = settings)

    /** A set-up repository + kart sharing one radio and master secret. */
    private class Harness(
        val radio: FakeKartRadioAp,
        val store: InMemoryPairingStore,
        val repo: KartSessionRepository,
        val kart: FakeKart,
    )

    private fun TestScope.harness(settings: KartSettings = KartSettings.DEFAULT, scope: CoroutineScope = backgroundScope): Harness {
        val radio = FakeKartRadioAp()
        val store = store(settings)
        // The deadman and the connect window measure real elapsed time; the real monotonic clock does not
        // advance with virtual time, so give the repository the test scheduler's virtual time source.
        val repo = KartSessionRepository(radio, store, crypto, scope, clock = fixedClock, timeSource = testScheduler.timeSource)
        val kart = FakeKart(radio, masterSecret(), scope, crypto)
        kart.start()
        return Harness(radio, store, repo, kart)
    }

    private suspend fun awaitCondition(timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        withTimeout(timeoutMs) { while (!predicate()) delay(5) }
    }

    private suspend fun awaitDriving(repo: KartSessionRepository) {
        withTimeout(10_000) { repo.linkState.first { it is LinkState.Driving } }
    }

    private fun assertSubsequence(actual: List<LinkState>, expected: List<LinkState>) {
        var index = 0
        for (state in actual) {
            if (index < expected.size && state == expected[index]) index++
        }
        assertEquals(expected.size, index, "expected $expected as an ordered subsequence of $actual")
    }

    // ---- the real end-to-end path -------------------------------------------------------------

    @Test
    fun sessionSequenceReachesDrivingThroughTheRealStack() = runTest {
        val h = harness()
        val states = ArrayList<LinkState>()
        backgroundScope.launch { h.repo.linkState.collect { states += it } }

        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        // The full ladder was climbed in order.
        awaitCondition { states.any { it is LinkState.Driving } }
        assertSubsequence(
            states,
            listOf(
                LinkState.Beaconing(KartNetworkKind.GAME),
                LinkState.KartAssociated,
                LinkState.KartHandshaked,
                LinkState.KartReady,
                LinkState.Driving,
            ),
        )

        // The kart was identified from the real control exchange.
        val info = assertNotNull(withTimeout(5_000) { h.repo.kartInfo.first { it != null } })
        assertEquals(h.kart.serial, info.serial)
        assertEquals(h.kart.character, info.character)
        assertEquals(FakeKart.EXPECTED_FIRMWARE_SYSTEM, info.firmwareSystem)
        assertEquals(FakeKart.EXPECTED_FIRMWARE_BOOT, info.firmwareBoot)
        assertEquals(FakeKart.EXPECTED_SHA1, info.sha1)

        // The app announced its own ports to the kart (connection_info little-endian struct).
        val connectionInfo = assertNotNull(h.kart.connectionInfo)
        assertEquals(16, connectionInfo.size)
        assertEquals(5116, u16LittleEndian(connectionInfo, 2), "telemetry port")
        assertEquals(5032, u16LittleEndian(connectionInfo, 4), "video control port")
        assertEquals(5016, u16LittleEndian(connectionInfo, 6), "video stream port")
        assertEquals(1, h.kart.driveState, "the kart was put in drive")

        // The real LVNI keepalive reached the kart on the video control channel, repeating each second.
        awaitCondition { h.kart.lvniBytes.size >= Lvni.PACKET_SIZE }
        assertEquals("LVNI", h.kart.lvniBytes.decodeToString(0, 4))
        val afterFirst = h.kart.lvniBytes.size
        delay(1_100)
        awaitCondition { h.kart.lvniBytes.size > afterFirst }
    }

    // ---- adapter mapping ----------------------------------------------------------------------

    @Test
    fun adapterStatesMapToTheFirstRungsOfTheLadder() = runTest {
        val radio = FakeKartRadioAp(initialAdapterState = AdapterState.ABSENT)
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)

        repo.start()
        withTimeout(5_000) { repo.linkState.first { it == LinkState.NoAdapter } }

        radio.setAdapterState(AdapterState.PERMISSION_REQUIRED)
        withTimeout(5_000) { repo.linkState.first { it == LinkState.AwaitingUsbPermission } }
        assertTrue(radio.permissionRequestCount >= 1, "permission was requested")

        radio.setAdapterState(AdapterState.READY)
        withTimeout(5_000) { repo.linkState.first { it == LinkState.Beaconing(KartNetworkKind.GAME) } }
        assertTrue(radio.isStarted, "the radio was started once ready")
        assertEquals(KartNetworkKind.GAME, radio.currentBeacon)
    }

    // ---- teleop: cadence, clamping, deadman ---------------------------------------------------

    @Test
    fun teleopStreamsAtThirtyHertzClampsThrottleAndDeadmansToNeutral() = runTest {
        val h = harness(settings = KartSettings(maxThrottlePercent = 50, steeringExpoPercent = 0, steeringTrimPercent = 0))
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        val command = DriveCommand(throttle = 120, steering = 0, brakeLight = false)
        val refresher = backgroundScope.launch {
            while (true) {
                h.repo.setDriveCommand(command)
                delay(20)
            }
        }

        val before = h.kart.teleopPackets.size
        delay(1_000)
        val streamed = h.kart.teleopPackets.size - before
        assertTrue(streamed in 24..36, "about 30 Hz over a second, got $streamed")

        val sample = h.kart.teleopPackets.last()
        assertEquals(32, sample.size, "a teleop datagram is 32 octets")
        assertEquals(60, sample[0].toInt(), "throttle 120 clamped to 50% is 60")

        // Deadman: stop refreshing; after 250 ms with no fresh command the app sends neutral.
        refresher.cancel()
        delay(400)
        assertEquals(0, h.kart.teleopPackets.last()[0].toInt(), "the deadman sent neutral throttle")
    }

    // ---- telemetry and video surfacing --------------------------------------------------------

    @Test
    fun telemetryDatagramSurfacesAsBatteryStatus() = runTest {
        val h = harness()
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        // Type 1 battery status: 0x1f payload, cable bit in octet 0, bars in octet 3.
        val datagram = ByteArray(1 + 0x1f)
        datagram[0] = 1
        datagram[1] = 0x01
        datagram[1 + 3] = 3
        h.kart.sendTelemetry(datagram)

        val battery = withTimeout(5_000) { h.repo.telemetry.first { it is Telemetry.BatteryStatus } } as Telemetry.BatteryStatus
        assertEquals(3, battery.batteryBars)
        assertTrue(battery.cableConnected)
    }

    @Test
    fun videoDatagramSurfacesAReassembledAccessUnit() = runTest {
        val h = harness()
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        val frames = ArrayList<ByteArray>()
        backgroundScope.launch { h.repo.videoAccessUnits.collect { frames += it } }
        delay(10) // let the collector subscribe

        val h264 = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        h.kart.sendVideo(videoStartDatagram(h264))

        awaitCondition { frames.isNotEmpty() }
        assertContentEquals(h264, frames.first(), "the reassembled access unit is the H.264 payload")
    }

    // ---- pairing ------------------------------------------------------------------------------

    @Test
    fun pairingExposesTheQrHandsCredentialsPersistsAndJoinsTheGame() = runTest {
        val h = harness()

        h.repo.startPairing()
        val qr = assertNotNull(withTimeout(5_000) { h.repo.pairingQr.first { it != null } })
        assertEquals(0x3E, qr.size, "the pairing QR payload is 62 octets")

        h.kart.runPairingThenGame()

        val (ssid, psk) = assertNotNull(h.kart.groupInfo, "the kart was handed the game credentials")
        val identity = deriveKartIdentity(masterSecret(), crypto)
        assertEquals(identity.gameSsid, ssid)
        assertContentEquals(identity.gamePsk, psk)

        awaitDriving(h.repo)
        assertEquals(null, h.repo.pairingQr.value, "the QR is cleared once pairing is done")

        val paired = assertNotNull(h.store.getPairedKart(), "the paired kart is persisted")
        assertEquals(h.kart.serial, paired.serial)
        assertContentEquals(h.kart.deviceIdent, paired.ident)
    }

    // ---- recovery -----------------------------------------------------------------------------

    @Test
    fun anRcdDisconnectRecoversForcesNeutralAndReRunsTheSession() = runTest {
        val h = harness()
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        val refresher = backgroundScope.launch {
            while (true) {
                h.repo.setDriveCommand(DriveCommand(throttle = 100, steering = 0, brakeLight = false))
                delay(20)
            }
        }
        delay(200)

        val packetsAtReset = h.kart.teleopPackets.size
        h.kart.resetLink()

        withTimeout(5_000) { h.repo.linkState.first { it is LinkState.Recovering } }
        awaitCondition { h.kart.teleopPackets.drop(packetsAtReset).any { it[0].toInt() == 0 } }

        // A fresh association re-runs the whole session back to driving.
        h.kart.runGameSession()
        awaitDriving(h.repo)
        refresher.cancel()
    }

    // ---- FIX C1: no stale command replay after recovery ---------------------------------------

    @Test
    fun recoveryDoesNotReplayAStaleCommandUntilAFreshOneArrives() = runTest {
        val h = harness()
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        // Drive non-neutral: throttle 100 becomes 50 on the wire at the default 50% scale.
        val refresher = backgroundScope.launch {
            while (true) {
                h.repo.setDriveCommand(DriveCommand(throttle = 100, steering = 0, brakeLight = false))
                delay(20)
            }
        }
        awaitCondition { h.kart.teleopPackets.any { it[0].toInt() == 50 } }

        // Lose the link, then recover WITHOUT sending any fresh command during recovery.
        refresher.cancel()
        h.kart.resetLink()
        withTimeout(5_000) { h.repo.linkState.first { it is LinkState.Recovering } }
        val packetsBeforeResume = h.kart.teleopPackets.size

        h.kart.runGameSession()
        awaitDriving(h.repo)
        delay(300) // several teleop ticks into the resumed session

        val afterResume = h.kart.teleopPackets.drop(packetsBeforeResume)
        assertTrue(afterResume.isNotEmpty(), "teleop resumed after recovery")
        assertTrue(
            afterResume.all { it[0].toInt() == 0 },
            "the kart must receive neutral, not a replayed stale throttle; saw ${afterResume.map { it[0].toInt() }.distinct()}",
        )
    }

    // ---- FIX I1: the deadman is a wall-time window ---------------------------------------------

    @Test
    fun teleopDeadmansToNeutralAfterTheWindowOfNoFreshInput() = runTest {
        val h = harness()
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        // A short burst of fresh non-neutral commands, then silence.
        repeat(5) {
            h.repo.setDriveCommand(DriveCommand(throttle = 100, steering = 0, brakeLight = false))
            delay(20)
        }
        awaitCondition { h.kart.teleopPackets.any { it[0].toInt() == 50 } }

        // After the 250 ms deadman window with no fresh input, neutral is sent.
        delay(400)
        assertEquals(0, h.kart.teleopPackets.last()[0].toInt(), "the deadman sent neutral after the window")
    }

    // ---- FIX I2: recovery backs off instead of busy-looping ------------------------------------

    @Test
    fun aFailingBeaconBacksOffInsteadOfSpinning() = runTest(timeout = 10.seconds) {
        val radio = FakeKartRadioAp()
        radio.beaconFailure = IllegalStateException("tx queue full")
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)

        repo.start()

        // With a 500 ms backoff doubling to a 5 s cap, only a handful of attempts fit in a 2 s window; the
        // un-fixed busy-loop would run unbounded (and livelock virtual time).
        delay(2_000)
        val attempts = radio.beaconAttemptCount
        assertTrue(attempts in 1..8, "recovery backed off to a few attempts in 2 s, got $attempts")
    }

    // ---- FIX I5 / G1 + G4: the pairing beacon carries the QR network ---------------------------

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun thePairingBeaconCarriesTheSameNetworkAsTheQrPayload() = runTest {
        val h = harness()
        h.repo.startPairing()

        val qr = assertNotNull(withTimeout(5_000) { h.repo.pairingQr.first { it != null } })
        awaitCondition { h.radio.lastPairingNetwork != null }
        val beaconed = assertNotNull(h.radio.lastPairingNetwork)

        val seed = qr.copyOfRange(0, 0x10)
        val ssid = latin1UntilNul(qr, 0x10, 0x30)
        val channel = u16LittleEndian(qr, 0x30)

        assertEquals(ssid, beaconed.ssid, "the beacon SSID matches the QR")
        assertContentEquals(seed, beaconed.seed, "the beacon seed matches the QR")
        assertEquals(channel, beaconed.channel, "the beacon channel matches the QR")

        // FIX G4: the SSID's random tail is 12 octets (design 8.5), not 6.
        assertTrue(ssid.startsWith("mklhc-pair_"), "the pairing SSID keeps its prefix")
        val randomTail = Base64.Default.decode(ssid.removePrefix("mklhc-pair_"))
        assertEquals(12, randomTail.size, "the SSID random portion is 12 octets")
    }

    // ---- FIX I6: shutdownOnExit is honoured on stop() -----------------------------------------

    @Test
    fun stopPowersTheKartOffWhenShutdownOnExitIsTrue() = runTest {
        val h = harness(settings = KartSettings(shutdownOnExit = true))
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        h.repo.stop()

        assertTrue(h.kart.shutdownRequested, "the kart was powered off on stop when shutdownOnExit is true")
    }

    @Test
    fun stopOnlyParksTheKartWhenShutdownOnExitIsFalse() = runTest {
        val h = harness(settings = KartSettings(shutdownOnExit = false))
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        h.repo.stop()

        assertEquals(0, h.kart.driveState, "the kart was put to sleep on stop when shutdownOnExit is false")
        assertTrue(!h.kart.shutdownRequested, "the kart was not powered off when shutdownOnExit is false")
    }

    // ---- FIX I4: stop() completes teardown even under cancellation -----------------------------

    @Test
    fun stopCompletesTeardownEvenWhenItsCallerIsCancelled() = runTest {
        val radio = FakeKartRadioAp()
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)
        val kart = FakeKart(radio, masterSecret(), backgroundScope, crypto)
        kart.start()
        repo.start()
        kart.runGameSession()
        awaitDriving(repo)

        // Run stop() in a caller scope cancelled while stop() is in flight; its NonCancellable teardown must
        // still bring the radio down.
        val callerScope = CoroutineScope(coroutineContext + Job())
        val stopJob = callerScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.stop() }
        callerScope.cancel()
        stopJob.join()

        assertEquals(1, radio.stopCount, "stop() finished teardown and stopped the radio despite the cancellation")
    }

    // ---- FIX D2: cancelPairing stops the beacon and settles the state -------------------------

    @Test
    fun cancelPairingStopsTheBeaconAndLeavesNoBeaconingState() = runTest {
        val h = harness()
        h.repo.startPairing()
        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.Beaconing(KartNetworkKind.PAIRING) } }

        h.repo.cancelPairing()

        awaitCondition { h.radio.stopCount >= 1 }
        assertEquals(null, h.repo.pairingQr.value, "the pairing QR was cleared")
        awaitCondition { h.repo.linkState.value != LinkState.Beaconing(KartNetworkKind.PAIRING) }
        assertTrue(
            h.repo.linkState.value !is LinkState.Beaconing,
            "the link is not left beaconing after cancel; it is ${h.repo.linkState.value}",
        )
    }

    // ---- teardown -----------------------------------------------------------------------------

    @Test
    fun stopLeavesNoLeakedCoroutineOrTimer() = runTest {
        // The repository runs on the test scope: runTest only returns once every coroutine it launched has
        // finished, so a leaked loop or timer would hang this test.
        val radio = FakeKartRadioAp()
        // shutdownOnExit = false so stop() parks the kart (sleep) rather than powering it off (see FIX I6).
        val store = store(KartSettings(shutdownOnExit = false))
        val repo = KartSessionRepository(radio, store, crypto, this, clock = fixedClock, timeSource = testScheduler.timeSource)
        val kart = FakeKart(radio, masterSecret(), backgroundScope, crypto)
        kart.start()

        repo.start()
        kart.runGameSession()
        awaitDriving(repo)

        repo.stop()

        assertEquals(1, radio.stopCount, "the radio was stopped")
        assertEquals(0, kart.driveState, "the kart was put to sleep on stop")
        assertEquals(false, kart.shutdownRequested, "the kart was not powered off when shutdownOnExit is false")
    }
}

/** Reads an unsigned little-endian 16-bit value at [offset]. */
private fun u16LittleEndian(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

/**
 * Builds a one-datagram video frame with a "FRAM" record at offset 13: filler, the length octet, the tag,
 * the big-endian FRAM size, filler, the H.264 bytes, then the 8-octet trailer. The length octet 0x1f makes
 * the H.264 start exactly [LspVideo.FRAM_SIZE_OVERHEAD] octets after the tag (`13 - 1 + 0x1f + 4 = 47`).
 */
private fun videoStartDatagram(h264: ByteArray): ByteArray {
    val framSize = h264.size + LspVideo.FRAM_SIZE_OVERHEAD
    return ByteArray(12) { 0x11 } +
        byteArrayOf(0x1f) +
        "FRAM".encodeToByteArray() +
        u32BigEndian(framSize) +
        ByteArray(26) { 0x22 } +
        h264 +
        ByteArray(LspVideo.PACKET_TRAILER) { 0xEE.toByte() }
}
