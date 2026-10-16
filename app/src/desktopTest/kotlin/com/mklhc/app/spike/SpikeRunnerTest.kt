package com.mklhc.app.spike

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.DhcpMessage
import com.mklhc.core.netstack.DhcpMessageType
import com.mklhc.core.netstack.DhcpServer
import com.mklhc.core.radio.FakeRadioLink
import com.mklhc.core.radio.RadioFrame
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.wifi_ap.ap.ApConfig
import com.mklhc.core.wifi_ap.ap.ApStateMachine
import com.mklhc.core.wifi_ap.lp2p.Lp2pBeacon
import com.mklhc.core.wifi_ap.mac.FrameControl
import com.mklhc.core.wifi_ap.mac.Ieee80211
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.InformationElements
import com.mklhc.core.wifi_ap.mac.LlcSnap
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.core.wifi_ap.pcap.PcapSink
import com.mklhc.core.wifi_ap.pcap.PcapWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SpikeRunner] against [FakeRadioLink]: a fake station walks through authentication, association and a CCMP-encrypted
 * DHCP exchange, and the tests pin what the spike must do with it: bring the radio up in order, answer the management
 * frames, decrypt the station's data frames into the DHCP server, send the reply back encrypted, and write every
 * decrypted frame to a valid pcap. Everything is deterministic: a fixed network, an injected pcap clock and the
 * virtual time of `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpikeRunnerTest {

    // ---- Bring-up -----------------------------------------------------------------------------

    @Test
    fun bringUpOpensTheAdapterInitialisesWaitsForReadyThenBeacons() = runTest {
        val rig = Rig(this)

        val bringUp = launch { rig.runner.bringUp() }
        runCurrent()

        // Until radiod says READY, only the device open and INIT have been sent: no identity, no beacon.
        assertEquals(
            listOf<RadioMessage.AppToRadiod>(RadioMessage.OpenDevice(0x0BDA, 0xC812), RadioMessage.Init(6, 20, 0)),
            rig.radio.sentMessages,
        )
        assertTrue(bringUp.isActive)

        rig.radio.emitEvent(RadioMessage.Ready("rtl8812cu", "fw-1", "devourer-test"))
        runCurrent()

        assertFalse(bringUp.isActive)
        assertEquals(
            listOf<RadioMessage.AppToRadiod>(
                RadioMessage.OpenDevice(0x0BDA, 0xC812),
                RadioMessage.Init(6, 20, 0),
                RadioMessage.SetIdentity(Rig.BSSID.toByteArray()),
                RadioMessage.StartBeacon(100, ApStateMachine(rig.config, rig.primitives).beacon()),
            ),
            rig.radio.sentMessages,
        )
        assertTrue(rig.logs.has("bringUp(): pairing beacon started"))
    }

    @Test
    fun bringUpFailsWhenRadiodReportsAnError() = runTest {
        val rig = Rig(this)
        var failure: Throwable? = null

        launch { failure = runCatching { rig.runner.bringUp() }.exceptionOrNull() }
        runCurrent()
        rig.radio.emitEvent(RadioMessage.Error(code = 7u, text = "cannot open the adapter"))
        runCurrent()

        val thrown = assertNotNull(failure)
        assertTrue(thrown is IllegalStateException)
        assertTrue("cannot open the adapter" in thrown.message.orEmpty())
        assertTrue(rig.radio.sentOfType<RadioMessage.StartBeacon>().isEmpty(), "no beacon after a failed bring-up")
    }

    @Test
    fun bringUpFailsWhenReadyNeverArrives() = runTest {
        val rig = Rig(this, options = SpikeOptions(readyTimeoutMs = 5_000L))
        var failure: Throwable? = null

        launch { failure = runCatching { rig.runner.bringUp() }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(5_001L)
        runCurrent()

        val thrown = assertNotNull(failure)
        assertTrue(thrown is IllegalStateException)
        assertTrue("did not report READY" in thrown.message.orEmpty())
        assertTrue(rig.radio.sentOfType<RadioMessage.StartBeacon>().isEmpty())
    }

    @Test
    fun bringUpCanRunAgainAfterARadiodRestart() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.radio.clearSentMessages()

        rig.bringUp()

        assertEquals(
            listOf("OpenDevice", "Init", "SetIdentity", "StartBeacon"),
            rig.radio.sentMessages.map { it::class.simpleName },
        )
    }

    @Test
    fun bringUpBeforeStartAndStartingTwiceAreRefused() = runTest {
        val rig = Rig(this, autoStart = false)

        assertFailsWith<IllegalStateException> { rig.runner.bringUp() }
        rig.runner.start(backgroundScope)
        assertFailsWith<IllegalStateException> { rig.runner.start(backgroundScope) }
    }

    // ---- The GO scenario: association, decrypted DHCP DISCOVER, pcap --------------------------

    @Test
    fun anAssociatedStationsDecryptedDhcpDiscoverFeedsTheServerAndLandsInThePcap() = runTest {
        val rig = Rig(this)
        rig.bringUp()

        rig.receive(rig.authRequest())
        rig.receive(rig.assocRequest())
        rig.receive(rig.dhcpFrame(rig.discover(), pn = 1))

        // (1) Association: the AP answered both management frames and the runner reports the milestone.
        assertEquals(Rig.STATION, rig.runner.associatedStation)
        val sent = rig.radio.sentOfType<RadioMessage.TxFrame>()
        assertEquals(3, sent.size, "auth response, assoc response, DHCP OFFER")
        assertTrue(Ieee80211Frame.parse(sent[0].frame)!!.isAuth)
        assertTrue(Ieee80211Frame.parse(sent[1].frame)!!.isAssocResponse)
        assertEquals(listOf("6M", "6M", "6M"), sent.map { it.rate })

        // (2) The decrypted DISCOVER reached the DHCP server, which offered the one lease, encrypted, to the station.
        val offerFrame = Ieee80211Frame.parse(sent[2].frame)!!
        assertTrue(offerFrame.isDataFrame && offerFrame.fromDs && offerFrame.isProtected)
        assertEquals(Rig.STATION, offerFrame.addr1)
        assertEquals(Rig.BSSID, offerFrame.addr2)
        val offer = rig.dhcpFromSent(sent[2].frame)
        assertEquals(DhcpMessageType.OFFER, offer.messageType)
        assertEquals(AddressPlan.KART_LEASE, offer.yourIp)
        assertEquals(DISCOVER_XID, offer.xid)
        assertContentEquals(Rig.STATION.toByteArray(), offer.clientMac)

        // (3) The capture is a valid radiotap pcap holding the exchange, with the DISCOVER in plaintext.
        val capture = rig.pcap()
        assertEquals(PcapWriter.LINKTYPE_IEEE802_11_RADIOTAP, capture.linkType)
        assertEquals(6, capture.frames.size, "auth req/resp, assoc req/resp, DISCOVER, OFFER")
        val parsed = capture.frames.map { Ieee80211Frame.parse(it.frame)!! }
        assertTrue(parsed[0].isAuth && parsed[1].isAuth && parsed[2].isAssocRequest && parsed[3].isAssocResponse)
        val discoverFrame = parsed[4]
        assertTrue(discoverFrame.isDataFrame && discoverFrame.toDs)
        assertFalse(discoverFrame.isProtected, "the Protected bit is cleared: this frame is the decrypted view")
        assertEquals(Rig.STATION, discoverFrame.addr2)
        val discover = dhcpFromBody(discoverFrame.body)
        assertEquals(DhcpMessageType.DISCOVER, discover.messageType)
        assertEquals(DISCOVER_XID, discover.xid)
        assertContentEquals(Rig.STATION.toByteArray(), discover.clientMac)
        // The OFFER is captured in its plaintext form too.
        val offerCaptured = parsed[5]
        assertFalse(offerCaptured.isProtected)
        assertEquals(DhcpMessageType.OFFER, dhcpFromBody(offerCaptured.body).messageType)
        assertEquals(1, rig.runner.decryptedFrameCount)

        // The injected clock stamps records in order (the header takes no timestamp).
        assertEquals(listOf(1_700_000_000L to 0L, 1_700_000_000L to 1_000L), capture.frames.take(2).map { it.tsSec to it.tsUsec })

        // (4) The milestone lines of the GO criteria are logged, and no key material is.
        assertTrue(rig.logs.has("auth -> sta=${Rig.STATION} retry=0"), rig.logs.text())
        assertTrue(rig.logs.has("assoc -> sta=${Rig.STATION} retry=0"), rig.logs.text())
        assertTrue(rig.logs.has("KartAssociated -> sta=${Rig.STATION}"), rig.logs.text())
        assertTrue(rig.logs.has("DHCP DISCOVER from ${Rig.STATION}"), rig.logs.text())
        assertTrue(rig.logs.has("pcap written: memory.pcap"), rig.logs.text())
        assertFalse(rig.ccmpKeyHex in rig.logs.text().lowercase(), "the CCMP key must never be logged")
    }

    @Test
    fun theLogShowsTheRetryFlagOfAuthenticationAndAssociation() = runTest {
        val rig = Rig(this)
        rig.bringUp()

        rig.receive(rig.authRequest(retry = true))
        rig.receive(rig.assocRequest(retry = true))

        assertTrue(rig.logs.has("auth -> sta=${Rig.STATION} retry=1"), rig.logs.text())
        assertTrue(rig.logs.has("assoc -> sta=${Rig.STATION} retry=1"), rig.logs.text())
    }

    @Test
    fun aDhcpRequestAfterTheOfferIsAcknowledgedAndBindsTheLease() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.associate()

        rig.receive(rig.dhcpFrame(rig.discover(), pn = 1))
        rig.receive(rig.dhcpFrame(rig.request(), pn = 2))

        val sent = rig.radio.sentOfType<RadioMessage.TxFrame>()
        assertEquals(DhcpMessageType.ACK, rig.dhcpFromSent(sent.last().frame).messageType)
        assertContentEquals(Rig.STATION.toByteArray(), rig.dhcp.boundClientMac)
        assertEquals(2, rig.runner.decryptedFrameCount)
    }

    @Test
    fun theStationLeavingFreesTheDhcpLease() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.associate()
        rig.receive(rig.dhcpFrame(rig.discover(), pn = 1))
        rig.receive(rig.dhcpFrame(rig.request(), pn = 2))
        assertNotNull(rig.dhcp.boundClientMac)

        rig.receive(rig.deauth())

        assertNull(rig.dhcp.boundClientMac)
        assertNull(rig.runner.associatedStation)
        assertTrue(rig.logs.has("station left, DHCP lease freed"), rig.logs.text())
    }

    // ---- What the spike ignores or refuses -----------------------------------------------------

    @Test
    fun neighbouringNetworkTrafficIsNeitherAnsweredNorCaptured() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        val otherBssid = MacAddress.parse("02:99:99:99:99:99")
        val otherBeacon = Ieee80211.beacon(otherBssid, 0L, 100, 0x0401, InformationElements.ssid("neighbour".encodeToByteArray()))
        val otherData = Ieee80211.fromDsData(Rig.STATION, otherBssid, otherBssid, ByteArray(8))

        rig.receive(otherBeacon)
        rig.receive(otherData)

        assertTrue(rig.radio.sentOfType<RadioMessage.TxFrame>().isEmpty())
        assertEquals(0, rig.pcap().frames.size)
    }

    @Test
    fun aDataFrameBeforeAssociationIsIgnored() = runTest {
        val rig = Rig(this)
        rig.bringUp()

        rig.receive(rig.dhcpFrame(rig.discover(), pn = 1))

        assertTrue(rig.radio.sentOfType<RadioMessage.TxFrame>().isEmpty())
        assertEquals(0, rig.pcap().frames.size)
        assertEquals(0, rig.runner.decryptedFrameCount)
    }

    @Test
    fun aFrameWithABadMicIsRejectedAndNeitherCapturedNorFedToDhcp() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.associate()
        val tamperedFrame = rig.dhcpFrame(rig.discover(), pn = 1)
        tamperedFrame[tamperedFrame.size - 1] = (tamperedFrame[tamperedFrame.size - 1].toInt() xor 0x01).toByte()
        val framesBefore = rig.pcap().frames.size
        val sentBefore = rig.radio.sentOfType<RadioMessage.TxFrame>().size

        rig.receive(tamperedFrame)

        assertEquals(framesBefore, rig.pcap().frames.size, "a frame that fails CCMP is not captured")
        assertEquals(sentBefore, rig.radio.sentOfType<RadioMessage.TxFrame>().size, "and gets no DHCP reply")
        assertEquals(0, rig.runner.decryptedFrameCount)
        assertTrue(rig.logs.has("CCMP rejected a frame"), rig.logs.text())
    }

    @Test
    fun aNonDhcpPayloadIsCapturedDecryptedButNotAnswered() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.associate()
        val framesBefore = rig.pcap().frames.size
        val sentBefore = rig.radio.sentOfType<RadioMessage.TxFrame>().size
        val arpBody = LlcSnap.wrap(0x0806, ByteArray(28) { it.toByte() })

        rig.receive(rig.encryptedData(arpBody, pn = 1))

        val frames = rig.pcap().frames
        assertEquals(framesBefore + 1, frames.size)
        val captured = Ieee80211Frame.parse(frames.last().frame)!!
        assertFalse(captured.isProtected)
        assertContentEquals(arpBody, captured.body)
        assertEquals(sentBefore, rig.radio.sentOfType<RadioMessage.TxFrame>().size)
    }

    @Test
    fun aNullDataFrameIsIgnored() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.associate()
        val framesBefore = rig.pcap().frames.size
        val nullData = Ieee80211Frame(
            frameControl = FrameControl.of(Ieee80211.TYPE_DATA, Ieee80211.SUBTYPE_NULL, toDs = true, powerManagement = true),
            addr1 = Rig.BSSID,
            addr2 = Rig.STATION,
            addr3 = Rig.BSSID,
        ).toBytes()

        rig.receive(nullData)

        assertEquals(framesBefore, rig.pcap().frames.size)
        assertEquals(0, rig.runner.decryptedFrameCount)
    }

    // ---- Stop ---------------------------------------------------------------------------------

    @Test
    fun stopStopsTheBeaconShutsRadiodDownAndStopsListening() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.radio.clearSentMessages()

        rig.runner.stop()
        runCurrent()
        rig.receive(rig.authRequest())

        assertEquals(listOf<RadioMessage.AppToRadiod>(RadioMessage.StopBeacon, RadioMessage.Shutdown), rig.radio.sentMessages)
        assertTrue(rig.logs.has("pcap written: memory.pcap -> frames=0"), rig.logs.text())
    }

    @Test
    fun stopStillShutsRadiodDownWhenTheBeaconCommandFails() = runTest {
        val rig = Rig(this)
        rig.bringUp()
        rig.radio.clearSentMessages()
        rig.radio.failure = IllegalStateException("radiod is not connected")

        rig.runner.stop() // must not throw

        assertTrue(rig.logs.has("stopping the beacon failed"), rig.logs.text())
        assertTrue(rig.logs.has("asking radiod to shut down failed"), rig.logs.text())
    }

    // ---- Test rig ------------------------------------------------------------------------------

    /** Collects the pcap bytes in memory. */
    private class MemorySink : PcapSink {
        private val buffer = ByteArrayOutputStream()

        fun bytes(): ByteArray = buffer.toByteArray()

        override fun write(bytes: ByteArray) = buffer.write(bytes)
    }

    /** Records every Kermit log line as `SEVERITY: message`. */
    private class LogCapture : LogWriter() {
        private val lines = mutableListOf<String>()

        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += "$severity: $message" + (throwable?.let { " | ${it::class.simpleName}: ${it.message}" } ?: "")
        }

        fun has(fragment: String): Boolean = lines.any { fragment in it }

        fun text(): String = lines.joinToString("\n")
    }

    /**
     * Everything one test needs: a fixed pairing network, the runner wired to a [FakeRadioLink] and an in-memory pcap
     * with an injected clock, plus a [FakeStation] that builds the frames the kart would send.
     */
    private class Rig(
        private val test: TestScope,
        options: SpikeOptions = SpikeOptions(),
        autoStart: Boolean = true,
    ) {
        val primitives = CryptoPrimitives()
        val radio = FakeRadioLink()
        val logs = LogCapture()
        val dhcp = DhcpServer()
        private val sink = MemorySink()
        private var clockMicros = 1_700_000_000_000_000L

        // A real LP2P network from fixed inputs (deterministic), the same recipe the wifi-ap tests use.
        private val network = Lp2pBeacon(primitives).encryptNetwork(
            psk = ByteArray(32) { 0x11 },
            plainNonce = ByteArray(16) { 0x22 },
            encryptedNonce = ByteArray(16) { 0x33 },
            randomness = { count -> ByteArray(count) { 0x44 } },
        )
        val config = ApConfig(bssid = BSSID, ssid = SSID, channel = 6, beaconIntervalTu = 100, network = network)
        val runner = SpikeRunner(
            radio = radio,
            apConfig = config,
            capture = PcapWriter(sink, clock = { clockMicros.also { clockMicros += 1_000L } }),
            captureLabel = "memory.pcap",
            options = options,
            primitives = primitives,
            dhcpServer = dhcp,
            log = Logger(StaticConfig(minSeverity = Severity.Verbose, logWriterList = listOf(logs)), tag = "SpikeRunnerTest"),
        )
        private val station = FakeStation(network.ccmpKey, BSSID, STATION, primitives)

        /** The network's CCMP key as hex, so a test can assert it never reaches a log. */
        val ccmpKeyHex: String = network.ccmpKey.toHexString()

        init {
            if (autoStart) runner.start(test.backgroundScope)
        }

        /** Runs [SpikeRunner.bringUp] to completion with radiod answering READY. */
        fun bringUp() {
            val job = test.backgroundScope.launch { runner.bringUp() }
            test.runCurrent()
            radio.emitEvent(RadioMessage.Ready("rtl8812cu", "fw-1", "devourer-test"))
            test.runCurrent()
            check(job.isCompleted) { "bringUp did not complete" }
        }

        /** Plays radiod delivering [frame] and lets the runner process it. */
        fun receive(frame: ByteArray) {
            radio.emitRxFrame(RadioFrame(rssiDbm = -42, rate = 0, tsf = 0uL, frame = frame))
            test.runCurrent()
        }

        fun associate() {
            receive(authRequest())
            receive(assocRequest())
            check(runner.associatedStation == STATION) { "the station did not associate" }
        }

        // ---- station-side frames ----

        fun authRequest(retry: Boolean = false): ByteArray = station.authRequest(retry)

        fun assocRequest(retry: Boolean = false): ByteArray = station.assocRequest(SSID, retry)

        fun deauth(): ByteArray = station.deauth()

        fun encryptedData(body: ByteArray, pn: Long): ByteArray = station.encryptedData(body, pn)

        fun dhcpFrame(dhcpPayload: ByteArray, pn: Long): ByteArray = station.dhcpFrame(dhcpPayload, pn)

        fun discover(): ByteArray = station.discover()

        fun request(): ByteArray = station.request()

        /** Decrypts a frame the runner transmitted (as the kart would) and parses the DHCP message inside it. */
        fun dhcpFromSent(mpdu: ByteArray): DhcpMessage = station.dhcpFromAp(mpdu)

        /** The pcap written so far, decoded (and its framing checked). */
        fun pcap(): PcapCapture = parsePcap(sink.bytes())

        companion object {
            val BSSID: MacAddress = MacAddress.parse("02:11:22:33:44:55")
            val STATION: MacAddress = FakeStation.DEFAULT_MAC
            const val SSID = "mklhc-pair_test"
        }
    }
}
