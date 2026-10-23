package com.mklhc.core.data.session

import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.data.radio.AdapterState
import com.mklhc.core.data.radio.AdapterUnavailableException
import com.mklhc.core.data.radio.FakeKartRadioAp
import com.mklhc.core.data.radio.KartRadioAp
import com.mklhc.core.data.store.InMemoryPairingStore
import com.mklhc.core.data.store.PairingStore
import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.KartSettings
import com.mklhc.core.domain.LinkState
import com.mklhc.core.domain.PairedKart
import com.mklhc.core.domain.UnavailableReason
import com.mklhc.core.kart_protocol.teleop.DriveCommand
import com.mklhc.core.kart_protocol.telemetry.Telemetry
import com.mklhc.core.kart_protocol.video.LspVideo
import com.mklhc.core.kart_protocol.video.Lvni
import com.mklhc.core.netstack.tcp.TcpState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [KartSessionRepository], the orchestration capstone.
 *
 * Business logic: [sessionSequenceReachesDrivingThroughTheRealStack] drives the WHOLE sequence end to end
 * over the real [com.mklhc.core.netstack.NetstackNode] + RCD + Fuji stack across the paired in-memory
 * FrameLink: a [FakeKart] completes the real RCD handshake, answers the real control exchange, receives
 * the real teleop datagrams and is handed the app's real LVNI keepalive. The other tests pin the state
 * mapping, teleop safety, telemetry/video surfacing, pairing, recovery, the kart leaving the network,
 * the lifecycle commands running one at a time in call order, and teardown. Everything runs under virtual
 * time via `runTest`.
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

    private fun TestScope.harness(
        settings: KartSettings = KartSettings.DEFAULT,
        scope: CoroutineScope = backgroundScope,
        radioSeenByRepo: (FakeKartRadioAp) -> KartRadioAp = { it },
        kartServicesLateBy: Duration? = null,
        videoDatagramTap: ((ByteArray) -> Unit)? = null,
    ): Harness {
        val radio = FakeKartRadioAp()
        val store = store(settings)
        // The deadman and the connect window measure real elapsed time; the real monotonic clock does not
        // advance with virtual time, so give the repository the test scheduler's virtual time source.
        val repo = KartSessionRepository(
            radioSeenByRepo(radio),
            store,
            crypto,
            scope,
            clock = fixedClock,
            timeSource = testScheduler.timeSource,
            videoDatagramTap = videoDatagramTap,
        )
        val kart = FakeKart(radio, masterSecret(), scope, crypto, servicesLateBy = kartServicesLateBy)
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

    @Test
    fun anAdapterThatTurnsUnusableAtStartSendsTheSessionBackToWaitingForIt() = runTest {
        val radio = FakeKartRadioAp()
        radio.startFailure = noUsableAdapter()
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)
        val states = ArrayList<LinkState>()
        backgroundScope.launch { repo.linkState.collect { states += it } }

        repo.start()
        awaitCondition { radio.startCount == 1 }
        // The fresh scan that failed the start reaches the platform's adapter state a moment later.
        radio.setAdapterState(AdapterState.ABSENT)
        withTimeout(5_000) { repo.linkState.first { it == LinkState.NoAdapter } }
        delay(10_000)
        assertEquals(1, radio.startCount, "the session waits for the adapter instead of retrying")
        assertEquals(LinkState.NoAdapter, repo.linkState.value)

        // Plugged in again: the session goes on by itself.
        radio.startFailure = null
        radio.setAdapterState(AdapterState.READY)
        withTimeout(5_000) { repo.linkState.first { it == LinkState.Beaconing(KartNetworkKind.GAME) } }
        assertEquals(2, radio.startCount)
        assertTrue(states.none { it is LinkState.Failed }, "an unusable adapter is not a failed session: $states")
    }

    @Test
    fun anAdapterStateThatStaysReadyAfterAnUnusableAdapterRetriesTheStartAfterAPauseNotInALoop() = runTest {
        val radio = FakeKartRadioAp()
        radio.startFailure = noUsableAdapter()
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)

        repo.start()
        awaitCondition { radio.startCount == 1 }
        assertEquals(LinkState.NoAdapter, repo.linkState.value)
        delay(SessionConfig().adapterRetryDelay - 100.milliseconds)
        assertEquals(1, radio.startCount, "no retry before the pause is over")
        delay(200.milliseconds)
        assertEquals(2, radio.startCount, "the platform never said the adapter went away, so the start is tried again")

        radio.startFailure = null
        withTimeout(10_000) { repo.linkState.first { it == LinkState.Beaconing(KartNetworkKind.GAME) } }
    }

    @Test
    fun pairingWithAnAdapterThatTurnsUnusableWaitsForItAndThenPairs() = runTest {
        val radio = FakeKartRadioAp()
        radio.startFailure = noUsableAdapter()
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)

        repo.startPairing()
        awaitCondition { radio.startCount == 1 }
        radio.setAdapterState(AdapterState.ABSENT)
        withTimeout(5_000) { repo.linkState.first { it == LinkState.NoAdapter } }

        radio.startFailure = null
        radio.setAdapterState(AdapterState.READY)
        withTimeout(5_000) { repo.linkState.first { it == LinkState.Beaconing(KartNetworkKind.PAIRING) } }
        assertNotNull(repo.pairingQr.value, "the pairing goes on where it was")
    }

    private fun noUsableAdapter() =
        AdapterUnavailableException(AdapterResolution.Unavailable(UnavailableReason.NO_COMPATIBLE_DEVICE))

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

    @Test
    fun everyVideoDatagramReachesTheTapAsReceivedBeforeReassembly() = runTest {
        val tapped = ArrayList<ByteArray>()
        val h = harness(videoDatagramTap = { tapped += it.copyOf() })
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        val start = videoStartDatagram(byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte()))
        val runt = byteArrayOf(1, 2, 3) // shorter than the trailer: the reassembler drops it, the tap still sees it
        h.kart.sendVideo(start)
        h.kart.sendVideo(runt)

        awaitCondition { tapped.size == 2 }
        assertContentEquals(start, tapped[0])
        assertContentEquals(runt, tapped[1])
    }

    @Test
    fun aTapThatThrowsNeitherStopsTheVideoNorReachesTheSession() = runTest {
        var tapCalls = 0
        val h = harness(videoDatagramTap = {
            tapCalls++
            error("disk full")
        })
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)
        val frames = ArrayList<ByteArray>()
        backgroundScope.launch { h.repo.videoAccessUnits.collect { frames += it } }
        delay(10) // let the collector subscribe

        val h264 = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
        h.kart.sendVideo(videoStartDatagram(h264))
        h.kart.sendVideo(videoStartDatagram(h264))

        awaitCondition { frames.size == 2 }
        assertEquals(2, tapCalls, "the tap is still called after it failed")
        assertEquals(LinkState.Driving, h.repo.linkState.value)
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

    @Test
    fun kartServicesThatListenLateAreRetriedUntilTheyAcceptPairingAndTheGameSession() = runTest {
        // As on hardware: right after the handshake the kart answers a connect to its services with a RST.
        val h = harness(kartServicesLateBy = 300.milliseconds)
        val states = ArrayList<LinkState>()
        backgroundScope.launch { h.repo.linkState.collect { states += it } }

        h.repo.startPairing()
        withTimeout(5_000) { h.repo.pairingQr.first { it != null } }
        h.kart.runPairingThenGame()

        val (ssid, _) = assertNotNull(h.kart.groupInfo, "the refused pairing connect was retried and the credentials handed over")
        assertEquals(deriveKartIdentity(masterSecret(), crypto).gameSsid, ssid)
        awaitDriving(h.repo)
        assertTrue(states.none { it is LinkState.Failed }, "a refused connect is retried, never fatal: $states")
        assertTrue(states.none { it is LinkState.Recovering }, "the retries fit in the connect window: $states")
    }

    @Test
    fun aKartServiceThatNeverListensEndsTheSessionOnceTheConnectWindowElapses() = runTest {
        val h = harness(kartServicesLateBy = 1_000.seconds)
        h.repo.start()
        backgroundScope.launch { h.kart.runGameSession() }

        val handshaked = withTimeout(10_000) {
            h.repo.linkState.first { it == LinkState.KartHandshaked }
            testScheduler.timeSource.markNow()
        }
        val recovering = withTimeout(10_000) { h.repo.linkState.first { it is LinkState.Recovering } } as LinkState.Recovering
        val retried = handshaked.elapsedNow()
        assertTrue("refused the connection to port 5103" in recovering.reason, "the loss names the refusal: ${recovering.reason}")
        val window = SessionConfig().connectWindow
        assertTrue(retried >= window && retried <= window + 100.milliseconds, "it retried for the connect window, no longer: $retried")
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

    // ---- kart departure -----------------------------------------------------------------------

    @Test
    fun aKartThatLeavesWhileDrivingLosesTheSessionAndDrivesAgainWhenItReturns() = runTest {
        val h = harness()
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)
        h.kart.requestLease()
        awaitCondition { h.repo.dhcpServer?.boundClientMac != null }
        // Every session connection is up, the video control one included.
        awaitCondition { h.kart.lvniBytes.size >= Lvni.PACKET_SIZE }

        // Gone without a word: no FIN, no RST, only the association drops.
        h.kart.leave()

        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.Recovering(STATION_LEFT) } }
        val leftBehind = h.kart.connectionsLeftBehind
        assertEquals(4, leftBehind.size, "the RCD, control, event and video control connections were open")
        awaitCondition { leftBehind.all { it.state.value == TcpState.CLOSED } }
        assertNull(h.repo.dhcpServer?.boundClientMac, "the lease was freed for the kart's return")

        h.kart.runGameSession()
        awaitDriving(h.repo)
    }

    @Test
    fun aSessionThatReachesDrivingRestartsTheRecoveryBackoff() = runTest {
        val h = harness()
        val timeline = ArrayList<Pair<LinkState, ComparableTimeMark>>()
        backgroundScope.launch { h.repo.linkState.collect { timeline += it to testScheduler.timeSource.markNow() } }
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        // Two losses that are not departures (the kart stays on the network), each followed by a session that
        // drives again: both recoveries must wait the initial backoff, not a doubled one.
        repeat(2) {
            h.kart.dropGameConnections()
            withTimeout(5_000) { h.repo.linkState.first { it is LinkState.Recovering } }
            h.kart.runGameSession()
            awaitDriving(h.repo)
        }

        // Each pause runs from entering Recovering to beaconing the game network again.
        val pauses = timeline.indices.filter { timeline[it].first is LinkState.Recovering }.map { start ->
            val resumed = timeline.drop(start + 1).first { it.first == LinkState.Beaconing(KartNetworkKind.GAME) }
            resumed.second - timeline[start].second
        }
        val initial = SessionConfig().recoveryBackoffInitial
        assertEquals(listOf(initial, initial), pauses, "a session that reached driving restarts the backoff")
    }

    @Test
    fun thePairedKartIsSavedOnceEvenWhenLaterSessionsAreLost() = runTest {
        val radio = FakeKartRadioAp()
        val store = CountingPairingStore(store())
        val repo = KartSessionRepository(radio, store, crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)
        val kart = FakeKart(radio, masterSecret(), backgroundScope, crypto)
        kart.start()

        repo.startPairing()
        assertNotNull(withTimeout(5_000) { repo.pairingQr.first { it != null } })
        kart.runPairingThenGame()
        awaitDriving(repo)
        assertEquals(1, store.pairedKartSaves, "the kart identified right after pairing is saved")

        kart.dropGameConnections()
        withTimeout(5_000) { repo.linkState.first { it is LinkState.Recovering } }
        kart.runGameSession()
        awaitDriving(repo)
        assertEquals(1, store.pairedKartSaves, "a later session does not save the paired kart again")
    }

    @Test
    fun aKartThatLeavesBeforeConnectingDoesNotLeaveTheSessionWaitingOnAccept() = runTest {
        val h = harness()
        h.repo.start()
        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.Beaconing(KartNetworkKind.GAME) } }

        // The kart joins but never connects, so the repository waits in accept() on the RCD port.
        h.radio.setStationAssociated(true)
        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.KartAssociated } }
        delay(1_000)
        assertEquals(LinkState.KartAssociated, h.repo.linkState.value, "still waiting for the kart to connect")

        h.radio.setStationAssociated(false)
        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.Recovering(STATION_LEFT) } }

        // A kart that comes back and connects gets a working session.
        h.kart.runGameSession()
        awaitDriving(h.repo)
    }

    @Test
    fun aDepartureSkipsTheRecoveryBackoff() = runTest {
        val h = harness()
        h.repo.start()
        h.kart.runGameSession()
        awaitDriving(h.repo)

        h.kart.leave()
        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.Recovering(STATION_LEFT) } }
        val lostAt = testScheduler.timeSource.markNow()

        // The kart re-associates and connects right as the loss is raised, as it does on hardware.
        h.kart.runGameSession()
        awaitDriving(h.repo)
        val recovery = lostAt.elapsedNow()
        assertTrue(
            recovery < SessionConfig().recoveryBackoffInitial,
            "the new session started without the ${SessionConfig().recoveryBackoffInitial} backoff, it took $recovery",
        )
    }

    @Test
    fun aConnectionAcceptedAsTheKartLeavesIsStillReset() = runTest {
        val h = harness()
        h.repo.start()

        // The kart connects to the session RCD port and leaves the moment its connect completes, so the app accepts
        // that connection in the same instant it loses the session.
        h.kart.connectToSessionThenLeave()
        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.Recovering(STATION_LEFT) } }

        val leftBehind = h.kart.connectionsLeftBehind
        assertEquals(1, leftBehind.size, "the session RCD connection was open")
        awaitCondition { leftBehind.all { it.state.value == TcpState.CLOSED } }

        h.kart.runGameSession()
        awaitDriving(h.repo)
    }

    @Test
    fun aKartThatLeavesBeforeTheGroupInfoIsAwaitedAgainWithTheSameQr() = runTest {
        val h = harness()
        val states = ArrayList<LinkState>()
        backgroundScope.launch { h.repo.linkState.collect { states += it } }

        h.repo.startPairing()
        val qr = assertNotNull(withTimeout(5_000) { h.repo.pairingQr.first { it != null } })
        val pairingNetwork = assertNotNull(h.radio.lastPairingNetwork)

        // The kart connects to the pairing RCD server, takes the lease, then leaves before the handshake.
        h.kart.connectToPairing()
        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.KartLeased } }
        h.kart.requestLease()
        awaitCondition { h.repo.dhcpServer?.boundClientMac != null }
        h.kart.leave()

        // Only the attempt ended: its connection was reset, its lease freed, and the same network and QR wait on.
        val leftBehind = h.kart.connectionsLeftBehind
        assertEquals(1, leftBehind.size, "the pairing RCD connection was open")
        awaitCondition { leftBehind.all { it.state.value == TcpState.CLOSED } }
        awaitCondition { h.repo.linkState.value == LinkState.Beaconing(KartNetworkKind.PAIRING) }
        assertNull(h.repo.dhcpServer?.boundClientMac, "the lease was freed for the kart's return")
        assertContentEquals(qr, h.repo.pairingQr.value, "the same QR stays published")
        assertEquals(listOf(KartNetworkKind.PAIRING), h.radio.beaconCalls, "the pairing network was not beaconed again")
        assertEquals(pairingNetwork, h.radio.lastPairingNetwork)

        // The kart comes back and pairs, and the session follows.
        h.kart.runPairingThenGame()
        awaitDriving(h.repo)
        assertNotNull(h.kart.groupInfo, "the kart was handed the game credentials")
        assertNotNull(h.store.getPairedKart(), "the paired kart is persisted")
        assertEquals(null, h.repo.pairingQr.value, "the QR is cleared once pairing is done")
        assertSubsequence(
            states,
            listOf(
                LinkState.Beaconing(KartNetworkKind.PAIRING),
                LinkState.KartLeased,
                LinkState.Beaconing(KartNetworkKind.PAIRING),
                LinkState.KartHandshaked,
                LinkState.Beaconing(KartNetworkKind.GAME),
                LinkState.Driving,
            ),
        )
        assertTrue(states.none { it is LinkState.Recovering || it is LinkState.Failed }, "pairing never failed: $states")
    }

    @Test
    fun aKartThatLeavesOnceTheGroupInfoCallIsUnderWayCompletesThePairing() = runTest {
        val h = harness()
        val states = ArrayList<LinkState>()
        backgroundScope.launch { h.repo.linkState.collect { states += it } }

        h.repo.startPairing()
        assertNotNull(withTimeout(5_000) { h.repo.pairingQr.first { it != null } })

        // The kart answers SetGroupInfo and drops off the network right after, then resets its pairing RCD
        // connection: the app sees both while its call still waits for the answer to come in.
        var stateAtDeparture: LinkState? = null
        var qrAtDeparture: ByteArray? = null
        h.kart.runPairingThenGame(
            onLeftPairing = {
                stateAtDeparture = h.repo.linkState.value
                qrAtDeparture = h.repo.pairingQr.value
            },
        )
        awaitDriving(h.repo)

        assertEquals(LinkState.KartHandshaked, stateAtDeparture, "the kart left while the app was in its SetGroupInfo call")
        assertNotNull(qrAtDeparture, "the pairing attempt had not finished when the kart left")
        assertNotNull(h.kart.groupInfo, "the kart was handed the game credentials")
        assertNotNull(h.store.getPairedKart(), "the paired kart is persisted")
        assertEquals(1, states.count { it == LinkState.Beaconing(KartNetworkKind.PAIRING) }, "pairing was not retried: $states")
        assertTrue(states.none { it is LinkState.Recovering || it is LinkState.Failed }, "the departure was not a loss: $states")
    }

    @Test
    fun anRcdChannelClosingBeforeTheGroupInfoRetriesTheAttemptWithTheSameQr() = runTest {
        val h = harness()
        val states = ArrayList<LinkState>()
        backgroundScope.launch { h.repo.linkState.collect { states += it } }

        h.repo.startPairing()
        val qr = assertNotNull(withTimeout(5_000) { h.repo.pairingQr.first { it != null } })

        // The kart's RCD client gives up and resets its connection before the handshake, but the kart stays on the
        // network: the app's RCD control channel closes without any departure.
        h.kart.connectToPairing()
        withTimeout(5_000) { h.repo.linkState.first { it == LinkState.KartLeased } }
        h.kart.dropPairingConnection()

        awaitCondition { states.count { it == LinkState.Beaconing(KartNetworkKind.PAIRING) } >= 2 }
        assertTrue(h.radio.associatedStation.value, "the kart never left the network")
        assertContentEquals(qr, h.repo.pairingQr.value, "the same QR stays published")
        assertEquals(listOf(KartNetworkKind.PAIRING), h.radio.beaconCalls, "the pairing network was not beaconed again")

        // The kart starts over on the same network and pairs, and the session follows.
        h.kart.runPairingThenGame()
        awaitDriving(h.repo)
        assertNotNull(h.kart.groupInfo, "the kart was handed the game credentials")
        assertNotNull(h.store.getPairedKart(), "the paired kart is persisted")
        assertTrue(states.none { it is LinkState.Recovering || it is LinkState.Failed }, "pairing never failed: $states")
    }

    @Test
    fun aDepartureAlreadyPublishedWhenTheWatcherIsDisarmedRetriesInsteadOfSendingTheGroupInfo() = runTest {
        // The repository's watchers learn of a departure only a while after the access point publishes it, so the
        // departure below is published but not yet seen by the watcher when the attempt disarms it.
        val h = harness(radioSeenByRepo = { LateDepartureRadio(it, lag = DEPARTURE_LAG) })
        val states = ArrayList<LinkState>()
        backgroundScope.launch { h.repo.linkState.collect { states += it } }

        h.repo.startPairing()
        val qr = assertNotNull(withTimeout(5_000) { h.repo.pairingQr.first { it != null } })

        // The kart completes the handshake and leaves at once; the app still connects to its pairing service.
        h.kart.handshakeOnPairingThenLeave()
        awaitCondition { states.count { it == LinkState.Beaconing(KartNetworkKind.PAIRING) } >= 2 }
        assertTrue(LinkState.KartHandshaked in states, "the attempt got as far as the handshake: $states")
        assertNull(h.kart.groupInfo, "no SetGroupInfo was sent to a kart that had left")
        assertContentEquals(qr, h.repo.pairingQr.value, "the same QR stays published")

        // The kart comes back and pairs, and the session follows.
        h.kart.runPairingThenGame()
        awaitDriving(h.repo)
        assertNotNull(h.kart.groupInfo, "the kart was handed the game credentials")
        assertTrue(states.none { it is LinkState.Recovering || it is LinkState.Failed }, "pairing never failed: $states")
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

    // ---- lifecycle commands run one at a time, in call order -----------------------------------

    @Test
    fun aCancelPairingWhileAStartPairingReplacesItsOrchestratorLeavesNothingRunning() = runTest {
        val radio = SlowStartRadio(FakeKartRadioAp(), RADIO_START_TIME)
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)
        repo.startPairing()
        delay(COMMAND_STEP) // the first orchestrator is inside the radio's start
        val restart = launch { repo.startPairing() }
        delay(COMMAND_STEP)
        assertFalse(restart.isCompleted, "the restart is in the gap: the old orchestrator is cancelled, the new one not yet launched")

        repo.cancelPairing()
        delay(SETTLE_TIME)

        assertTrue(restart.isCompleted)
        assertEquals(0, radio.waitingForKart, "no orchestrator is left waiting for the kart")
        assertFalse(radio.fake.isStarted, "the radio is stopped")
        assertNull(radio.fake.currentBeacon, "the pairing network is not left on the air")
        assertNull(repo.pairingQr.value, "no QR is shown")
        assertEquals(LinkState.Failed("pairing cancelled"), repo.linkState.value)
    }

    @Test
    fun twoQuickStartsLeaveExactlyOneOrchestrator() = runTest {
        val radio = SlowStartRadio(FakeKartRadioAp(), RADIO_START_TIME)
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)
        repo.start()
        delay(COMMAND_STEP) // the first orchestrator is inside the radio's start

        val second = launch { repo.start() }
        val third = launch { repo.start() }
        delay(SETTLE_TIME)

        assertTrue(second.isCompleted && third.isCompleted)
        assertEquals(1, radio.waitingForKart, "exactly one orchestrator beacons the game network and waits for the kart")
        assertEquals(KartNetworkKind.GAME, radio.fake.currentBeacon)
        assertEquals(LinkState.Beaconing(KartNetworkKind.GAME), repo.linkState.value)
    }

    @Test
    fun stopDuringAStartInFlightCompletesItsTeardownEvenWhenItsCallerIsCancelled() = runTest {
        val radio = SlowStartRadio(FakeKartRadioAp(), RADIO_START_TIME)
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)
        repo.start()
        delay(COMMAND_STEP)
        val restart = launch { repo.start() }
        delay(COMMAND_STEP)
        assertFalse(restart.isCompleted, "the restart waits for the first orchestrator to end")

        // stop() waits for the command in flight, and its caller gives up meanwhile.
        val callerScope = CoroutineScope(coroutineContext + Job())
        val stopping = callerScope.launch { repo.stop() }
        delay(COMMAND_STEP)
        assertFalse(stopping.isCompleted, "stop waits for the start in flight instead of acting on the job it replaces")
        callerScope.cancel()
        stopping.join()

        assertTrue(restart.isCompleted, "the start in flight finished first, no deadlock")
        assertEquals(1, radio.fake.stopCount, "the teardown ran to the end despite the cancelled caller")
        assertFalse(radio.fake.isStarted)
        delay(SETTLE_TIME)
        assertEquals(0, radio.waitingForKart, "the orchestrator the start published was cancelled too")
        assertFalse(radio.fake.isStarted, "and nothing starts the radio again")
    }

    @Test
    fun aStartPairingRightAfterCancelPairingRunsAfterTheCancelAndIsNotUndoneByIt() = runTest {
        val radio = SlowStartRadio(FakeKartRadioAp(), RADIO_START_TIME)
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)
        repo.startPairing()
        delay(COMMAND_STEP) // the first orchestrator is inside the radio's start

        repo.cancelPairing()
        repo.startPairing()
        delay(SETTLE_TIME)

        assertEquals(1, radio.waitingForKart, "the new pairing waits for the kart")
        assertTrue(radio.fake.isStarted)
        assertEquals(KartNetworkKind.PAIRING, radio.fake.currentBeacon)
        assertNotNull(repo.pairingQr.value, "the new pairing's QR is shown")
        assertEquals(LinkState.Beaconing(KartNetworkKind.PAIRING), repo.linkState.value)
    }

    @Test
    fun cancelPairingAfterStopDoesNothing() = runTest {
        val radio = FakeKartRadioAp()
        val repo = KartSessionRepository(radio, store(), crypto, backgroundScope, clock = fixedClock, timeSource = testScheduler.timeSource)
        repo.startPairing()
        withTimeout(5_000) { repo.linkState.first { it == LinkState.Beaconing(KartNetworkKind.PAIRING) } }
        repo.stop()
        val stateAfterStop = repo.linkState.value

        repo.cancelPairing()
        delay(COMMAND_STEP)

        assertEquals(1, radio.stopCount, "the radio is not stopped a second time")
        assertEquals(stateAfterStop, repo.linkState.value, "the link state is left as stop left it")
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
 * Builds a one-datagram video frame with a "FRAM" record at offset 13: filler where the datagram and chunk
 * headers go, the length octet, the tag, the big-endian FRAM size, filler, the H.264 bytes, then the 8-octet
 * trailer. The length octet 0x1f makes the H.264 start exactly [LspVideo.FRAM_SIZE_OVERHEAD] octets after the
 * tag (`13 - 1 + 0x1f + 4 = 47`); the FRAM size also counts the 4-octet frame-end field. A one-datagram frame
 * needs no particular sequence number.
 */
private fun videoStartDatagram(h264: ByteArray): ByteArray {
    val framSize = h264.size + LspVideo.FRAM_SIZE_OVERHEAD + LspVideo.FRAME_END_FIELD
    return ByteArray(12) { 0x11 } +
        byteArrayOf(0x1f) +
        "FRAM".encodeToByteArray() +
        u32BigEndian(framSize) +
        ByteArray(26) { 0x22 } +
        h264 +
        ByteArray(LspVideo.PACKET_TRAILER) { 0xEE.toByte() }
}

/**
 * The fake radio as the repository sees it, with departures reaching its watchers late: [associatedStation] reports
 * the current value at once, but a collector receives a `false` only [lag] after it was published.
 *
 * Business logic: on hardware a departure is published in [KartRadioAp.associatedStation] and the repository's
 * watcher sees it one dispatch later. Stretching that dispatch to [lag] of virtual time makes "a departure already
 * published whose watcher has not run yet" a deterministic state for a test. Everything else is the fake's.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class LateDepartureRadio(private val radio: FakeKartRadioAp, private val lag: Duration) : KartRadioAp by radio {
    override val associatedStation: StateFlow<Boolean> = object : StateFlow<Boolean> {
        override val value: Boolean get() = radio.associatedStation.value

        override val replayCache: List<Boolean> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing =
            radio.associatedStation.collect { associated ->
                if (!associated) delay(lag)
                collector.emit(associated)
            }
    }
}

/**
 * The fake radio as the repository sees it, with a start that takes [startTime] of virtual time and cannot be cut
 * short, and a count of the coroutines waiting for the kart to associate.
 *
 * Business logic: cancelling an orchestrator that is inside this start takes [startTime], as cancelling one whose
 * teardown takes time does. That is the window in which a lifecycle command replacing the orchestrator has cancelled
 * the old job but not yet launched the new one, stretched so a test can issue another command inside it. An
 * orchestrator that reached the beaconing phase waits for the kart in [associatedStation], so until a kart associates
 * [waitingForKart] is the number of orchestrators running. Everything else is the fake's.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class SlowStartRadio(val fake: FakeKartRadioAp, private val startTime: Duration) : KartRadioAp by fake {
    /** How many coroutines are collecting [associatedStation] now. */
    var waitingForKart = 0
        private set

    override suspend fun start() {
        withContext(NonCancellable) { delay(startTime) }
        fake.start()
    }

    override val associatedStation: StateFlow<Boolean> = object : StateFlow<Boolean> {
        override val value: Boolean get() = fake.associatedStation.value

        override val replayCache: List<Boolean> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
            waitingForKart++
            try {
                fake.associatedStation.collect(collector)
            } finally {
                waitingForKart--
            }
        }
    }
}

/** An in-memory store that counts how often the paired kart is saved; everything else is the wrapped store's. */
private class CountingPairingStore(private val inner: InMemoryPairingStore) : PairingStore by inner {
    var pairedKartSaves = 0
        private set

    override suspend fun setPairedKart(kart: PairedKart?) {
        pairedKartSaves++
        inner.setPairedKart(kart)
    }
}

/** The reason [LinkState.Recovering] carries when the kart left the network. */
private const val STATION_LEFT = "the kart left the network"

/** How late [LateDepartureRadio] lets the repository's watchers see a departure; shorter than the kart's connect retries. */
private val DEPARTURE_LAG = 500.milliseconds

/** How long [SlowStartRadio] takes to start the radio. */
private val RADIO_START_TIME = 1.seconds

/** Virtual time for the coroutines a command launched to reach their next wait, well inside [RADIO_START_TIME]. */
private val COMMAND_STEP = 10.milliseconds

/** Virtual time for every orchestrator to settle: several radio starts. */
private val SETTLE_TIME = 10.seconds
