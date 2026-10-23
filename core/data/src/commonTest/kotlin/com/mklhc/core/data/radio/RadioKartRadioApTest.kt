package com.mklhc.core.data.radio

import co.touchlab.kermit.Severity
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.data.CapturingLogWriter
import com.mklhc.core.data.capturingLogger
import com.mklhc.core.data.hex
import com.mklhc.core.data.identity.deriveKartIdentity
import com.mklhc.core.data.store.InMemoryPairingStore
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.UnavailableReason
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import com.mklhc.core.netstack.EtherType
import com.mklhc.core.netstack.LinkPayload
import com.mklhc.core.radio.FakeRadioLink
import com.mklhc.core.radio.RadioFrame
import com.mklhc.core.radio.RadioLink
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.wifi_ap.ap.ApConfig
import com.mklhc.core.wifi_ap.ap.FakeStation
import com.mklhc.core.wifi_ap.ap.RadioAccessPoint
import com.mklhc.core.wifi_ap.ap.RadioOptions
import com.mklhc.core.wifi_ap.lp2p.Lp2pKeys
import com.mklhc.core.wifi_ap.lp2p.NonceTable
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.InformationElements
import com.mklhc.core.wifi_ap.mac.MacAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [RadioKartRadioAp] over a real [RadioAccessPoint] on a [FakeRadioLink], with an in-memory pairing store and a
 * [FakeStation] playing the kart.
 *
 * Business logic: the tests pin what the orchestrator relies on. The radio comes up once on the store's channel and a
 * failed or cancelled start leaves nothing running; each network is served with the store's BSSID, a hidden SSID and
 * the CCMP key its credentials derive to (a station holding that independently recomputed key associates and its
 * data is accepted, one holding another key is not); asking for the served network again changes nothing, so a
 * recovering session never cuts off an associated kart, while switching networks serves a new configuration and drops
 * the station; and stop shuts the radio down and can always be repeated. The nonce table is deterministic: the
 * pairing network's random draw and the game network's time-based draw land on different indexes, so a network
 * served with the wrong kind of nonce shows up as a wrong key. When the platform's connection flag goes false and true
 * again under a started radio (radiod relaunched), the radio is brought up again and the network that was served is
 * served again with the same configuration, so the same beacon and the same keys; a disconnect alone, a radio that is
 * not started, and the connection start() itself waited for change nothing, and a failed or interrupted re-bring-up
 * leaves nothing running that a stop or the next reconnect cannot deal with. A radiod that goes away during start()'s
 * own bring-up (a crash in INIT, or the old radiod of a stop shutting down) is not waited on: the radiod that replaces
 * it gets its own OPEN_DEVICE and INIT and its READY completes start(), while a second disconnect fails start() and
 * leaves it retryable. The connection is a [MutableStateFlow], and radiod's READY is played by hand, so every step is
 * observable under virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent / advanceTimeBy drive the access point's collectors under virtual time
class RadioKartRadioApTest {

    // ---- start ---------------------------------------------------------------------------------

    @Test
    fun startBringsTheRadioUpOnTheStoreChannelOnceAndIsIdempotent() = runTest {
        var connectChecks = 0
        val rig = Rig(this, awaitRadioConnected = { connectChecks++ })

        assertNull(rig.start())
        assertEquals(
            listOf<RadioMessage.AppToRadiod>(
                RadioMessage.OpenDevice(ADAPTER.vid, ADAPTER.pid),
                RadioMessage.Init(CHANNEL, RADIO_OPTIONS.bandwidthMhz, RADIO_OPTIONS.txPowerIndex),
            ),
            rig.radio.sentMessages,
            "the radio comes up on the resolved adapter and the channel the store chose, and nothing is beaconed yet",
        )

        assertNull(rig.start(answer = null))
        assertEquals(2, rig.radio.sentMessages.size, "a second start does not touch the radio again")
        assertEquals(1, connectChecks, "a started radio does not wait for the helper again")
    }

    @Test
    fun startWaitsForTheRadioHelperBeforeTouchingTheRadio() = runTest {
        val connected = CompletableDeferred<Unit>()
        val rig = Rig(this, awaitRadioConnected = { connected.await() })
        val start = backgroundScope.launch { rig.ap.start() }

        runCurrent()
        assertTrue(rig.radio.sentMessages.isEmpty(), "nothing is sent while the helper is unreachable")
        assertTrue(start.isActive)

        connected.complete(Unit)
        runCurrent()
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
        assertTrue(start.isActive, "still waiting for READY")

        rig.radio.emitEvent(READY)
        runCurrent()
        assertTrue(start.isCompleted)
    }

    @Test
    fun aFailedStartShutsTheAccessPointDownAndCanBeRetried() = runTest {
        val rig = Rig(this)

        val failure = rig.start(answer = RadioMessage.Error(code = 7u, text = "cannot open the adapter"))

        assertTrue(failure is IllegalStateException, "the failure is rethrown: $failure")
        assertTrue("cannot open the adapter" in failure.message.orEmpty())
        assertTrue(
            rig.logs.allText().contains("start(): the radio did not come up, shutting the access point down again -> message="),
            rig.logs.allText(),
        )
        assertEquals(
            listOf("OpenDevice", "Init", "StopBeacon", "Shutdown"),
            rig.radio.sentMessages.map { it::class.simpleName },
            "the half-started radio is shut down again",
        )
        assertFailsWith<IllegalStateException>("a failed start leaves the radio not started") { rig.ap.beacon(KartNetworkKind.GAME) }

        rig.radio.clearSentMessages()
        assertNull(rig.start(), "the next attempt starts clean")
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aHelperThatCannotBeReachedFailsStartWithoutTouchingTheRadio() = runTest {
        var reachable = false
        val rig = Rig(this, awaitRadioConnected = { check(reachable) { "radiod is not connected" } })

        val failure = assertFailsWith<IllegalStateException> { rig.ap.start() }

        assertEquals("radiod is not connected", failure.message)
        assertTrue(rig.radio.sentMessages.isEmpty())
        reachable = true
        assertNull(rig.start())
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun cancellingStartShutsTheAccessPointDownToo() = runTest {
        val rig = Rig(this)
        val start = launch { rig.ap.start() }
        runCurrent()
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })

        start.cancel()
        runCurrent()

        assertTrue(start.isCancelled, "cancellation propagates out of start()")
        assertEquals(
            listOf("OpenDevice", "Init", "StopBeacon", "Shutdown"),
            rig.radio.sentMessages.map { it::class.simpleName },
        )
        assertFalse(rig.logs.allText().contains("did not come up"), "a cancellation is not reported as a radio failure")
        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.GAME) }
        rig.radio.clearSentMessages()
        assertNull(rig.start(), "a cancelled start can be tried again")
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aStartEndedByAnErrorStillShutsTheAccessPointDown() = runTest {
        // An Error is no Exception: without the cleanup in finally the collectors would stay and every later start
        // would fail with "already started".
        val rig = Rig(this, radioLink = { fake -> CrashingInitRadioLink(fake) })

        val failure = rig.start(answer = null)

        assertTrue(failure is HelperCrash, "the error is rethrown: $failure")
        assertEquals(listOf("OpenDevice", "StopBeacon", "Shutdown"), rig.radio.sentMessages.map { it::class.simpleName })
        assertTrue(rig.logs.allText().contains("did not come up, shutting the access point down again -> message=the radio helper crashed"))
        rig.radio.clearSentMessages()
        assertNull(rig.start(), "the next attempt starts clean")
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    // ---- beacon: arguments ---------------------------------------------------------------------

    @Test
    fun beaconBeforeStartIsAProgrammingError() = runTest {
        val rig = Rig(this)

        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.GAME) }
        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork()) }
        assertTrue(rig.radio.sentMessages.isEmpty())
    }

    @Test
    fun beaconRefusesAPairingNetworkWhereItDoesNotBelongAndAnotherChannel() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.radio.clearSentMessages()

        assertFailsWith<IllegalArgumentException>("PAIRING needs the network the QR describes") {
            rig.ap.beacon(KartNetworkKind.PAIRING)
        }
        assertFailsWith<IllegalArgumentException>("GAME takes its credentials from the identity") {
            rig.ap.beacon(KartNetworkKind.GAME, pairingNetwork())
        }
        assertFailsWith<IllegalArgumentException>("the radio is on one channel only") {
            rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork(channel = OTHER_CHANNEL))
        }
        assertTrue(rig.radio.sentMessages.isEmpty(), "a refused request touches nothing")
    }

    // ---- beacon: the two networks --------------------------------------------------------------

    @Test
    fun pairingServesTheStoreBssidWithAHiddenSsidAndTheKeyDerivedFromTheSeed() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.radio.clearSentMessages()

        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork())

        assertEquals(listOf("SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
        assertContentEquals(BSSID_BYTES, rig.radio.sentOfType<RadioMessage.SetIdentity>().single().mac)
        val start = rig.radio.sentOfType<RadioMessage.StartBeacon>().single()
        assertEquals(ApConfig.DEFAULT_BEACON_INTERVAL_TU, start.intervalTu, "the default beacon interval")
        val beacon = assertNotNull(Ieee80211Frame.parse(start.beacon))
        assertTrue(beacon.isBeacon)
        assertEquals(BSSID, beacon.addr3, "the beacon is the store BSSID's")
        assertContentEquals(
            ByteArray(PAIRING_SSID.encodeToByteArray().size),
            hiddenSsidOf(start.beacon),
            "the SSID is hidden: the real length, every octet zero",
        )

        // A station holding the independently recomputed key associates, and its data is accepted.
        val kart = rig.station(rig.pairingKey())
        rig.associate(kart, PAIRING_SSID)
        assertTrue(rig.ap.associatedStation.value)
        assertTrue(rig.discoverGetsThrough(kart, pn = 1), "the pairing key is the one derived from SHA-256(seed)")
        // Under any other key, including the game network's, the same station is not understood.
        assertFalse(rig.discoverGetsThrough(rig.station(rig.gameKey()), pn = 2), "a game-network key does not open the pairing network")
        assertFalse(rig.discoverGetsThrough(rig.station(rig.pairingKey(OTHER_SEED)), pn = 3), "another seed gives another key")
    }

    @Test
    fun gameServesTheIdentitySsidAndTheKeyDerivedFromTheMasterSecret() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.radio.clearSentMessages()

        rig.ap.beacon(KartNetworkKind.GAME)

        assertEquals(listOf("SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
        assertContentEquals(BSSID_BYTES, rig.radio.sentOfType<RadioMessage.SetIdentity>().single().mac)
        val start = rig.radio.sentOfType<RadioMessage.StartBeacon>().single()
        val beacon = assertNotNull(Ieee80211Frame.parse(start.beacon))
        assertEquals(BSSID, beacon.addr3, "the game network uses the same BSSID the orchestrator gives the network stack")
        assertContentEquals(
            ByteArray(rig.identity.gameSsid.encodeToByteArray().size),
            hiddenSsidOf(start.beacon),
            "the SSID is hidden: the identity SSID's length, every octet zero",
        )

        val kart = rig.station(rig.gameKey())
        rig.associate(kart, rig.identity.gameSsid)
        assertTrue(rig.ap.associatedStation.value)
        assertTrue(rig.discoverGetsThrough(kart, pn = 1), "the game key is the one derived from the master secret and a time-based nonce")
        assertFalse(rig.discoverGetsThrough(rig.station(rig.pairingKey()), pn = 2), "a pairing-network key does not open the game network")
    }

    // ---- beacon: switching and repeating ---------------------------------------------------------

    @Test
    fun switchingFromPairingToGameUpdatesTheBeaconAndDropsTheStation() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork())
        val pairingKart = rig.station(rig.pairingKey())
        rig.associate(pairingKart, PAIRING_SSID)
        rig.radio.clearSentMessages()

        rig.ap.beacon(KartNetworkKind.GAME)

        // Read at once, with no dispatch in between: the flag is already false when the switch returns.
        assertFalse(rig.ap.associatedStation.value, "switching networks resets the station table")
        assertEquals(listOf("UpdateBeacon"), rig.radio.sentMessages.map { it::class.simpleName }, "same BSSID: only the beacon changes")
        val update = rig.radio.sentOfType<RadioMessage.UpdateBeacon>().single()
        assertEquals(
            rig.identity.gameSsid.encodeToByteArray().size,
            hiddenSsidOf(update.beacon).size,
            "the new beacon is the game network's",
        )
        assertFalse(rig.discoverGetsThrough(pairingKart, pn = 1), "the dropped station is no longer served")

        // The kart joins the game network with the game key.
        val gameKart = rig.station(rig.gameKey())
        rig.associate(gameKart, rig.identity.gameSsid)
        assertTrue(rig.discoverGetsThrough(gameKart, pn = 1))
    }

    @Test
    fun askingForTheGameNetworkAgainChangesNothingAndKeepsTheStation() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        val kart = rig.station(rig.gameKey())
        rig.associate(kart, rig.identity.gameSsid)
        rig.radio.clearSentMessages()

        // The orchestrator asks on every recovery pass.
        repeat(REPEATED_REQUESTS) { rig.ap.beacon(KartNetworkKind.GAME) }

        assertTrue(rig.radio.sentMessages.isEmpty(), "no new nonce, no new beacon, no new identity")
        assertTrue(rig.ap.associatedStation.value, "the associated kart is not cut off")
        assertTrue(rig.discoverGetsThrough(kart, pn = 1), "and its key is unchanged")
    }

    @Test
    fun anEqualPairingNetworkIsANoOpButAnotherSeedOrSsidIsServedAgain() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork())
        val kart = rig.station(rig.pairingKey())
        rig.associate(kart, PAIRING_SSID)
        rig.radio.clearSentMessages()

        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork(seed = SEED.copyOf()))
        assertTrue(rig.radio.sentMessages.isEmpty(), "the same network, compared by content, is not served twice")
        assertTrue(rig.ap.associatedStation.value)

        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork(seed = OTHER_SEED))
        assertFalse(rig.ap.associatedStation.value, "a new seed is a new network, and the flag says so at once")
        assertEquals(listOf("UpdateBeacon"), rig.radio.sentMessages.map { it::class.simpleName })

        rig.radio.clearSentMessages()
        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork(seed = OTHER_SEED, ssid = "mklhc-pair_other"))
        assertEquals(listOf("UpdateBeacon"), rig.radio.sentMessages.map { it::class.simpleName }, "a new SSID is a new network")
    }

    @Test
    fun concurrentRequestsForTheSameNetworkServeItOnce() = runTest {
        // startBeacon takes virtual time, so without the lock the second request would run while the first is mid-way.
        val rig = Rig(this, radioLink = { fake -> SlowBeaconRadioLink(fake) })
        rig.start()
        rig.radio.clearSentMessages()

        val first = launch { rig.ap.beacon(KartNetworkKind.GAME) }
        val second = launch { rig.ap.beacon(KartNetworkKind.GAME) }
        advanceTimeBy(1.seconds)
        runCurrent()

        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(listOf("SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aFailedBeaconIsRedoneInFullOnTheNextRequest() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.radio.clearSentMessages()

        rig.radio.failure = IllegalStateException("radiod is not connected")
        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.GAME) }
        rig.radio.failure = null
        rig.ap.beacon(KartNetworkKind.GAME)

        assertEquals(
            listOf("SetIdentity", "StartBeacon"),
            rig.radio.sentMessages.map { it::class.simpleName },
            "a request that failed half way is not mistaken for a served network",
        )
    }

    @Test
    fun aFailedSwitchToGameLetsThePairingNetworkBeServedAgainInFull() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork())
        rig.radio.clearSentMessages()

        // The switch dies on UPDATE_BEACON, the only command it sends (the BSSID does not change).
        rig.radio.failure = IllegalStateException("radiod is not connected")
        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.GAME) }
        rig.radio.failure = null

        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork())

        assertEquals(
            listOf("UpdateBeacon"),
            rig.radio.sentMessages.map { it::class.simpleName },
            "the pairing network is served again, not taken for the network still served",
        )
        val update = rig.radio.sentOfType<RadioMessage.UpdateBeacon>().single()
        assertEquals(PAIRING_SSID.encodeToByteArray().size, hiddenSsidOf(update.beacon).size, "the beacon is the pairing network's")
        val kart = rig.station(rig.pairingKey())
        rig.associate(kart, PAIRING_SSID)
        assertTrue(rig.discoverGetsThrough(kart, pn = 1), "and the access point answers under the pairing key")
    }

    // ---- stop ----------------------------------------------------------------------------------

    @Test
    fun stopStopsTheBeaconShutsRadiodDownAndClearsTheStation() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.associate(rig.station(rig.gameKey()), rig.identity.gameSsid)
        rig.radio.clearSentMessages()

        rig.ap.stop()

        // Read at once, with no dispatch in between: the flag is already false when stop() returns.
        assertFalse(rig.ap.associatedStation.value)
        assertEquals(listOf<RadioMessage.AppToRadiod>(RadioMessage.StopBeacon, RadioMessage.Shutdown), rig.radio.sentMessages)
        assertFailsWith<IllegalStateException>("a stopped radio cannot beacon") { rig.ap.beacon(KartNetworkKind.GAME) }
    }

    @Test
    fun stopIsSafeBeforeStartAndTwiceAndNeverThrows() = runTest {
        val rig = Rig(this)

        rig.ap.stop() // never started
        assertTrue(rig.radio.sentMessages.isEmpty(), "there is nothing to stop")

        rig.start()
        rig.ap.stop()
        rig.radio.clearSentMessages()
        rig.ap.stop() // twice
        assertTrue(rig.radio.sentMessages.isEmpty())

        // A radiod that is already gone does not make stop() throw.
        assertNull(rig.start())
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.radio.failure = IllegalStateException("radiod is not connected")
        rig.ap.stop()
        assertTrue(rig.logs.allText().contains("stopping the beacon failed"), rig.logs.allText())
        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.GAME) }
    }

    @Test
    fun startingAgainAfterStopServesTheNetworkFromScratch() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.ap.stop()
        rig.radio.clearSentMessages()

        assertNull(rig.start())
        rig.ap.beacon(KartNetworkKind.GAME)

        assertEquals(
            listOf("OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            rig.radio.sentMessages.map { it::class.simpleName },
            "the stopped network is forgotten: the new radio gets its identity and a started beacon",
        )
    }

    // ---- a relaunched radiod -------------------------------------------------------------------

    @Test
    fun aReconnectWhileServingPairingBringsTheRadioUpAgainAndServesTheSameNetwork() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        assertEquals(2, rig.radio.sentMessages.size, "a connection that is up when the radio starts is no reconnect")
        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork())
        val firstStart = rig.radio.sentOfType<RadioMessage.StartBeacon>().single()
        rig.associate(rig.station(rig.pairingKey()), PAIRING_SSID)
        rig.radio.clearSentMessages()

        connection.value = false
        runCurrent()
        assertTrue(rig.radio.sentMessages.isEmpty(), "the disconnect alone does nothing")
        connection.value = true
        runCurrent()

        assertEquals(
            listOf<RadioMessage.AppToRadiod>(
                RadioMessage.OpenDevice(ADAPTER.vid, ADAPTER.pid),
                RadioMessage.Init(CHANNEL, RADIO_OPTIONS.bandwidthMhz, RADIO_OPTIONS.txPowerIndex),
            ),
            rig.radio.sentMessages,
            "the new radiod gets the adapter and the channel, and nothing is served before its READY",
        )
        assertFalse(rig.ap.associatedStation.value, "bringing the radio up drops the station: the orchestrator sees a departure")

        rig.radiodReady()

        assertEquals(listOf("OpenDevice", "Init", "SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
        assertContentEquals(BSSID_BYTES, rig.radio.sentOfType<RadioMessage.SetIdentity>().single().mac)
        val restarted = rig.radio.sentOfType<RadioMessage.StartBeacon>().single()
        assertEquals(firstStart.intervalTu, restarted.intervalTu)
        assertContentEquals(firstStart.beacon, restarted.beacon, "the same configuration is served again, not a rebuilt one")

        // The kart rejoins with the key it already holds, and the pairing network still counts as the one served.
        val kart = rig.station(rig.pairingKey())
        rig.associate(kart, PAIRING_SSID)
        assertTrue(rig.discoverGetsThrough(kart, pn = 1))
        rig.radio.clearSentMessages()
        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork(seed = SEED.copyOf()))
        assertTrue(rig.radio.sentMessages.isEmpty(), "the same request is still a no-op, so the kart is not cut off again")
    }

    @Test
    fun aReconnectWhileServingTheGameNetworkServesItAgainWithTheSameKey() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        val firstStart = rig.radio.sentOfType<RadioMessage.StartBeacon>().single()
        rig.associate(rig.station(rig.gameKey()), rig.identity.gameSsid)
        rig.radio.clearSentMessages()

        rig.relaunchRadiod(connection)
        rig.radiodReady()

        assertEquals(listOf("OpenDevice", "Init", "SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
        assertContentEquals(
            firstStart.beacon,
            rig.radio.sentOfType<RadioMessage.StartBeacon>().single().beacon,
            "a rebuilt game network would carry the next time-based nonce, so another beacon",
        )
        assertFalse(rig.ap.associatedStation.value, "nobody is associated until the kart joins again")

        // The key is the first nonce's: a rebuilt configuration would use the next one and reject this kart.
        val kart = rig.station(rig.gameKey())
        rig.associate(kart, rig.identity.gameSsid)
        assertTrue(rig.discoverGetsThrough(kart, pn = 1), "the kart rejoins with the key it already has")
        rig.radio.clearSentMessages()
        repeat(REPEATED_REQUESTS) { rig.ap.beacon(KartNetworkKind.GAME) } // the orchestrator's recovery pass
        assertTrue(rig.radio.sentMessages.isEmpty(), "and the recovery pass finds the game network served")
        assertTrue(rig.ap.associatedStation.value)
    }

    @Test
    fun aReconnectWhileStartedButBeforeAnyBeaconOnlyBringsTheRadioUp() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.radio.clearSentMessages()

        rig.relaunchRadiod(connection)
        rig.radiodReady()

        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName }, "there was no network to serve again")

        rig.radio.clearSentMessages()
        rig.ap.beacon(KartNetworkKind.GAME)
        assertEquals(listOf("SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName }, "the radio is usable")
    }

    @Test
    fun aReconnectWhileNotStartedDoesNothing() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)

        rig.relaunchRadiod(connection)
        assertTrue(rig.radio.sentMessages.isEmpty(), "never started")

        assertNotNull(rig.start(answer = RadioMessage.Error(code = 7u, text = "cannot open the adapter")))
        rig.radio.clearSentMessages()
        rig.relaunchRadiod(connection)
        assertTrue(rig.radio.sentMessages.isEmpty(), "a start that failed leaves nothing to bring up")

        assertNull(rig.start())
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.ap.stop()
        rig.radio.clearSentMessages()
        rig.relaunchRadiod(connection)
        assertTrue(rig.radio.sentMessages.isEmpty(), "a stopped radio stays down")
        assertFalse(rig.logs.allText().contains("redoBringUpAfterReconnect"), rig.logs.allText())
    }

    @Test
    fun aDisconnectAloneChangesNothing() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.associate(rig.station(rig.gameKey()), rig.identity.gameSsid)
        rig.radio.clearSentMessages()

        connection.value = false
        runCurrent()
        advanceTimeBy(1.seconds)

        assertTrue(rig.radio.sentMessages.isEmpty(), "the radio is not touched until radiod is back")
        assertTrue(rig.ap.associatedStation.value, "the station is dropped by the bring-up, not by the disconnect")
        rig.ap.beacon(KartNetworkKind.GAME)
        assertTrue(rig.radio.sentMessages.isEmpty(), "the game network still counts as served")
    }

    @Test
    fun aRadiodRelaunchedDuringStartsBringUpGetsAFreshBringUpWhoseReadyCompletesStart() = runTest {
        // No awaitRadioConnected: the wait is derived from the connection. start() waits for radiod to connect, and
        // that false -> true change is the one it waited for, not a reconnect.
        val connection = MutableStateFlow(false)
        val rig = Rig(this, radioConnection = connection)
        val start = rig.launchStart()
        runCurrent()
        assertTrue(rig.radio.sentMessages.isEmpty(), "start waits for radiod")

        connection.value = true
        runCurrent()
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })

        // radiod crashes during its INIT: no READY will come for these commands, and start() does not wait for one.
        connection.value = false
        runCurrent()
        assertNull(start.outcome, "start waits for radiod to come back")
        assertEquals(2, rig.radio.sentMessages.size, "nothing is sent to a radiod that is gone")
        assertTrue(rig.logs.allText().contains("start(): radiod disconnected during the bring-up, waiting for it to reconnect"))

        // The supervisor relaunches it: the new radiod knows nothing of the first commands and gets its own.
        connection.value = true
        runCurrent()
        assertEquals(listOf("OpenDevice", "Init", "OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
        assertEquals(2, rig.adapters.resolveCount, "the adapter is resolved again for the radiod that replaced the first")
        assertNull(start.outcome, "only the new radiod's READY completes start")

        rig.radiodReady()
        assertTrue(assertNotNull(start.outcome).isSuccess, "${start.outcome}")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(4, rig.radio.sentMessages.size, "the relaunch start() recovered from is not taken for a later reconnect")

        // The radio is usable, and the watcher is alive from here on: a real reconnect brings the radio up again.
        rig.radio.clearSentMessages()
        rig.ap.beacon(KartNetworkKind.GAME)
        assertEquals(listOf("SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
        rig.radio.clearSentMessages()
        rig.relaunchRadiod(connection)
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aSecondDisconnectDuringStartsBringUpFailsStartAndLeavesItRetryable() = runTest {
        val connection = MutableStateFlow(true)
        var waits = 0
        // An explicit wait is still the one used, before the bring-up and after a disconnect.
        val rig = Rig(this, awaitRadioConnected = { waits++; connection.first { it } }, radioConnection = connection)
        val start = rig.launchStart()
        runCurrent()

        rig.relaunchRadiod(connection) // the first disconnect: start() brings the radio up on the new radiod
        assertEquals(listOf("OpenDevice", "Init", "OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
        assertNull(start.outcome)

        connection.value = false // and that radiod goes away too
        runCurrent()

        val failure = assertNotNull(start.outcome).exceptionOrNull()
        assertTrue(failure is IllegalStateException, "a clear failure, not a cancellation: $failure")
        assertTrue("radiod disconnected again during the bring-up" in failure.message.orEmpty(), failure.message)
        assertTrue(
            rig.logs.allText().contains("start(): the radio did not come up, shutting the access point down again -> message="),
            rig.logs.allText(),
        )
        assertEquals(
            listOf("OpenDevice", "Init", "OpenDevice", "Init", "StopBeacon", "Shutdown"),
            rig.radio.sentMessages.map { it::class.simpleName },
            "the access point is shut down again",
        )
        assertEquals(2, waits, "one wait before the first bring-up, one for the radiod that replaced it")
        assertFailsWith<IllegalStateException>("a failed start leaves the radio not started") { rig.ap.beacon(KartNetworkKind.GAME) }

        connection.value = true
        runCurrent()
        assertEquals(6, rig.radio.sentMessages.size, "nothing watches the connection of a radio that did not start")
        rig.radio.clearSentMessages()
        assertNull(rig.start(), "the next attempt starts clean")
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aStartRightAfterAStopComesUpOnTheRadiodThatReplacesTheOneShuttingDown() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.ap.stop()
        rig.radio.clearSentMessages()

        // The old radiod got SHUTDOWN but is still connected, so start() does not wait and talks to it.
        val start = rig.launchStart()
        runCurrent()
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })

        connection.value = false // it exits; the supervisor relaunches it after its backoff
        runCurrent()
        advanceTimeBy(SUPERVISOR_RESTART_BACKOFF)
        runCurrent()
        assertNull(start.outcome, "start waits for the new radiod, not for a READY the old one will never send")
        connection.value = true
        runCurrent()
        rig.radiodReady()

        assertTrue(assertNotNull(start.outcome).isSuccess, "${start.outcome}")
        assertEquals(listOf("OpenDevice", "Init", "OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
        rig.radio.clearSentMessages()
        rig.ap.beacon(KartNetworkKind.GAME)
        assertEquals(
            listOf("SetIdentity", "StartBeacon"),
            rig.radio.sentMessages.map { it::class.simpleName },
            "the new radiod gets its identity and a started beacon",
        )
        val kart = rig.station(rig.gameKey(FIRST_GAME_NONCE_INDEX + 1))
        rig.associate(kart, rig.identity.gameSsid)
        assertTrue(rig.discoverGetsThrough(kart, pn = 1), "the game network is served from scratch, with the next nonce")
    }

    @Test
    fun aCommandRadiodCanNoLongerTakeDuringStartCountsAsTheDisconnect() = runTest {
        // radiod goes away as OPEN_DEVICE is sent: the send fails before the watcher has seen the flag drop.
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioLink = { fake -> DisconnectingOpenRadioLink(fake, connection) }, radioConnection = connection)
        val start = rig.launchStart()
        runCurrent()

        assertNull(start.outcome, "a send that fails because radiod is gone is waited out like the disconnect it is")
        assertTrue(rig.radio.sentMessages.isEmpty())
        connection.value = true
        runCurrent()
        rig.radiodReady()

        assertTrue(assertNotNull(start.outcome).isSuccess, "${start.outcome}")
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun cancellingStartWhileItWaitsForRadiodToComeBackShutsTheAccessPointDown() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        val start = launch { rig.ap.start() }
        runCurrent()
        connection.value = false
        runCurrent()

        start.cancel()
        runCurrent()

        assertTrue(start.isCancelled, "cancellation propagates out of start()")
        assertEquals(listOf("OpenDevice", "Init", "StopBeacon", "Shutdown"), rig.radio.sentMessages.map { it::class.simpleName })
        assertFalse(rig.logs.allText().contains("did not come up"), "a cancellation is not reported as a radio failure")
        connection.value = true
        rig.radio.clearSentMessages()
        assertNull(rig.start(), "a cancelled start can be tried again")
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aFailingReBringUpIsLoggedAndTheNextReconnectServesTheInterruptedNetwork() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        val firstStart = rig.radio.sentOfType<RadioMessage.StartBeacon>().single()
        rig.radio.clearSentMessages()

        rig.relaunchRadiod(connection)
        rig.radio.emitEvent(RadioMessage.Error(code = 7u, text = "cannot open the adapter"))
        runCurrent()

        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName }, "nothing is served on a radio that did not come up")
        val failure = rig.logs.entries.single { it.severity == Severity.Error && "left for the next reconnect" in it.message }
        assertTrue("redoBringUpAfterReconnect(): " in failure.message && "-> message=" in failure.message, failure.message)
        assertTrue("cannot open the adapter" in failure.message, failure.message)

        // The radio is off the air, and a request says so instead of being taken for a no-op.
        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.GAME) }
        assertEquals(2, rig.radio.sentMessages.size)

        // The next reconnect works, and serves what the radio served before it was lost.
        rig.relaunchRadiod(connection)
        rig.radiodReady()
        assertEquals(
            listOf("OpenDevice", "Init", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            rig.radio.sentMessages.map { it::class.simpleName },
        )
        assertContentEquals(firstStart.beacon, rig.radio.sentOfType<RadioMessage.StartBeacon>().single().beacon)
        val kart = rig.station(rig.gameKey())
        rig.associate(kart, rig.identity.gameSsid)
        assertTrue(rig.discoverGetsThrough(kart, pn = 1), "the kart's key is still the one of the first game network")
        rig.radio.clearSentMessages()
        rig.ap.beacon(KartNetworkKind.GAME)
        assertTrue(rig.radio.sentMessages.isEmpty(), "and the network counts as served again")
    }

    @Test
    fun stopDoesNotWaitBehindAReBringUpInFlight() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.radio.clearSentMessages()
        rig.relaunchRadiod(connection)
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName }, "waiting for READY")

        val stop = launch { rig.ap.stop() }
        runCurrent()

        assertTrue(stop.isCompleted, "stop ends the re-bring-up instead of waiting for a READY behind it")
        assertEquals(listOf("OpenDevice", "Init", "StopBeacon", "Shutdown"), rig.radio.sentMessages.map { it::class.simpleName })
        rig.radiodReady()
        assertEquals(4, rig.radio.sentMessages.size, "a READY that comes late changes nothing")
        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.GAME) }
        rig.radio.clearSentMessages()
        rig.relaunchRadiod(connection)
        assertTrue(rig.radio.sentMessages.isEmpty(), "the watcher ended with stop")
        assertFalse(rig.logs.allText().contains("left for the next reconnect"), "a stop is not a failed bring-up")
    }

    @Test
    fun aDisconnectDuringAReBringUpEndsItAndTheNextReconnectStartsOver() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        val firstStart = rig.radio.sentOfType<RadioMessage.StartBeacon>().single()
        rig.radio.clearSentMessages()
        rig.relaunchRadiod(connection) // the re-bring-up waits for READY

        connection.value = false // and radiod is gone again
        runCurrent()
        val request = launch { runCatching { rig.ap.beacon(KartNetworkKind.GAME) } }
        runCurrent()

        assertTrue(request.isCompleted, "the abandoned bring-up no longer holds the lifecycle lock (a request is answered at once)")
        assertFalse(rig.logs.allText().contains("left for the next reconnect"), "an abandoned bring-up is not a failure")

        connection.value = true
        runCurrent()
        rig.radiodReady()

        assertEquals(
            listOf("OpenDevice", "Init", "OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            rig.radio.sentMessages.map { it::class.simpleName },
        )
        assertContentEquals(firstStart.beacon, rig.radio.sentOfType<RadioMessage.StartBeacon>().single().beacon)
    }

    @Test
    fun theWatcherEndsWhenItsScopeEnds() = runTest {
        val connection = MutableStateFlow(true)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val rig = Rig(this, radioConnection = connection, scope = scope)
        rig.start()
        rig.radio.clearSentMessages()

        scope.cancel()
        runCurrent()
        rig.relaunchRadiod(connection)

        assertTrue(rig.radio.sentMessages.isEmpty(), "nobody reacts to a reconnect once the scope is gone")
    }

    // ---- the adapter ---------------------------------------------------------------------------

    @Test
    fun startResolvesTheAdapterRightBeforeTheBringUpAndReportsItInUseUntilStop() = runTest {
        val rig = Rig(this)
        rig.adapters.setDevices(listOf(DetectedUsbDevice(OTHER_ADAPTER, bus = 2, address = 3, access = UsbDeviceAccess.OPENABLE)))

        assertNull(rig.start())

        assertEquals(RadioMessage.OpenDevice(OTHER_ADAPTER.vid, OTHER_ADAPTER.pid), rig.radio.sentMessages.first())
        assertEquals(1, rig.adapters.resolveCount)
        assertEquals(listOf<UsbId?>(OTHER_ADAPTER), rig.adapters.activeReports, "reported once the radio is up")
        assertTrue(rig.logs.allText().contains("start(): radio started -> channel=$CHANNEL adapter=$OTHER_ADAPTER"), rig.logs.allText())

        assertNull(rig.start(answer = null))
        assertEquals(1, rig.adapters.resolveCount, "a started radio is not resolved again")

        rig.ap.stop()
        assertEquals(listOf(OTHER_ADAPTER, null), rig.adapters.activeReports, "and reported down when it stops")
    }

    @Test
    fun aStartWithNoUsableAdapterFailsBeforeTouchingTheRadio() = runTest {
        val rig = Rig(this)
        rig.adapters.setDevices(emptyList())

        val failure = assertIs<AdapterUnavailableException>(rig.start(answer = null), "the reason reaches the orchestrator")

        assertEquals(UnavailableReason.NO_COMPATIBLE_DEVICE, failure.reason)
        assertTrue(rig.radio.sentMessages.isEmpty(), "nothing is opened, and radiod is not shut down for nothing")
        assertTrue(rig.adapters.activeReports.isEmpty(), "no resolution returned, so there is nothing to report")
        assertTrue(rig.logs.allText().contains("start(): the radio did not come up"), rig.logs.allText())
        assertFailsWith<IllegalStateException> { rig.ap.beacon(KartNetworkKind.GAME) }

        rig.adapters.setDevices(listOf(DetectedUsbDevice(ADAPTER, bus = 1, address = 4, access = UsbDeviceAccess.OPENABLE)))
        assertNull(rig.start(), "plugged in, the next start works")
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aBringUpThatFailsAfterTheResolutionReportsTheRadioDown() = runTest {
        val rig = Rig(this)

        assertNotNull(rig.start(answer = RadioMessage.Error(code = 10u, text = "cannot open the adapter")))

        assertEquals(1, rig.adapters.resolveCount)
        assertEquals(listOf<UsbId?>(null), rig.adapters.activeReports, "the scans resume, so the screen shows the adapter again")
    }

    @Test
    fun aReconnectResolvesTheAdapterAgainBeforeTheReBringUp() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.radio.clearSentMessages()
        // The first adapter was unplugged while radiod was away, and the other one is there.
        rig.adapters.setDevices(listOf(DetectedUsbDevice(OTHER_ADAPTER, bus = 2, address = 3, access = UsbDeviceAccess.OPENABLE)))

        rig.relaunchRadiod(connection)

        assertEquals(RadioMessage.OpenDevice(OTHER_ADAPTER.vid, OTHER_ADAPTER.pid), rig.radio.sentMessages.first())
        assertEquals(2, rig.adapters.resolveCount)
        rig.radiodReady()
        assertEquals(listOf(ADAPTER, OTHER_ADAPTER), rig.adapters.activeReports)
        assertEquals(listOf("OpenDevice", "Init", "SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aReBringUpWithNoUsableAdapterIsLoggedAndTheNextReconnectTriesAgain() = runTest {
        val connection = MutableStateFlow(true)
        val rig = Rig(this, radioConnection = connection)
        rig.start()
        rig.ap.beacon(KartNetworkKind.GAME)
        val firstStart = rig.radio.sentOfType<RadioMessage.StartBeacon>().single()
        rig.radio.clearSentMessages()
        rig.adapters.setDevices(emptyList())

        rig.relaunchRadiod(connection)

        assertTrue(rig.radio.sentMessages.isEmpty(), "nothing is opened without an adapter")
        val failure = rig.logs.entries.single { it.severity == Severity.Error && "left for the next reconnect" in it.message }
        assertTrue("NO_COMPATIBLE_DEVICE" in failure.message, failure.message)
        assertEquals(listOf(ADAPTER, null), rig.adapters.activeReports, "the radio is down, so the scans resume")

        rig.adapters.setDevices(listOf(DetectedUsbDevice(ADAPTER, bus = 1, address = 4, access = UsbDeviceAccess.OPENABLE)))
        rig.relaunchRadiod(connection)
        rig.radiodReady()
        assertEquals(listOf("OpenDevice", "Init", "SetIdentity", "StartBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
        assertContentEquals(firstStart.beacon, rig.radio.sentOfType<RadioMessage.StartBeacon>().single().beacon)
        assertEquals(listOf(ADAPTER, null, ADAPTER), rig.adapters.activeReports)
    }

    // ---- platform seams ------------------------------------------------------------------------

    @Test
    fun theAdapterStateAndTheFrameLinkArePassedThrough() = runTest {
        val adapter = MutableStateFlow(AdapterState.PERMISSION_REQUIRED)
        val rig = Rig(this, adapterState = adapter)

        assertSame(adapter, rig.ap.adapterState)
        assertSame(rig.accessPoint, rig.ap.frameLink, "one link for the life of the object")
        adapter.value = AdapterState.READY
        assertEquals(AdapterState.READY, rig.ap.adapterState.value)

        rig.start()
        rig.ap.stop()
        assertSame(rig.accessPoint, rig.ap.frameLink, "and it survives stop and start")
        assertFalse(rig.ap.associatedStation.value)
    }

    @Test
    fun requestAdapterPermissionCallsThePlatformAndSurvivesItsFailure() = runTest {
        var requests = 0
        val working = Rig(this, onRequestAdapterPermission = { requests++ })
        working.ap.requestAdapterPermission()
        assertEquals(1, requests)

        val failing = Rig(this, onRequestAdapterPermission = { throw IllegalStateException("no activity to ask from") })
        failing.ap.requestAdapterPermission() // must not throw
        assertTrue(failing.logs.allText().contains("the platform request failed"), failing.logs.allText())
    }

    // ---- secrets -------------------------------------------------------------------------------

    @Test
    fun neitherTheSeedNorAnyKeyNorTheMasterSecretIsLogged() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.ap.beacon(KartNetworkKind.PAIRING, pairingNetwork())
        rig.associate(rig.station(rig.pairingKey()), PAIRING_SSID)
        rig.ap.beacon(KartNetworkKind.GAME)
        rig.associate(rig.station(rig.gameKey()), rig.identity.gameSsid)
        rig.ap.stop()

        val text = rig.logs.allText().lowercase()
        assertTrue("network served" in text, "the exchange was logged at all: $text")
        val secrets = mapOf(
            "pairing seed" to SEED,
            "pairing PSK" to rig.crypto.sha256(SEED),
            "pairing CCMP key" to rig.pairingKey(),
            "game PSK" to rig.identity.gamePsk,
            "game CCMP key" to rig.gameKey(),
            "master secret" to MASTER_SECRET,
        )
        for ((name, secret) in secrets) {
            assertFalse(hex(secret) in text, "the $name must never be logged")
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun pairingNetwork(
        ssid: String = PAIRING_SSID,
        seed: ByteArray = SEED,
        channel: Int = CHANNEL,
    ): PairingNetwork = PairingNetwork(ssid, seed, channel)

    /** The SSID element of a beacon frame: all zeros of the real SSID's length when the network is hidden. */
    private fun hiddenSsidOf(beacon: ByteArray): ByteArray {
        val frame = checkNotNull(Ieee80211Frame.parse(beacon)) { "not an 802.11 frame" }
        val fields = checkNotNull(frame.beaconFields()) { "not a beacon" }
        return checkNotNull(InformationElements.find(fields.elements, InformationElements.SSID)) { "no SSID element" }.value
    }

    /** A radio whose first INIT fails with an Error, not an Exception, like a helper that crashed mid-command. */
    private class CrashingInitRadioLink(private val fake: FakeRadioLink) : RadioLink by fake {
        private var crashed = false

        override suspend fun init(channel: Int, bandwidthMhz: Int, txPowerIndex: Int) {
            if (!crashed) {
                crashed = true
                throw HelperCrash()
            }
            fake.init(channel, bandwidthMhz, txPowerIndex)
        }
    }

    private class HelperCrash : Error("the radio helper crashed")

    /**
     * A radio whose first OPEN_DEVICE finds radiod gone: the connection flag drops and the command fails, as
     * `DesktopRadioLink` fails a command once radiod has disconnected. Later commands reach radiod.
     */
    private class DisconnectingOpenRadioLink(
        private val fake: FakeRadioLink,
        private val connection: MutableStateFlow<Boolean>,
    ) : RadioLink by fake {
        private var disconnected = false

        override suspend fun openDevice(vid: Int, pid: Int) {
            if (!disconnected) {
                disconnected = true
                connection.value = false
                throw IllegalStateException("send(): radiod is not connected -> type=OpenDevice")
            }
            fake.openDevice(vid, pid)
        }
    }

    /** A [KartRadioAp.start] launched in the background: [outcome] stays null until it finished. */
    private class PendingStart {
        var outcome: Result<Unit>? = null
    }

    /** A radio whose START_BEACON takes virtual time, so two requests can overlap unless something serialises them. */
    private class SlowBeaconRadioLink(private val fake: FakeRadioLink) : RadioLink by fake {
        override suspend fun startBeacon(intervalTu: Int, beacon: ByteArray) {
            delay(START_BEACON_DELAY_MS)
            fake.startBeacon(intervalTu, beacon)
        }
    }

    /**
     * Everything one test needs: the pairing store with a pinned identity, a deterministic nonce table, a real access
     * point on a [FakeRadioLink] (optionally behind a wrapper), the object under test and a collector of the frames
     * that reach the network stack's side of the link. One log captures both layers.
     */
    private class Rig(
        private val test: TestScope,
        radioLink: (FakeRadioLink) -> RadioLink = { it },
        awaitRadioConnected: (suspend () -> Unit)? = null,
        onRequestAdapterPermission: () -> Unit = {},
        adapterState: StateFlow<AdapterState> = MutableStateFlow(AdapterState.READY),
        radioConnection: StateFlow<Boolean>? = null,
        scope: CoroutineScope = test.backgroundScope,
    ) {
        val crypto = CryptoPrimitives()
        val radio = FakeRadioLink()
        val logs = CapturingLogWriter()
        val store = InMemoryPairingStore(crypto, masterSecret = MASTER_SECRET, apBssid = BSSID_BYTES, channel = CHANNEL)

        // The pairing network draws a random index (the id below); the game network's first draw is time-based, at
        // time 0 with a sequence counter that starts at GAME_SEQUENCE_START: index (0 shl 4) or (start + 1).
        val nonceTable = NonceTable(
            table = ByteArray(NONCE_COUNT * NONCE_BYTES),
            nowEpochSeconds = { 0L },
            randomInt = { bound -> if (bound == NONCE_COUNT) PAIRING_NONCE_INDEX else GAME_SEQUENCE_START },
        )
        // Timed on the test's virtual clock, like everything else here, so the inactivity timeout follows virtual time.
        val accessPoint = RadioAccessPoint(
            radio = radioLink(radio),
            options = RADIO_OPTIONS,
            primitives = crypto,
            log = capturingLogger(logs, "RadioAccessPoint"),
            timeSource = test.testScheduler.timeSource,
        )
        val adapters = FakeUsbAdapterRepository()
        val ap = RadioKartRadioAp(
            accessPoint = accessPoint,
            pairingStore = store,
            scope = scope,
            adapterState = adapterState,
            adapterRepository = adapters,
            awaitRadioConnected = awaitRadioConnected,
            radioConnection = radioConnection,
            onRequestAdapterPermission = onRequestAdapterPermission,
            crypto = crypto,
            nonceTable = nonceTable,
            log = capturingLogger(logs, "RadioKartRadioAp"),
        )
        val identity = deriveKartIdentity(MASTER_SECRET, crypto)
        private val inbound = ArrayList<LinkPayload>()

        init {
            test.backgroundScope.launch { ap.frameLink.inbound.collect { inbound += it } }
        }

        /**
         * Runs [KartRadioAp.start] with radiod answering [answer] (nothing when null); returns what it threw, or null
         * when it succeeded.
         */
        fun start(answer: RadioMessage.RadiodToApp? = READY): Throwable? {
            val pending = launchStart()
            test.runCurrent()
            if (answer != null) radio.emitEvent(answer)
            test.runCurrent()
            return checkNotNull(pending.outcome) { "start() did not finish" }.exceptionOrNull()
        }

        /** Launches [KartRadioAp.start] in the background, for a test that plays radiod step by step. */
        fun launchStart(): PendingStart {
            val pending = PendingStart()
            test.backgroundScope.launch { pending.outcome = runCatching { ap.start() } }
            return pending
        }

        /** Plays radiod answering a bring-up with READY, and lets the access point (and whoever waits for it) react. */
        fun radiodReady() {
            radio.emitEvent(READY)
            test.runCurrent()
        }

        /**
         * Plays radiod going away and the supervisor relaunching it: the link's flag drops and rises again, each change
         * seen by the collectors before the next one.
         */
        fun relaunchRadiod(connection: MutableStateFlow<Boolean>) {
            connection.value = false
            test.runCurrent()
            connection.value = true
            test.runCurrent()
        }

        /** The CCMP key of the pairing network served for [seed]: the nonce of the random draw and `SHA-256(seed)`. */
        fun pairingKey(seed: ByteArray = SEED): ByteArray =
            Lp2pKeys(crypto).deriveCcmpKey(nonceTable.getPlaintextNonce(PAIRING_NONCE_INDEX), crypto.sha256(seed))

        /** The CCMP key of the game network at the nonce [index]: the identity's pre-shared key under that nonce. */
        fun gameKey(index: Int = FIRST_GAME_NONCE_INDEX): ByteArray =
            Lp2pKeys(crypto).deriveCcmpKey(nonceTable.getPlaintextNonce(index), identity.gamePsk)

        /** A kart that holds [key], at the usual address. */
        fun station(key: ByteArray) = FakeStation(key, BSSID, STATION, crypto)

        /** Plays radiod delivering [frame] and lets the access point's receive collector react. */
        fun receive(frame: ByteArray) {
            radio.emitRxFrame(RadioFrame(rssiDbm = RSSI_DBM, rate = 0, tsf = 0uL, frame = frame))
            test.runCurrent()
        }

        /** Walks [station] through authentication and association with [ssid]. */
        fun associate(station: FakeStation, ssid: String) {
            receive(station.authRequest())
            receive(station.assocRequest(ssid))
            check(ap.associatedStation.value) { "the station did not associate" }
        }

        /** The kart sends a DHCP DISCOVER numbered [pn]; true when it came out on the link (CCMP accepted it). */
        fun discoverGetsThrough(station: FakeStation, pn: Long): Boolean {
            val before = inbound.size
            receive(station.dhcpFrame(station.discover(), pn))
            return inbound.size == before + 1 && inbound.last().etherType == EtherType.IPV4
        }
    }

    private companion object {
        val READY = RadioMessage.Ready("rtl8812cu", "fw-1", "devourer-test")
        val RADIO_OPTIONS = RadioOptions()

        // What the fake adapter repository resolves by default, and another compatible adapter.
        val ADAPTER: UsbId = FakeUsbAdapterRepository.DEFAULT_ADAPTER
        val OTHER_ADAPTER = UsbId(0x0BDA, 0xC812)

        val MASTER_SECRET = ByteArray(32) { (it + 7).toByte() }
        val SEED = ByteArray(0x10) { (it + 1).toByte() }
        val OTHER_SEED = ByteArray(0x10) { (0x80 + it).toByte() }

        // First octet 0x02: locally administered (bit 1 set), unicast (bit 0 clear).
        val BSSID_BYTES = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)
        val BSSID: MacAddress = MacAddress.fromBytes(BSSID_BYTES)
        val STATION: MacAddress = FakeStation.DEFAULT_MAC

        const val CHANNEL = 11
        const val OTHER_CHANNEL = 1
        const val PAIRING_SSID = "mklhc-pair_AAAAAAAAAAAAAAAA"
        const val RSSI_DBM = -42
        const val REPEATED_REQUESTS = 3
        const val START_BEACON_DELAY_MS = 10L

        // The desktop supervisor's shortest wait before it relaunches radiod.
        val SUPERVISOR_RESTART_BACKOFF = 500.milliseconds

        const val NONCE_COUNT = 0x10000
        const val NONCE_BYTES = 16
        const val PAIRING_NONCE_INDEX = 0x0A0B
        const val GAME_SEQUENCE_START = 4
        const val FIRST_GAME_NONCE_INDEX = GAME_SEQUENCE_START + 1
    }
}
