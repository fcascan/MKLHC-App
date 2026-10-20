package com.mklhc.app.spike

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.netstack.AddressPlan
import com.mklhc.core.netstack.DhcpMessage
import com.mklhc.core.netstack.DhcpMessageType
import com.mklhc.core.netstack.DhcpServer
import com.mklhc.core.netstack.EtherType
import com.mklhc.core.netstack.Ipv4Address
import com.mklhc.core.netstack.Ipv4Packet
import com.mklhc.core.netstack.Ipv4Receiver
import com.mklhc.core.netstack.UdpDatagram
import com.mklhc.core.radio.RadioFrame
import com.mklhc.core.radio.RadioLink
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.wifi_ap.ap.ApConfig
import com.mklhc.core.wifi_ap.ap.ApState
import com.mklhc.core.wifi_ap.ap.ApStateMachine
import com.mklhc.core.wifi_ap.crypto.CcmpException
import com.mklhc.core.wifi_ap.mac.FrameControl
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.LlcSnap
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.core.wifi_ap.pcap.PcapWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Glue for the radio hardware spike (Task 1.8).
 *
 * Original code. It only wires modules that already exist (RadioLink, ApStateMachine, the netstack's IPv4 / UDP /
 * DHCP codecs and PcapWriter); the 802.11 and DHCP behaviour itself lives in those modules. The AP design reference
 * (devourer ap_responder.cpp, no code copied) is cited in ApStateMachine.
 */

/**
 * The spike's tunables that are not part of the access point description ([ApConfig]).
 *
 * @property vid the adapter's USB vendor id; the default is the reference RTL8812CU (0bda:c812).
 * @property pid the adapter's USB product id.
 * @property bandwidthMhz the channel width handed to radiod's INIT (20 MHz: the kart network is 802.11g).
 * @property txPowerIndex the TX power index for INIT; 0 keeps the chip default.
 * @property txRate the rate string for every frame the spike transmits; a hardware-spike tunable.
 * @property txRetryLimit the per-frame retry limit sent with TX_FRAME (radiod currently applies one device-wide limit).
 * @property readyTimeoutMs how long [SpikeRunner.bringUp] waits for radiod's READY after INIT.
 */
data class SpikeOptions(
    val vid: Int = DEFAULT_VID,
    val pid: Int = DEFAULT_PID,
    val bandwidthMhz: Int = DEFAULT_BANDWIDTH_MHZ,
    val txPowerIndex: Int = 0,
    val txRate: String = DEFAULT_TX_RATE,
    val txRetryLimit: Int = DEFAULT_TX_RETRY_LIMIT,
    val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
) {
    companion object {
        /** USB vendor id of the reference adapter (RTL8812CU). */
        const val DEFAULT_VID = 0x0BDA

        /** USB product id of the reference adapter (RTL8812CU). */
        const val DEFAULT_PID = 0xC812

        /**
         * How long to wait for READY by default. INIT loads the firmware and the PHY tables; on Windows the 8822B
         * (TP-Link Archer T3U) needed about 46 s, because each of its ~44,000 BB/AGC/RF table writes is a USB control
         * transfer (~1.3 ms over WinUSB). Measured on 2026-10-20.
         */
        const val DEFAULT_READY_TIMEOUT_MS = 120_000L

        private const val DEFAULT_BANDWIDTH_MHZ = 20
        private const val DEFAULT_TX_RATE = "6M"
        private const val DEFAULT_TX_RETRY_LIMIT = 7
    }
}

/**
 * Wires a [RadioLink] to the software access point, the DHCP server and a pcap capture: the whole radio spike
 * except process start-up, which `SpikeMain` does.
 *
 * Business logic: the Task 1.8 gate asks the app to host the pairing network, let the kart associate, decrypt its
 * DHCP DISCOVER and export a decrypted capture. The runner does exactly that, frame by frame:
 *
 * - [start] subscribes to the radio's two hot flows (received frames and radiod notifications) before anything is
 *   commanded, because they have no replay and an answer arriving before the subscription would be lost.
 * - [bringUp] opens the adapter, brings the radio up, waits for radiod's READY, sets the BSSID and starts the
 *   hardware pairing beacon. It can be called again after radiod restarts.
 * - Every received frame goes through one function, in arrival order, on the collecting coroutine. Management frames
 *   (probe, authentication, association, deauthentication) are fed to [ApStateMachine], its responses are
 *   transmitted, and the exchange is captured. Data frames from the associated station are CCMP-decrypted by the state
 *   machine, appended to the capture as plaintext 802.11 frames (Protected bit cleared, CCMP header and MIC gone, so
 *   Wireshark shows the LLC/SNAP payload), and the LLC/SNAP + IPv4 + UDP layers are peeled off with the netstack's own
 *   parsers; a UDP datagram for port 67 is a DHCP message, which feeds [DhcpServer], and its reply is wrapped back up
 *   (UDP, IPv4, LLC/SNAP), encrypted by the state machine, transmitted and captured too.
 * - Frames of neighbouring networks are ignored and never captured: only management frames that address this BSSID
 *   (or that the AP answered) and the associated station's data frames are kept.
 *
 * Milestone log lines (the GO criteria): `auth` and `assoc` with `retry=0|1`, `KartAssociated`,
 * `DHCP DISCOVER from <mac>` and `pcap written: <path>`. Neither the pairing seed nor any key is ever logged.
 *
 * Not covered by the spike (the full link layer is a separate task): ARP, TCP and every non-DHCP payload are
 * captured but not answered; there is no power-save buffering and no per-TID replay window.
 *
 * Threading: [ApStateMachine], [DhcpServer] and [PcapWriter] are not thread-safe. All frame handling runs on the single
 * coroutine [start] launches, so give [start] a scope on a single-threaded dispatcher (production uses
 * `limitedParallelism(1)`); call [bringUp] and [stop] from that same dispatcher.
 *
 * @param radio the link to radiod (a [FakeRadioLink][com.mklhc.core.radio.FakeRadioLink] in tests).
 * @param apConfig the BSS to serve (the pairing network).
 * @param capture where the decrypted frames go.
 * @param captureLabel what to call the capture in log lines, normally its file path.
 * @param options the spike tunables.
 * @param primitives the crypto provider for CCMP.
 * @param dhcpServer the single-lease DHCP server fed with the station's DHCP messages.
 * @param log the logger; injectable so tests can assert the milestones.
 */
class SpikeRunner(
    private val radio: RadioLink,
    private val apConfig: ApConfig,
    private val capture: PcapWriter,
    private val captureLabel: String,
    private val options: SpikeOptions = SpikeOptions(),
    primitives: CryptoPrimitives = CryptoPrimitives(),
    private val dhcpServer: DhcpServer = DhcpServer(),
    private val log: Logger = Logger.withTag(TAG),
) {
    private val ap = ApStateMachine(apConfig, primitives)
    private val bssid: MacAddress = apConfig.bssid
    private val ipv4Receiver = Ipv4Receiver()

    /** What radiod last said about the bring-up; [RadioOutcome.Pending] from the moment [bringUp] starts. */
    private val outcome = MutableStateFlow<RadioOutcome>(RadioOutcome.Pending)
    private val collectors = ArrayList<Job>()
    private var nextIpIdentification = 0
    private var decryptedFrames = 0

    /** How many data frames from the station have been decrypted and captured so far. */
    val decryptedFrameCount: Int get() = decryptedFrames

    /** The associated station, or null while only beaconing. Read it from the runner's dispatcher. */
    val associatedStation: MacAddress? get() = ap.station

    /**
     * Subscribes to the radio's received frames and notifications, launching the collectors in [scope]. The
     * subscriptions are in place when this returns, so a following [bringUp] cannot miss radiod's answer.
     *
     * @throws IllegalStateException if already started.
     */
    fun start(scope: CoroutineScope) {
        check(collectors.isEmpty()) { "start(): already started" }
        log.i { "start(): listening to the radio -> channel=${apConfig.channel} bssid=$bssid" }
        // UNDISPATCHED runs each launch up to the collector's first suspension, which is the moment it subscribes.
        collectors += scope.launch(start = CoroutineStart.UNDISPATCHED) { radio.events.collect { onEvent(it) } }
        collectors += scope.launch(start = CoroutineStart.UNDISPATCHED) { radio.rxFrames.collect { onRxFrame(it) } }
    }

    /**
     * Brings the radio up and starts the pairing beacon: OPEN_DEVICE, INIT, wait for READY, SET_IDENTITY (the BSSID),
     * START_BEACON with the access point's beacon. Call it again after radiod restarts.
     *
     * @throws IllegalStateException if [start] was not called, radiod reports an error, or READY does not arrive
     * within [SpikeOptions.readyTimeoutMs].
     */
    suspend fun bringUp() {
        check(collectors.isNotEmpty()) { "bringUp(): start() must be called first, or radiod's answer is missed" }
        outcome.value = RadioOutcome.Pending
        log.i {
            "bringUp(): opening the adapter -> vid=${hex4(options.vid)} pid=${hex4(options.pid)} " +
                "channel=${apConfig.channel} bandwidthMhz=${options.bandwidthMhz} txPowerIndex=${options.txPowerIndex}"
        }
        radio.openDevice(options.vid, options.pid)
        radio.init(apConfig.channel, options.bandwidthMhz, options.txPowerIndex)

        when (val result = withTimeoutOrNull(options.readyTimeoutMs) { outcome.first { it !is RadioOutcome.Pending } }) {
            is RadioOutcome.Ready -> Unit
            is RadioOutcome.Failed ->
                throw IllegalStateException("bringUp(): radiod reported an error -> code=${result.code} text=${result.text}")
            else ->
                throw IllegalStateException("bringUp(): radiod did not report READY -> timeoutMs=${options.readyTimeoutMs}")
        }

        radio.setIdentity(bssid.toByteArray())
        radio.startBeacon(apConfig.beaconIntervalTu, ap.beacon())
        log.i {
            "bringUp(): pairing beacon started -> ssid=${apConfig.ssid} bssid=$bssid channel=${apConfig.channel} " +
                "intervalTu=${apConfig.beaconIntervalTu}"
        }
    }

    /**
     * Stops the beacon, asks radiod to shut down (it powers the chip down; a chip left running keeps beaconing) and
     * cancels the collectors. Runs to completion even when the calling coroutine is cancelled; each radio command is
     * best effort, so a radiod that is already gone does not stop the rest.
     */
    suspend fun stop() = withContext(NonCancellable) {
        log.i { "stop(): shutting the radio down -> decryptedFrames=$decryptedFrames" }
        bestEffort("stopping the beacon") { radio.stopBeacon() }
        bestEffort("asking radiod to shut down") { radio.shutdown() }
        collectors.forEach { it.cancel() }
        collectors.clear()
        log.i { "stop(): pcap written: $captureLabel -> frames=${capture.framesWritten}" }
    }

    // ---- radiod notifications -----------------------------------------------------------------

    private fun onEvent(event: RadioMessage) {
        when (event) {
            is RadioMessage.Ready -> {
                log.i {
                    "onEvent(): radio ready -> chipId=${event.chipId} firmware=${event.firmware} " +
                        "devourer=${event.devourerVersion}"
                }
                outcome.value = RadioOutcome.Ready
            }
            is RadioMessage.Error -> {
                log.e { "onEvent(): radiod error -> code=${event.code} text=${event.text}" }
                outcome.compareAndSet(RadioOutcome.Pending, RadioOutcome.Failed(event.code, event.text))
            }
            is RadioMessage.Status -> log.d {
                "onEvent(): radiod status -> rx=${event.rx} tx=${event.tx} txErrors=${event.txErrors} beacons=${event.beacons}"
            }
            else -> Unit
        }
    }

    // ---- received frames ----------------------------------------------------------------------

    /** Handles one received frame; a failure on one frame is logged and never ends the collector. */
    private suspend fun onRxFrame(radioFrame: RadioFrame) {
        try {
            val parsed = Ieee80211Frame.parse(radioFrame.frame) ?: return
            when {
                parsed.isManagement -> onManagementFrame(radioFrame, parsed)
                parsed.isDataFrame -> onDataFrame(radioFrame, parsed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.e(failure) { "onRxFrame(): frame processing failed -> message=${failure.message}" }
        }
    }

    private suspend fun onManagementFrame(radioFrame: RadioFrame, parsed: Ieee80211Frame) {
        val stateBefore = ap.state
        val responses = ap.onFrame(radioFrame.frame)
        val addressedToUs = parsed.addr1 == bssid || parsed.addr3 == bssid
        if (!addressedToUs && responses.isEmpty()) return // a neighbouring network's traffic

        logManagementFrame(radioFrame, parsed, responses.size)
        // Capture is best effort relative to the protocol: a failing append here would skip the auth/assoc response
        // and desync the station. Acceptable for a throwaway spike (a pcap write failure defeats its purpose anyway),
        // but TODO(KartRadioAp): the real AP must transmit responses first and never gate them on capture success.
        capture.append(radioFrame.frame)
        for (response in responses) {
            transmit(response)
            capture.append(response)
        }

        val stateAfter = ap.state
        if (stateBefore == ApState.Beaconing && stateAfter == ApState.Associated) {
            log.i { "onManagementFrame(): KartAssociated -> sta=${ap.station} aid=${ap.aid}" }
        } else if (stateBefore == ApState.Associated && stateAfter == ApState.Beaconing) {
            // The station left (or started over): free the single lease for its next DHCP exchange.
            dhcpServer.reset()
            log.i { "onManagementFrame(): station left, DHCP lease freed -> sta=${parsed.addr2}" }
        }
    }

    private fun logManagementFrame(radioFrame: RadioFrame, parsed: Ieee80211Frame, responseCount: Int) {
        val retry = if (parsed.retry) 1 else 0
        when {
            parsed.isAuth -> log.i {
                "onManagementFrame(): auth -> sta=${parsed.addr2} retry=$retry rssiDbm=${radioFrame.rssiDbm} responses=$responseCount"
            }
            parsed.isAssocRequest || parsed.isReassocRequest -> log.i {
                "onManagementFrame(): assoc -> sta=${parsed.addr2} retry=$retry rssiDbm=${radioFrame.rssiDbm} responses=$responseCount"
            }
            parsed.isDeauth || parsed.isDisassoc -> log.i {
                "onManagementFrame(): deauth -> sta=${parsed.addr2} reason=${parsed.reasonCode()}"
            }
            else -> log.d {
                "onManagementFrame(): management frame -> subtype=${parsed.frameControl.subtype} sta=${parsed.addr2} responses=$responseCount"
            }
        }
    }

    private suspend fun onDataFrame(radioFrame: RadioFrame, parsed: Ieee80211Frame) {
        val station = ap.station ?: return // nobody is associated yet
        val fromOurStation = parsed.toDs && parsed.addr1 == bssid && parsed.addr2 == station
        if (!fromOurStation) return // another network's data
        if (parsed.isNullData) return // power-save signalling, no payload
        if (!parsed.isProtected) {
            log.w { "onDataFrame(): ignoring an unprotected data frame -> sta=$station" }
            return
        }

        val plaintext = try {
            ap.decryptData(radioFrame.frame)
        } catch (rejected: CcmpException) {
            log.w { "onDataFrame(): CCMP rejected a frame -> sta=$station retry=${parsed.retry} reason=${rejected.message}" }
            return
        }

        capture.append(clearFrame(parsed, plaintext))
        decryptedFrames++
        if (decryptedFrames == 1) {
            log.i {
                "onDataFrame(): first data frame decrypted -> sta=$station bytes=${plaintext.size} " +
                    "pcap written: $captureLabel"
            }
        } else {
            log.d { "onDataFrame(): data frame decrypted -> sta=$station bytes=${plaintext.size} total=$decryptedFrames" }
        }
        onPayload(station, plaintext)
    }

    /** Peels LLC/SNAP and IPv4 off a decrypted body; only DHCP is acted on, anything else stays in the capture. */
    private suspend fun onPayload(station: MacAddress, body: ByteArray) {
        val snap = LlcSnap.unwrap(body)
        if (snap == null) {
            log.d { "onPayload(): body is not LLC/SNAP -> bytes=${body.size}" }
            return
        }
        if (snap.etherType != EtherType.IPV4) {
            log.d { "onPayload(): not answered by the spike -> etherType=0x${snap.etherType.toString(HEX_RADIX)}" }
            return
        }
        val packet = ipv4Receiver.receive(snap.payload) ?: return
        // Unlike NetstackNode, the spike does not drop IPv4 addressed elsewhere; harmless here because the kart's
        // pairing DHCP is broadcast and the capture is already scoped to the associated station at the 802.11 layer.
        if (packet.protocol != Ipv4Packet.PROTOCOL_UDP) {
            log.d { "onPayload(): not answered by the spike -> ipProtocol=${packet.protocol}" }
            return
        }
        val datagram = UdpDatagram.parse(packet.payload, packet.source, packet.destination)
        if (datagram == null || datagram.destinationPort != DhcpServer.SERVER_PORT) return

        val request = DhcpMessage.parse(datagram.payload)
        if (request == null) {
            log.w { "onPayload(): unparseable DHCP payload -> bytes=${datagram.payload.size}" }
            return
        }
        log.i {
            "onPayload(): DHCP ${dhcpTypeName(request.messageType)} from ${macText(request.clientMac)} -> " +
                "xid=${request.xid.toUInt().toString(HEX_RADIX)}"
        }
        val reply = dhcpServer.handle(request) ?: return
        sendDhcpReply(station, reply)
    }

    // ---- transmit -----------------------------------------------------------------------------

    /**
     * Wraps a DHCP [reply] as the netstack does (UDP 67 to 68 from the AP address, then IPv4, then LLC/SNAP;
     * broadcast for a NAK or a client that asked for it, otherwise to the offered address), encrypts it for the
     * station, transmits it and captures its plaintext form.
     *
     * TODO(FrameLink): once the real link layer exists, route replies through NetstackNode instead of duplicating
     * its UDP/IPv4/LLC wrapping here.
     */
    private suspend fun sendDhcpReply(station: MacAddress, reply: DhcpMessage) {
        val toBroadcast = reply.isBroadcast || reply.messageType == DhcpMessageType.NAK
        val destinationIp = if (toBroadcast) Ipv4Address.BROADCAST else reply.yourIp
        val udp = UdpDatagram(DhcpServer.SERVER_PORT, DhcpServer.CLIENT_PORT, reply.encode())
            .encode(AddressPlan.AP, destinationIp)
        val ip = Ipv4Packet(
            source = AddressPlan.AP,
            destination = destinationIp,
            protocol = Ipv4Packet.PROTOCOL_UDP,
            payload = udp,
            identification = nextIpIdentification,
        ).encode()
        nextIpIdentification = (nextIpIdentification + 1) and IP_IDENTIFICATION_MASK

        // There is no group key, so even an IP-broadcast reply goes to the station as a unicast 802.11 frame.
        val body = LlcSnap.wrap(EtherType.IPV4, ip)
        val mpdu = ap.encryptData(destination = station, source = bssid, body = body)
        transmit(mpdu)
        Ieee80211Frame.parse(mpdu)?.let { capture.append(clearFrame(it, body)) }
        log.i {
            "sendDhcpReply(): DHCP ${dhcpTypeName(reply.messageType)} to $station -> " +
                "xid=${reply.xid.toUInt().toString(HEX_RADIX)} yiaddr=${reply.yourIp} broadcast=$toBroadcast"
        }
    }

    private suspend fun transmit(frame: ByteArray) {
        radio.txFrame(options.txRate, options.txRetryLimit, TX_FLAGS_DEFAULT, frame)
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * The decrypted view of a protected data frame: the same MAC header with the Protected Frame bit cleared,
     * followed by the plaintext [body] (no CCMP header, no MIC), which is what Wireshark can dissect.
     */
    private fun clearFrame(source: Ieee80211Frame, body: ByteArray): ByteArray = Ieee80211Frame(
        frameControl = FrameControl(source.frameControl.value and PROTECTED_FRAME_BIT.inv()),
        addr1 = source.addr1,
        addr2 = source.addr2,
        addr3 = source.addr3,
        durationId = source.durationId,
        sequenceControl = source.sequenceControl,
        addr4 = source.addr4,
        qosControl = source.qosControl,
        htControl = source.htControl,
        body = body,
    ).toBytes()

    private suspend fun bestEffort(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            log.w { "stop(): $what failed, continuing -> message=${failure.message}" }
        }
    }

    private fun dhcpTypeName(type: Int?): String = when (type) {
        DhcpMessageType.DISCOVER -> "DISCOVER"
        DhcpMessageType.OFFER -> "OFFER"
        DhcpMessageType.REQUEST -> "REQUEST"
        DhcpMessageType.DECLINE -> "DECLINE"
        DhcpMessageType.ACK -> "ACK"
        DhcpMessageType.NAK -> "NAK"
        DhcpMessageType.RELEASE -> "RELEASE"
        DhcpMessageType.INFORM -> "INFORM"
        else -> "type=$type"
    }

    private fun macText(bytes: ByteArray): String =
        bytes.joinToString(":") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }

    private fun hex4(value: Int): String = value.toString(HEX_RADIX).padStart(HEX4_DIGITS, '0')

    /** The state of the bring-up as radiod reports it. */
    private sealed interface RadioOutcome {
        data object Pending : RadioOutcome

        data object Ready : RadioOutcome

        data class Failed(val code: UInt, val text: String) : RadioOutcome
    }

    private companion object {
        const val TAG = "SpikeRunner"
        const val HEX_RADIX = 16
        const val HEX4_DIGITS = 4
        const val BYTE_MASK = 0xFF
        const val IP_IDENTIFICATION_MASK = 0xFFFF

        /** TX_FRAME flags 0: unicast frames are sent expecting a hardware ACK (radiod sends group frames without). */
        const val TX_FLAGS_DEFAULT = 0

        /** The Protected Frame bit of the 16-bit Frame Control field. */
        const val PROTECTED_FRAME_BIT = 0x4000
    }
}
