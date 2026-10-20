package com.mklhc.core.wifi_ap.ap

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.Arp
import com.mklhc.core.netstack.ArpPacket
import com.mklhc.core.netstack.DhcpMessageType
import com.mklhc.core.netstack.EtherType
import com.mklhc.core.netstack.Ipv4Address
import com.mklhc.core.netstack.Ipv4Packet
import com.mklhc.core.netstack.LinkPayload
import com.mklhc.core.netstack.NetstackConfig
import com.mklhc.core.netstack.NetstackNode
import com.mklhc.core.netstack.tcp.TcpSegment
import com.mklhc.core.netstack.tcp.TcpState
import com.mklhc.core.radio.FakeRadioLink
import com.mklhc.core.radio.RadioFrame
import com.mklhc.core.radio.RadioLink
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.wifi_ap.crypto.Ccmp
import com.mklhc.core.wifi_ap.mac.FrameControl
import com.mklhc.core.wifi_ap.mac.Ieee80211
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.InformationElements
import com.mklhc.core.wifi_ap.mac.LlcSnap
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.core.wifi_ap.pcap.PcapSink
import com.mklhc.core.wifi_ap.pcap.PcapWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [RadioAccessPoint] against [FakeRadioLink], with a [FakeStation] playing the kart: the bring-up and network
 * lifecycle, the management exchange, the decrypted data path into [RadioAccessPoint.inbound], the encrypted path out
 * of [RadioAccessPoint.send], the capture and the shutdown. The last test joins the access point to a real
 * [NetstackNode] and walks the kart from association to an accepted TCP connection on the RCD port. Everything is
 * deterministic: a fixed network, an injected pcap clock, a seeded TCP initial sequence number and the virtual time of
 * `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent / advanceTimeBy drive the collectors under virtual time
class RadioAccessPointTest {

    // ---- Bring-up -----------------------------------------------------------------------------

    @Test
    fun bringUpOpensTheAdapterInitialisesAndWaitsForReady() = runTest {
        val rig = Rig(this)

        val bringUp = launch { rig.ap.bringUp(CHANNEL) }
        runCurrent()

        // Until radiod says READY only the device open and INIT have been sent, and bringUp is still waiting.
        assertEquals(
            listOf<RadioMessage.AppToRadiod>(RadioMessage.OpenDevice(0x0BDA, 0xC812), RadioMessage.Init(CHANNEL, 20, 0)),
            rig.radio.sentMessages,
        )
        assertTrue(bringUp.isActive)

        rig.radio.emitEvent(READY)
        runCurrent()

        assertFalse(bringUp.isActive)
        assertEquals(2, rig.radio.sentMessages.size, "no identity and no beacon before a network is served")
        assertTrue(rig.logs.has("bringUp(): radio up, ready to serve -> channel=$CHANNEL"), rig.logs.text())
    }

    @Test
    fun bringUpFailsWhenRadiodReportsAnError() = runTest {
        val rig = Rig(this)
        var failure: Throwable? = null

        launch { failure = runCatching { rig.ap.bringUp(CHANNEL) }.exceptionOrNull() }
        runCurrent()
        rig.radio.emitEvent(RadioMessage.Error(code = 7u, text = "cannot open the adapter"))
        runCurrent()

        val thrown = assertNotNull(failure)
        assertTrue(thrown is IllegalStateException)
        assertTrue("cannot open the adapter" in thrown.message.orEmpty())
        assertFailsWith<IllegalStateException>("a failed bring-up cannot serve") { rig.ap.serve(rig.config) }
    }

    @Test
    fun bringUpFailsWhenReadyNeverArrives() = runTest {
        val rig = Rig(this, options = RadioOptions(readyTimeoutMs = 5_000L))
        var failure: Throwable? = null

        launch { failure = runCatching { rig.ap.bringUp(CHANNEL) }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()

        val thrown = assertNotNull(failure)
        assertTrue(thrown is IllegalStateException)
        assertTrue("did not report READY" in thrown.message.orEmpty())
    }

    @Test
    fun stopWhileABringUpWaitsForReadyFailsItAndRecordsNothing() = runTest {
        val rig = Rig(this)
        var failure: Throwable? = null
        val bringUp = launch { failure = runCatching { rig.ap.bringUp(CHANNEL) }.exceptionOrNull() }
        runCurrent()
        assertTrue(bringUp.isActive, "waiting for READY")

        rig.ap.stop()
        // Subscribed again, so radiod's late READY does reach the access point.
        rig.ap.start(backgroundScope)
        rig.radio.emitEvent(READY)
        runCurrent()

        val thrown = assertNotNull(failure, "the bring-up does not outlive the stop")
        assertTrue(thrown is IllegalStateException, "a plain failure, not a cancellation: $thrown")
        assertTrue("stopped during the bring-up" in thrown.message.orEmpty(), thrown.message)
        assertFalse(rig.logs.has("radio up, ready to serve"), rig.logs.text())
        assertFailsWith<IllegalStateException>("nothing was recorded, so the radio is not up") { rig.ap.serve(rig.config) }
        assertEquals(
            listOf("OpenDevice", "Init", "StopBeacon", "Shutdown"),
            rig.radio.sentMessages.map { it::class.simpleName },
            "no identity and no beacon after the shutdown",
        )
    }

    @Test
    fun aReadyThatArrivesWhileStopRunsIsNotRecorded() = runTest {
        // The READY reaches the waiting bring-up after stop() began but before it cancels the collectors: only the
        // stop generation, checked under the lock, keeps the bring-up from recording its channel.
        val rig = Rig(this, radioLink = { fake -> ReadyDuringShutdownRadioLink(fake) })
        var failure: Throwable? = null
        launch { failure = runCatching { rig.ap.bringUp(CHANNEL) }.exceptionOrNull() }
        runCurrent()

        rig.ap.stop()

        assertTrue(rig.logs.has("onEvent(): radio ready"), "the bring-up was answered: ${rig.logs.text()}")
        val thrown = assertNotNull(failure)
        assertTrue(thrown is IllegalStateException, "$thrown")
        assertTrue("stopped during the bring-up" in thrown.message.orEmpty(), thrown.message)
        assertFailsWith<IllegalStateException>("nothing was recorded, so the radio is not up") { rig.ap.serve(rig.config) }
        assertTrue(rig.radio.sentOfType<RadioMessage.SetIdentity>().isEmpty())
        assertTrue(rig.radio.sentOfType<RadioMessage.StartBeacon>().isEmpty())
    }

    @Test
    fun bringUpBeforeStartStartingTwiceAndAnUnusableChannelAreRefused() = runTest {
        val rig = Rig(this, autoStart = false)

        assertFailsWith<IllegalStateException> { rig.ap.bringUp(CHANNEL) }
        rig.ap.start(backgroundScope)
        assertFailsWith<IllegalStateException> { rig.ap.start(backgroundScope) }
        assertFailsWith<IllegalArgumentException> { rig.ap.bringUp(3) }
        assertTrue(rig.radio.sentMessages.isEmpty())
    }

    // ---- Serving a network --------------------------------------------------------------------

    @Test
    fun serveSetsTheIdentityAndStartsTheBeaconThenUpdatesItOnTheNextServe() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.radio.clearSentMessages()

        rig.ap.serve(rig.config)
        assertEquals(
            listOf<RadioMessage.AppToRadiod>(
                RadioMessage.SetIdentity(BSSID.toByteArray()),
                RadioMessage.StartBeacon(100, ApStateMachine(rig.config, rig.primitives).beacon()),
            ),
            rig.radio.sentMessages,
        )
        assertTrue(rig.logs.has("serve(): beacon started -> ssid=${ApTestFixtures.SSID} bssid=$BSSID"), rig.logs.text())

        // Same BSSID, another network: only the beacon is replaced.
        rig.radio.clearSentMessages()
        val gameNetwork = ApConfig(bssid = BSSID, ssid = "mklhc_GAME", channel = CHANNEL, network = ApTestFixtures.network())
        rig.ap.serve(gameNetwork)
        assertEquals(
            listOf<RadioMessage.AppToRadiod>(RadioMessage.UpdateBeacon(ApStateMachine(gameNetwork, rig.primitives).beacon())),
            rig.radio.sentMessages,
        )

        // A new BSSID is handed to the hardware ACK engine before the beacon changes.
        rig.radio.clearSentMessages()
        val otherBssid = MacAddress.parse("02:11:22:33:44:66")
        rig.ap.serve(ApConfig(bssid = otherBssid, ssid = "mklhc_GAME", channel = CHANNEL, network = ApTestFixtures.network()))
        assertEquals(listOf("SetIdentity", "UpdateBeacon"), rig.radio.sentMessages.map { it::class.simpleName })
        assertContentEquals(otherBssid.toByteArray(), rig.radio.sentOfType<RadioMessage.SetIdentity>().single().mac)
    }

    @Test
    fun serveDropsTheAssociatedStationWhichMustJoinAgain() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        val received = rig.collectInbound()

        rig.ap.serve(rig.config)

        assertNull(rig.ap.associatedStation.value)
        assertFalse(rig.ap.isStationAssociated.value, "already false when serve() returns")
        assertTrue(rig.logs.has("serve(): network switched, station dropped -> sta=$STATION"), rig.logs.text())
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))
        assertTrue(received.isEmpty(), "the dropped station's data is no longer accepted")

        rig.associate()
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))
        assertEquals(1, received.size, "after joining again its data flows")
    }

    @Test
    fun serveRefusesAnotherChannelAndARadioThatIsNotUp() = runTest {
        val rig = Rig(this)

        assertFailsWith<IllegalStateException> { rig.ap.serve(rig.config) }
        rig.bringUp()
        assertFailsWith<IllegalArgumentException> { rig.ap.serve(ApTestFixtures.config(channel = 1)) }
        assertEquals(listOf("OpenDevice", "Init"), rig.radio.sentMessages.map { it::class.simpleName })
    }

    @Test
    fun aNewBringUpDropsTheNetworkAndTheNextServeStartsTheBeaconAgain() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        rig.radio.clearSentMessages()

        rig.bringUp()
        assertNull(rig.ap.associatedStation.value, "radiod restarted: the station is gone")
        assertFalse(rig.ap.isStationAssociated.value)
        rig.receive(rig.station.authRequest())
        assertTrue(rig.radio.sentOfType<RadioMessage.TxFrame>().isEmpty(), "nothing is answered until a network is served")
        rig.ap.serve(rig.config)

        assertEquals(
            listOf("OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            rig.radio.sentMessages.map { it::class.simpleName },
        )
    }

    // ---- Management frames --------------------------------------------------------------------

    @Test
    fun authenticationAndAssociationAreAnsweredAndPublishTheStation() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()

        rig.receive(rig.station.authRequest())
        rig.receive(rig.station.assocRequest(ApTestFixtures.SSID))

        assertEquals(STATION, rig.ap.associatedStation.value)
        assertTrue(rig.ap.isStationAssociated.value)
        val sent = rig.radio.sentOfType<RadioMessage.TxFrame>()
        assertEquals(2, sent.size)
        assertTrue(Ieee80211Frame.parse(sent[0].frame)!!.isAuth)
        assertTrue(Ieee80211Frame.parse(sent[1].frame)!!.isAssocResponse)
        assertTrue(sent.all { it.rate == "6M" && it.retryLimit == 7 && it.flags == 0 })
        assertTrue(rig.logs.has("auth -> sta=$STATION retry=0 rssiDbm=$RSSI_DBM"), rig.logs.text())
        assertTrue(rig.logs.has("assoc -> sta=$STATION retry=0 rssiDbm=$RSSI_DBM"), rig.logs.text())
        assertTrue(rig.logs.has("KartAssociated -> sta=$STATION aid=1"), rig.logs.text())
    }

    @Test
    fun theLogShowsTheRetryFlagOfAuthenticationAndAssociation() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()

        rig.receive(rig.station.authRequest(retry = true))
        rig.receive(rig.station.assocRequest(ApTestFixtures.SSID, retry = true))

        assertTrue(rig.logs.has("auth -> sta=$STATION retry=1"), rig.logs.text())
        assertTrue(rig.logs.has("assoc -> sta=$STATION retry=1"), rig.logs.text())
    }

    @Test
    fun aDeauthenticationClearsTheStation() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()

        rig.receive(rig.station.deauth())

        assertNull(rig.ap.associatedStation.value)
        assertFalse(rig.ap.isStationAssociated.value)
        assertTrue(rig.logs.has("deauth -> sta=$STATION reason=3"), rig.logs.text())
        assertTrue(rig.logs.has("station left -> sta=$STATION"), rig.logs.text())
    }

    @Test
    fun neighbouringNetworkTrafficIsNeitherAnsweredNorCaptured() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.startCapture()
        val otherBssid = MacAddress.parse("02:99:99:99:99:99")
        val otherBeacon = Ieee80211.beacon(otherBssid, 0L, 100, 0x0401, InformationElements.ssid("neighbour".encodeToByteArray()))
        val otherData = Ieee80211.fromDsData(STATION, otherBssid, otherBssid, ByteArray(8))

        rig.receive(otherBeacon)
        rig.receive(otherData)

        assertTrue(rig.radio.sentOfType<RadioMessage.TxFrame>().isEmpty())
        assertEquals(0, rig.capturedFrames().size)
    }

    @Test
    fun dataNotFromTheAssociatedStationIsNeitherDeliveredNorCaptured() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        rig.startCapture()
        val received = rig.collectInbound()
        val key = rig.config.network.ccmpKey
        val otherStation = FakeStation(key, BSSID, MacAddress.parse("02:aa:bb:cc:dd:02"), rig.primitives)
        val toAnotherBssid = FakeStation(key, MacAddress.parse("02:99:99:99:99:99"), STATION, rig.primitives)

        // Each frame is protected with the network's own key, so only its addressing can keep it out.
        rig.receive(otherStation.dhcpFrame(otherStation.discover(), pn = 1)) // another source address
        rig.receive(toAnotherBssid.dhcpFrame(toAnotherBssid.discover(), pn = 2)) // addressed to another BSSID
        rig.receive(notToDsData(key, LlcSnap.wrap(EtherType.ARP, ByteArray(ArpPacket.WIRE_SIZE)), pn = 3, rig.primitives))

        assertTrue(received.isEmpty(), "nothing reaches the network stack")
        assertEquals(0, rig.capturedFrames().size, "nothing is captured")
        assertEquals(STATION, rig.ap.associatedStation.value, "the association is untouched")

        // The associated station's own frame, under the same key, gets through: the frames above were kept out by
        // their addressing alone.
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 4))
        assertEquals(1, received.size)
        assertEquals(1, rig.capturedFrames().size)
    }

    // ---- Received data ------------------------------------------------------------------------

    @Test
    fun aDecryptedDhcpDiscoverIsDeliveredOnInboundAsIpv4() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        val received = rig.collectInbound()

        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))

        val payload = received.single()
        assertEquals(EtherType.IPV4, payload.etherType)
        val discover = assertNotNull(FakeStation.dhcpFromBody(LlcSnap.wrap(payload.etherType, payload.payload)))
        assertEquals(DhcpMessageType.DISCOVER, discover.messageType)
        assertEquals(FakeStation.DISCOVER_XID, discover.xid)
        assertContentEquals(STATION.toByteArray(), discover.clientMac)
        assertTrue(rig.logs.has("first data frame decrypted -> sta=$STATION"), rig.logs.text())
        assertFalse(rig.ccmpKeyHex in rig.logs.text().lowercase(), "the CCMP key must never be logged")
    }

    @Test
    fun aDataFrameBeforeAssociationIsIgnored() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.startCapture()
        val received = rig.collectInbound()

        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))

        assertTrue(received.isEmpty())
        assertTrue(rig.radio.sentOfType<RadioMessage.TxFrame>().isEmpty())
        assertEquals(0, rig.capturedFrames().size)
    }

    @Test
    fun aFrameWithABadMicIsRejectedAndTheNextFrameStillGetsThrough() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        val received = rig.collectInbound()
        val tampered = rig.station.dhcpFrame(rig.station.discover(), pn = 1)
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        rig.receive(tampered)
        assertTrue(received.isEmpty(), "a frame that fails CCMP is not delivered")
        assertTrue(rig.logs.has("CCMP rejected a frame -> sta=$STATION"), rig.logs.text())

        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 2))
        assertEquals(1, received.size, "the collector keeps going after a rejected frame")
    }

    @Test
    fun anUnprotectedDataFrameIsDroppedWithAWarning() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        val received = rig.collectInbound()
        val clear = Ieee80211Frame(
            frameControl = FrameControl.of(Ieee80211.TYPE_DATA, Ieee80211.SUBTYPE_DATA, toDs = true),
            addr1 = BSSID,
            addr2 = STATION,
            addr3 = MacAddress.BROADCAST,
            body = LlcSnap.wrap(EtherType.ARP, ByteArray(ArpPacket.WIRE_SIZE)),
        ).toBytes()

        rig.receive(clear)

        assertTrue(received.isEmpty())
        assertTrue(rig.logs.has("WARN: onDataFrame(): ignoring an unprotected data frame -> sta=$STATION"), rig.logs.text())
    }

    @Test
    fun aNullDataFrameIsIgnored() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        rig.startCapture()
        val received = rig.collectInbound()
        val nullData = Ieee80211Frame(
            frameControl = FrameControl.of(Ieee80211.TYPE_DATA, Ieee80211.SUBTYPE_NULL, toDs = true, powerManagement = true),
            addr1 = BSSID,
            addr2 = STATION,
            addr3 = BSSID,
        ).toBytes()

        rig.receive(nullData)

        assertTrue(received.isEmpty())
        assertEquals(0, rig.capturedFrames().size)
    }

    @Test
    fun aBodyWithoutLlcSnapIsCapturedButNotDelivered() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        rig.startCapture()
        val received = rig.collectInbound()

        rig.receive(rig.station.encryptedData(ByteArray(12) { it.toByte() }, pn = 1))

        assertTrue(received.isEmpty())
        assertEquals(1, rig.capturedFrames().size, "the decrypted frame is still captured")
        assertTrue(rig.logs.has("body is not LLC/SNAP, dropping it"), rig.logs.text())
    }

    @Test
    fun aFullInboundQueueDropsFramesInsteadOfHoldingUpTheRadio() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        val overflow = 3

        // Nobody collects inbound yet: the queue fills up and the extra frames are dropped.
        for (pn in 1L..(RadioAccessPoint.INBOUND_CAPACITY + overflow)) {
            rig.receive(rig.station.linkFrame(EtherType.ARP, byteArrayOf(pn.toByte()), pn))
        }
        // The collector is not stuck: it still answers the station's management frames.
        rig.receive(rig.station.deauth())
        assertNull(rig.ap.associatedStation.value)
        assertTrue(rig.logs.has("WARN: deliver(): the inbound queue is full"), rig.logs.text())

        val received = rig.collectInbound()
        assertEquals(RadioAccessPoint.INBOUND_CAPACITY, received.size)
        assertContentEquals(byteArrayOf(1), received.first().payload, "the oldest frames are kept, in order")
    }

    @Test
    fun payloadsStillQueuedWhenTheNetworkIsDroppedAreDiscarded() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()

        // A network switch.
        rig.associate()
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))
        rig.ap.serve(rig.config)
        assertTrue(rig.takeQueuedInbound().isEmpty(), "the old association's payloads are gone after a switch")
        assertTrue(rig.logs.has("serve(): discarded the payloads queued from the dropped network -> count=1"), rig.logs.text())

        // A new bring-up: radiod restarted.
        rig.associate()
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))
        rig.bringUp()
        assertTrue(rig.takeQueuedInbound().isEmpty(), "and after a new bring-up")
        assertTrue(rig.logs.has("bringUp(): discarded the payloads queued from the dropped network -> count=1"), rig.logs.text())

        // A stop and a start: a relaunched network stack does not read the previous run's payloads.
        rig.ap.serve(rig.config)
        rig.associate()
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))
        rig.ap.stop()
        rig.ap.start(backgroundScope)
        assertTrue(rig.takeQueuedInbound().isEmpty(), "and after a stop")
        assertTrue(rig.logs.has("stop(): discarded the payloads queued from the dropped network -> count=1"), rig.logs.text())

        // The payloads of the network still served are kept until the network stack reads them.
        rig.bringUpAndServe()
        rig.associate()
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))
        assertEquals(1, rig.takeQueuedInbound().size)
    }

    // ---- Transmitted data ---------------------------------------------------------------------

    @Test
    fun sendEncryptsThePayloadForTheAssociatedStation() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        rig.radio.clearSentMessages()
        val arp = ByteArray(ArpPacket.WIRE_SIZE) { it.toByte() }

        rig.ap.send(EtherType.ARP, arp)

        val sent = rig.radio.sentOfType<RadioMessage.TxFrame>().single()
        assertEquals("6M", sent.rate)
        assertEquals(7, sent.retryLimit)
        assertEquals(0, sent.flags)
        val frame = Ieee80211Frame.parse(sent.frame)!!
        assertTrue(frame.isDataFrame && frame.fromDs && frame.isProtected)
        assertEquals(STATION, frame.addr1)
        assertEquals(BSSID, frame.addr2)
        assertEquals(BSSID, frame.addr3)
        assertEquals(LinkPayload(EtherType.ARP, arp), rig.station.fromAp(sent.frame), "the station decrypts it")
    }

    @Test
    fun sendWithoutAnAssociatedStationDropsTheFrame() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()

        rig.ap.send(EtherType.IPV4, ByteArray(20))

        assertTrue(rig.radio.sentOfType<RadioMessage.TxFrame>().isEmpty())
        assertTrue(rig.logs.has("send(): no associated station, dropping the frame -> etherType=0x800"), rig.logs.text())
    }

    @Test
    fun concurrentSendsGoOnAirInPacketNumberOrder() = runTest {
        // Every TX_FRAME takes less time than the one before, so without the lock a later frame would overtake an
        // earlier one and reach the air with a higher packet number first.
        val rig = Rig(this, radioLink = { fake -> ShrinkingTxDelayRadioLink(fake) })
        rig.bringUpAndServe()
        rig.receive(rig.station.authRequest())
        rig.receive(rig.station.assocRequest(ApTestFixtures.SSID))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(STATION, rig.ap.associatedStation.value)
        rig.radio.clearSentMessages()

        val senders = (1..8).map { index -> launch { rig.ap.send(EtherType.IPV4, byteArrayOf(index.toByte())) } }
        advanceTimeBy(1.seconds)
        runCurrent()

        assertTrue(senders.all { it.isCompleted })
        val onAir = rig.radio.sentOfType<RadioMessage.TxFrame>().map { it.frame }
        assertEquals(8, onAir.size)
        val packetNumbers = onAir.map { packetNumberOf(it) }
        assertEquals(packetNumbers.sorted().distinct(), packetNumbers, "packet numbers must increase in TX order")
        // The kart's replay window (one CCMP receiver) accepts every frame in the order it went on air.
        val kartReceiver = Ccmp(rig.primitives)
        onAir.forEach { kartReceiver.decrypt(rig.config.network.ccmpKey, it) }
    }

    @Test
    fun aFailingTransmitIsALostFrameButCancellationPropagates() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()

        rig.radio.failure = IllegalStateException("radiod is not connected")
        rig.ap.send(EtherType.IPV4, ByteArray(20)) // must not throw
        assertTrue(rig.logs.has("WARN: transmit(): radiod did not take the frame, treating it as lost -> kind=data"), rig.logs.text())

        rig.radio.failure = CancellationException("the caller was cancelled")
        assertFailsWith<CancellationException> { rig.ap.send(EtherType.IPV4, ByteArray(20)) }
    }

    // ---- Capture ------------------------------------------------------------------------------

    @Test
    fun theCaptureHoldsManagementFramesAndClearDataFramesInBothDirections() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.startCapture()
        val arp = ByteArray(ArpPacket.WIRE_SIZE) { it.toByte() }

        rig.associate()
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))
        rig.ap.send(EtherType.ARP, arp)

        val frames = rig.capturedFrames().map { Ieee80211Frame.parse(it)!! }
        assertEquals(6, frames.size, "auth req/resp, assoc req/resp, DISCOVER, the sent ARP")
        assertTrue(frames[0].isAuth && frames[1].isAuth && frames[2].isAssocRequest && frames[3].isAssocResponse)
        assertEquals(STATION, frames[0].addr2, "the request is captured before its response")

        val discover = frames[4]
        assertTrue(discover.isDataFrame && discover.toDs)
        assertFalse(discover.isProtected, "the Protected bit is cleared: this frame is the decrypted view")
        assertEquals(DhcpMessageType.DISCOVER, FakeStation.dhcpFromBody(discover.body)?.messageType)

        val sentArp = frames[5]
        assertTrue(sentArp.isDataFrame && sentArp.fromDs)
        assertFalse(sentArp.isProtected)
        assertContentEquals(LlcSnap.wrap(EtherType.ARP, arp), sentArp.body)
    }

    @Test
    fun aFailingCaptureNeverStopsTheAuthenticationResponse() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.startCapture()
        rig.sink.failing = true

        rig.associate()

        assertEquals(2, rig.radio.sentOfType<RadioMessage.TxFrame>().size, "auth and assoc responses still went out")
        assertTrue(rig.logs.has("WARN: captureFrame(): capture append failed"), rig.logs.text())
    }

    @Test
    fun eachManagementExchangeIsCapturedOnlyAfterItsResponseWasTransmitted() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        val sink = TxCountingSink(rig.radio)
        rig.ap.capture = PcapWriter(sink, clock = { 0L })
        sink.txFramesAtEachWrite.clear() // the global header the writer wrote when it was created

        rig.associate()

        // Records: auth request, auth response, assoc request, assoc response. The station is waiting for each
        // response, so it goes on air first and the capture follows: the n-th response's TX_FRAME is already there.
        assertEquals(listOf(1, 1, 2, 2), sink.txFramesAtEachWrite, "TX_FRAMEs handed to radiod when each record was written")
    }

    // ---- Stop ---------------------------------------------------------------------------------

    @Test
    fun stopStopsTheBeaconShutsRadiodDownAndForgetsTheStation() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.associate()
        rig.radio.clearSentMessages()

        rig.ap.stop()
        runCurrent()

        assertEquals(listOf<RadioMessage.AppToRadiod>(RadioMessage.StopBeacon, RadioMessage.Shutdown), rig.radio.sentMessages)
        assertNull(rig.ap.associatedStation.value)
        assertFalse(rig.ap.isStationAssociated.value)
        rig.receive(rig.station.authRequest())
        rig.ap.send(EtherType.IPV4, ByteArray(20))
        assertTrue(rig.radio.sentOfType<RadioMessage.TxFrame>().isEmpty(), "nothing is answered or sent after stop")
        assertFailsWith<IllegalStateException> { rig.ap.serve(rig.config) }

        rig.radio.clearSentMessages()
        rig.ap.stop() // a second stop does nothing
        assertTrue(rig.radio.sentMessages.isEmpty())
    }

    @Test
    fun stopStillShutsRadiodDownWhenTheRadioFails() = runTest {
        val rig = Rig(this)
        rig.bringUpAndServe()
        rig.radio.failure = IllegalStateException("radiod is not connected")

        rig.ap.stop() // must not throw

        assertTrue(rig.logs.has("stopping the beacon failed"), rig.logs.text())
        assertTrue(rig.logs.has("asking radiod to shut down failed"), rig.logs.text())
    }

    @Test
    fun theAccessPointStartsAgainAfterStopAndInboundSurvives() = runTest {
        val rig = Rig(this)
        val received = rig.collectInbound()
        rig.bringUpAndServe()
        rig.ap.stop()

        rig.ap.start(backgroundScope)
        rig.bringUpAndServe()
        rig.associate()
        rig.receive(rig.station.dhcpFrame(rig.station.discover(), pn = 1))

        assertEquals(1, received.size, "the inbound flow collected before the stop still delivers")
    }

    // ---- End to end: the real network stack over the access point -----------------------------

    @Test
    fun theKartGetsALeaseResolvesTheAppAndOpensTheRcdConnection() = runTest {
        val rig = Rig(this)
        val node = NetstackNode(
            frameLink = rig.ap,
            scope = backgroundScope,
            config = NetstackConfig(ourIp = AddressPlan.AP, ourMac = BSSID.toByteArray()),
            tcpIssRandom = Random(TCP_ISS_SEED),
        )
        node.start()
        val listener = node.tcp.listen(RCD_PORT)
        rig.bringUpAndServe()
        rig.associate()

        // DHCP: DISCOVER -> OFFER and REQUEST -> ACK, both broadcast at the IP level (the kart has no address yet).
        val offerFrame = rig.exchange(rig.station.dhcpFrame(rig.station.discover(), pn = 1))
        val offer = assertNotNull(rig.station.dhcpFromAp(offerFrame))
        assertEquals(DhcpMessageType.OFFER, offer.messageType)
        assertEquals(AddressPlan.KART_LEASE, offer.yourIp)
        assertEquals(Ipv4Address.BROADCAST, rig.ipv4FromAp(offerFrame).destination)
        val ackFrame = rig.exchange(rig.station.dhcpFrame(rig.station.request(), pn = 2))
        assertEquals(DhcpMessageType.ACK, rig.station.dhcpFromAp(ackFrame)?.messageType)
        assertEquals(Ipv4Address.BROADCAST, rig.ipv4FromAp(ackFrame).destination)

        // ARP: who-has 169.254.98.65 is answered with the BSSID as the sender hardware address.
        val whoHas = ArpPacket(
            operation = Arp.OPERATION_REQUEST,
            senderMac = STATION.toByteArray(),
            senderIp = AddressPlan.KART_LEASE,
            targetMac = ByteArray(MacAddress.SIZE),
            targetIp = AddressPlan.AP,
        )
        val arpFrame = rig.exchange(rig.station.linkFrame(EtherType.ARP, whoHas.encode(), pn = 3))
        val arpPayload = assertNotNull(rig.station.fromAp(arpFrame))
        assertEquals(EtherType.ARP, arpPayload.etherType)
        val arpReply = assertNotNull(ArpPacket.decode(arpPayload.payload))
        assertEquals(Arp.OPERATION_REPLY, arpReply.operation)
        assertContentEquals(BSSID.toByteArray(), arpReply.senderMac)
        assertEquals(AddressPlan.AP, arpReply.senderIp)
        assertContentEquals(STATION.toByteArray(), arpReply.targetMac)
        assertEquals(AddressPlan.KART_LEASE, arpReply.targetIp)

        // TCP: SYN to 169.254.98.65:5201 -> SYN-ACK, then the kart's ACK completes the handshake.
        val syn = TcpSegment(KART_PORT, RCD_PORT, KART_ISS, 0, TcpSegment.SYN, TCP_WINDOW, maxSegmentSize = TCP_MSS)
        val synAckFrame = rig.exchange(rig.station.linkFrame(EtherType.IPV4, tcpPacket(syn), pn = 4, destination = BSSID))
        val synAckPacket = rig.ipv4FromAp(synAckFrame)
        assertEquals(AddressPlan.AP, synAckPacket.source)
        assertEquals(AddressPlan.KART_LEASE, synAckPacket.destination)
        val synAck = assertNotNull(TcpSegment.parse(synAckPacket.payload, AddressPlan.AP, AddressPlan.KART_LEASE))
        assertTrue(synAck.isSyn && synAck.isAck, "the RCD port answers a SYN with SYN+ACK")
        assertEquals(KART_ISS + 1, synAck.acknowledgmentNumber)
        assertEquals(RCD_PORT, synAck.sourcePort)
        assertEquals(KART_PORT, synAck.destinationPort)

        val ack = TcpSegment(KART_PORT, RCD_PORT, KART_ISS + 1, (synAck.sequenceNumber + 1) and MASK_32, TcpSegment.ACK, TCP_WINDOW)
        rig.receive(rig.station.linkFrame(EtherType.IPV4, tcpPacket(ack), pn = 5, destination = BSSID))
        val connection = withTimeout(1.seconds) { listener.accept() }
        assertEquals(TcpState.ESTABLISHED, connection.state.value)
        assertEquals(AddressPlan.KART_LEASE, connection.peer)
        assertEquals(KART_PORT, connection.peerPort)
    }

    // ---- Test rig ------------------------------------------------------------------------------

    /** An IPv4 packet from the kart's lease to the AP carrying [segment]. */
    private fun tcpPacket(segment: TcpSegment): ByteArray = Ipv4Packet(
        source = AddressPlan.KART_LEASE,
        destination = AddressPlan.AP,
        protocol = Ipv4Packet.PROTOCOL_TCP,
        payload = segment.encode(AddressPlan.KART_LEASE, AddressPlan.AP),
    ).encode()

    /** The 48-bit CCMP packet number of a protected 3-address MPDU: PN0, PN1 at octets 0-1, PN2..PN5 at 4-7. */
    private fun packetNumberOf(mpdu: ByteArray): Long {
        val ccmpHeader = mpdu.copyOfRange(MAC_HEADER_BYTES, MAC_HEADER_BYTES + CCMP_HEADER_BYTES)
        val bigEndian = byteArrayOf(ccmpHeader[7], ccmpHeader[6], ccmpHeader[5], ccmpHeader[4], ccmpHeader[1], ccmpHeader[0])
        return bigEndian.fold(0L) { value, octet -> (value shl 8) or (octet.toLong() and 0xFF) }
    }

    /**
     * A CCMP-protected data frame from [STATION] to the BSSID that is neither to-DS nor from-DS, encrypted with [key]
     * under the 48-bit packet number [pn]: valid in every way except that no station sends it to an access point.
     */
    private fun notToDsData(key: ByteArray, body: ByteArray, pn: Long, primitives: CryptoPrimitives): ByteArray {
        val header = Ieee80211Frame(
            frameControl = FrameControl.of(Ieee80211.TYPE_DATA, Ieee80211.SUBTYPE_DATA, protectedFrame = true),
            addr1 = BSSID,
            addr2 = STATION,
            addr3 = MacAddress.BROADCAST,
        ).toBytes()
        val packetNumber = ByteArray(PN_BYTES) { index -> (pn shr ((PN_BYTES - 1 - index) * 8)).toByte() }
        return Ccmp(primitives).encrypt(key, header, body, packetNumber)
    }

    /**
     * A radio whose SHUTDOWN crosses a READY still in flight: radiod answers the INIT just as the app shuts it down,
     * and the collectors, still running, take the READY before stop() cancels them.
     */
    private class ReadyDuringShutdownRadioLink(private val fake: FakeRadioLink) : RadioLink by fake {
        override suspend fun shutdown() {
            fake.emitEvent(READY)
            delay(1)
            fake.shutdown()
        }
    }

    /** Records, for every unit the pcap writer hands it, how many TX_FRAMEs [radio] had been given by then. */
    private class TxCountingSink(private val radio: FakeRadioLink) : PcapSink {
        val txFramesAtEachWrite = ArrayList<Int>()

        override fun write(bytes: ByteArray) {
            txFramesAtEachWrite += radio.sentOfType<RadioMessage.TxFrame>().size
        }
    }

    /** A radio whose TX_FRAME takes a little less virtual time on every call (20 ms, 19 ms, ... down to 1 ms). */
    private class ShrinkingTxDelayRadioLink(private val fake: FakeRadioLink) : RadioLink by fake {
        private var calls = 0

        override suspend fun txFrame(rate: String, retryLimit: Int, flags: Int, frame: ByteArray) {
            delay(maxOf(1, 20 - calls++).toLong())
            fake.txFrame(rate, retryLimit, flags, frame)
        }
    }

    /** Collects the pcap bytes in memory; [failing] makes every write throw, like a full disk. */
    private class MemorySink : PcapSink {
        private val chunks = ArrayList<ByteArray>()
        var failing = false

        fun bytes(): ByteArray = chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }

        override fun write(bytes: ByteArray) {
            if (failing) throw IllegalStateException("disk full")
            chunks += bytes
        }
    }

    /** Records every Kermit log line as `SEVERITY: message`. */
    private class LogCapture : LogWriter() {
        private val lines = mutableListOf<String>()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += "${severity.name.uppercase()}: $message"
        }

        fun has(fragment: String): Boolean = lines.any { fragment in it }

        fun text(): String = lines.joinToString("\n")
    }

    /**
     * Everything one test needs: a fixed network, the access point wired to a [FakeRadioLink] (optionally behind a
     * wrapper), an in-memory pcap with an injected clock, and a [FakeStation] that builds the frames the kart sends.
     */
    private class Rig(
        private val test: TestScope,
        options: RadioOptions = RadioOptions(),
        radioLink: (FakeRadioLink) -> RadioLink = { it },
        autoStart: Boolean = true,
    ) {
        val primitives = CryptoPrimitives()
        val radio = FakeRadioLink()
        val logs = LogCapture()
        val sink = MemorySink()
        val config: ApConfig = ApTestFixtures.config(channel = CHANNEL)
        val ap = RadioAccessPoint(
            radio = radioLink(radio),
            options = options,
            primitives = primitives,
            log = Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(logs)), tag = "RadioAccessPointTest"),
        )
        val station = FakeStation(config.network.ccmpKey, BSSID, STATION, primitives)
        private var clockMicros = 1_700_000_000_000_000L

        /** The network's CCMP key as hex, so a test can assert it never reaches a log. */
        val ccmpKeyHex: String = config.network.ccmpKey.toHexString()

        init {
            if (autoStart) ap.start(test.backgroundScope)
        }

        /** Runs [RadioAccessPoint.bringUp] to completion with radiod answering READY. */
        fun bringUp() {
            val job = test.backgroundScope.launch { ap.bringUp(CHANNEL) }
            test.runCurrent()
            radio.emitEvent(READY)
            test.runCurrent()
            check(job.isCompleted) { "bringUp did not complete" }
        }

        suspend fun bringUpAndServe() {
            bringUp()
            ap.serve(config)
        }

        /** Captures to the in-memory sink from now on. */
        fun startCapture() {
            ap.capture = PcapWriter(sink, clock = { clockMicros.also { clockMicros += 1_000L } })
        }

        /** The 802.11 frames captured so far (record headers and the radiotap prefix stripped). */
        fun capturedFrames(): List<ByteArray> {
            val bytes = sink.bytes()
            fun u32(offset: Int): Int = (0 until 4).sumOf { (bytes[offset + it].toInt() and 0xFF) shl (it * 8) }
            val frames = ArrayList<ByteArray>()
            var offset = PcapWriter.GLOBAL_HEADER_BYTES
            while (offset < bytes.size) {
                val includedLength = u32(offset + 8)
                val packetStart = offset + PcapWriter.RECORD_HEADER_BYTES
                frames += bytes.copyOfRange(packetStart + PcapWriter.RADIOTAP_HEADER_BYTES, packetStart + includedLength)
                offset = packetStart + includedLength
            }
            return frames
        }

        /** Collects [RadioAccessPoint.inbound] into the returned list for the rest of the test. */
        fun collectInbound(): List<LinkPayload> {
            val received = ArrayList<LinkPayload>()
            test.backgroundScope.launch { ap.inbound.collect { received += it } }
            test.runCurrent()
            return received
        }

        /** Reads what is queued on [RadioAccessPoint.inbound] right now, without staying subscribed. */
        fun takeQueuedInbound(): List<LinkPayload> {
            val taken = ArrayList<LinkPayload>()
            val reader = test.backgroundScope.launch { ap.inbound.collect { taken += it } }
            test.runCurrent()
            reader.cancel()
            test.runCurrent()
            return taken
        }

        /** Plays radiod delivering [frame] and lets the access point (and anything on the test scheduler) react. */
        fun receive(frame: ByteArray) {
            radio.emitRxFrame(RadioFrame(rssiDbm = RSSI_DBM, rate = 0, tsf = 0uL, frame = frame))
            test.runCurrent()
        }

        /** Delivers [frame] and returns the last frame the access point transmitted in reaction. */
        fun exchange(frame: ByteArray): ByteArray {
            val before = radio.sentOfType<RadioMessage.TxFrame>().size
            receive(frame)
            val sent = radio.sentOfType<RadioMessage.TxFrame>()
            check(sent.size > before) { "the access point transmitted nothing in reaction" }
            return sent.last().frame
        }

        /** Decrypts a frame the access point sent to the station and parses the IPv4 packet inside it. */
        fun ipv4FromAp(mpdu: ByteArray): Ipv4Packet {
            val payload = checkNotNull(station.fromAp(mpdu)) { "the station cannot decrypt the frame" }
            check(payload.etherType == EtherType.IPV4) { "not an IPv4 frame: $payload" }
            return checkNotNull(Ipv4Packet.parse(payload.payload)) { "not a valid IPv4 packet" }
        }

        fun associate() {
            receive(station.authRequest())
            receive(station.assocRequest(ApTestFixtures.SSID))
            check(ap.associatedStation.value == STATION) { "the station did not associate" }
        }
    }

    private companion object {
        val BSSID: MacAddress = ApTestFixtures.BSSID
        val STATION: MacAddress = FakeStation.DEFAULT_MAC
        val READY = RadioMessage.Ready("rtl8812cu", "fw-1", "devourer-test")
        const val CHANNEL = 6
        const val RSSI_DBM = -42
        const val RCD_PORT = 5201
        const val KART_PORT = 40000
        const val KART_ISS = 1000L
        const val TCP_WINDOW = 8192
        const val TCP_MSS = 1460
        const val TCP_ISS_SEED = 100
        const val MASK_32 = 0xFFFFFFFFL
        const val MAC_HEADER_BYTES = 24
        const val CCMP_HEADER_BYTES = 8
        const val PN_BYTES = 6
    }
}
