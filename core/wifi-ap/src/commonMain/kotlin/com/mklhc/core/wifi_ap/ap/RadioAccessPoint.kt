package com.mklhc.core.wifi_ap.ap

import co.touchlab.kermit.Logger
import com.mklhc.core.crypto.CryptoPrimitives
import com.mklhc.core.netstack.FrameLink
import com.mklhc.core.netstack.LinkPayload
import com.mklhc.core.radio.RadioFrame
import com.mklhc.core.radio.RadioLink
import com.mklhc.core.radio.RadioMessage
import com.mklhc.core.wifi_ap.crypto.CcmpException
import com.mklhc.core.wifi_ap.mac.FrameControl
import com.mklhc.core.wifi_ap.mac.Ieee80211Frame
import com.mklhc.core.wifi_ap.mac.LlcSnap
import com.mklhc.core.wifi_ap.mac.MacAddress
import com.mklhc.core.wifi_ap.pcap.PcapWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/*
 * The access point's link layer: the netstack's FrameLink implemented over radiod's RadioLink.
 *
 * Original code, moved and generalised from the radio hardware spike's former glue class
 * (app/src/desktopMain/kotlin/com/mklhc/app/spike/SpikeRunner.kt, removed when this class replaced it). It only wires
 * modules that already exist
 * (RadioLink, ApStateMachine, LlcSnap, PcapWriter) to the FrameLink boundary; the 802.11 behaviour itself lives in
 * ApStateMachine, whose AP design reference (devourer ap_responder.cpp, no code copied) is cited there.
 */

/**
 * The radio tunables that are not part of the network description ([ApConfig]).
 *
 * Business logic: one value set per adapter and platform. The defaults are the reference adapter on desktop, opened
 * by USB vendor/product id (the Android form, which hands radiod a file descriptor, comes in Phase 2), and the
 * 802.11g settings the kart network uses.
 *
 * @property vid the adapter's USB vendor id; the default is the reference RTL8812CU (0bda:c812).
 * @property pid the adapter's USB product id.
 * @property bandwidthMhz the channel width handed to radiod's INIT (20 MHz: the kart network is 802.11g).
 * @property txPowerIndex the TX power index for INIT; 0 keeps the chip default.
 * @property txRate the rate string for every frame the access point transmits.
 * @property txRetryLimit the per-frame retry limit sent with TX_FRAME (radiod currently applies one device-wide limit).
 * @property readyTimeoutMs how long [RadioAccessPoint.bringUp] waits for radiod's READY after INIT.
 */
data class RadioOptions(
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
 * The single-station Wi-Fi access point on the USB radio: the [FrameLink] the network stack talks through, built on
 * the [RadioLink] to radiod.
 *
 * Business logic: the kart joins the network the app hosts, gets its address by DHCP, resolves the app by ARP and
 * opens the RCD TCP connections. Everything below IP happens here, frame by frame:
 *
 * - [start] subscribes to the radio's two hot flows (received frames and radiod notifications) before anything is
 *   commanded, because they have no replay and an answer arriving before the subscription would be lost.
 * - [bringUp] opens the adapter and brings the radio up on a channel, waiting for radiod's READY. It is called again
 *   whenever radiod restarts; after it nothing is beaconing and no network is served.
 * - [serve] starts (or switches to) one network: a fresh [ApStateMachine], so the station table is reset and any
 *   associated station is dropped (switching networks resets the station table by design), the BSSID handed to the
 *   hardware ACK engine, and the hardware beacon started or replaced.
 * - Every received frame goes through one function, in arrival order, on the collecting coroutine. Management frames
 *   (probe, authentication, association, deauthentication) are fed to the state machine and its responses are
 *   transmitted. Data frames are accepted only from the associated station (to-DS, addr1 = BSSID, addr2 = the
 *   station), CCMP-decrypted, LLC/SNAP-unwrapped and delivered on [inbound] as a [LinkPayload]. Frames of
 *   neighbouring networks are ignored.
 * - [send] wraps a payload in LLC/SNAP, encrypts it for the associated station and transmits it. The link has one
 *   station, so there is no link address: with nobody associated the frame is dropped like a lost one, and TCP
 *   retransmits.
 * - [capture], when set, records the exchange as a pcap: management frames as received and as sent, data frames in
 *   their decrypted form, in both directions. Capturing is best effort and comes after transmitting, so a failing
 *   capture never delays, reorders or stops protocol traffic.
 *
 * Milestone log lines (the hardware GO criteria): `auth -> sta= retry= rssiDbm=`, `assoc -> sta= retry= rssiDbm=`,
 * `deauth -> sta= reason=`, `KartAssociated -> sta= aid=`, `station left -> sta=` and the first decrypted data frame
 * of each association. The network's CCMP key, its PSK and the pairing seed are never logged.
 *
 * Not covered: power-save buffering, per-TID replay windows and group-addressed data (there is no group key, so even
 * an IP broadcast reply goes to the station as a unicast 802.11 frame). The DHCP lease is not touched here: the
 * integration layer owns it.
 *
 * Threading: [ApStateMachine], [PcapWriter] and the CCMP packet-number counter are not thread-safe, and the network
 * stack calls [send] from its own coroutines while the receive collector runs. One [Mutex] guards the state machine,
 * the capture and the lifecycle state; [send] holds it across encryption and TX_FRAME so packet numbers reach the
 * air in increasing order (the kart's CCMP replay window drops a lower packet number that arrives after a higher
 * one), and the receive collector holds it while handling a frame and transmitting its responses. [send] and the
 * receive path are safe from any coroutine. The lifecycle calls ([start], [bringUp], [serve], [stop]) must be made
 * one at a time by the owner, with one exception: [stop] may run while a [bringUp] is still in progress (waiting for
 * READY, typically). That bring-up then fails with an IllegalStateException and records nothing, so a later [serve]
 * fails too instead of commanding a radio that was shut down. It is a plain failure, not a CancellationException,
 * because the caller was not cancelled and must not mistake it for a silent cancellation.
 *
 * @param radio the link to radiod (a [FakeRadioLink][com.mklhc.core.radio.FakeRadioLink] in tests).
 * @param options the radio tunables.
 * @param primitives the crypto provider for CCMP.
 * @param log the logger; injectable so tests can assert the milestones.
 */
class RadioAccessPoint(
    private val radio: RadioLink,
    private val options: RadioOptions = RadioOptions(),
    private val primitives: CryptoPrimitives = CryptoPrimitives(),
    private val log: Logger = Logger.withTag(TAG),
) : FrameLink {

    /** Guards every field marked "guarded by [lock]", the capture appends, and spans encrypt + TX_FRAME. */
    private val lock = Mutex()

    /** What radiod last said about the bring-up; [RadioOutcome.Pending] from the moment [bringUp] starts. */
    private val outcome = MutableStateFlow<RadioOutcome>(RadioOutcome.Pending)

    // Both written only by publishStation(), in the same step, so neither is ever behind the other.
    private val stationState = MutableStateFlow<MacAddress?>(null)
    private val stationAssociated = MutableStateFlow(false)

    /** Never closed: it outlives stop/start and network stack restarts, as the [FrameLink] contract requires. */
    private val inboundFrames = Channel<LinkPayload>(INBOUND_CAPACITY)

    /** The two radio collectors; owned by the lifecycle calls [start] and [stop]. */
    private val collectors = ArrayList<Job>()

    // Guarded by [lock].
    private var network: ServedNetwork? = null
    private var broughtUpChannel: Int? = null
    private var beaconRunning = false
    private var identity: MacAddress? = null
    private var decryptedFrames = 0L
    private var decryptedSinceAssociation = 0
    private var captureFailing = false
    private var inboundDropping = false
    private var inboundDropped = 0L

    // Guarded by [lock]. Counts the stops; a bring-up records its channel only if no stop ran since it began.
    private var stopGeneration = 0L

    /** The associated station, or null while only beaconing (and after [serve], [bringUp] or [stop] dropped it). */
    val associatedStation: StateFlow<MacAddress?> = stationState.asStateFlow()

    /**
     * Whether a station is associated: exactly `associatedStation.value != null`, published in the same step as
     * [associatedStation] rather than mapped from it, so it is already false when [serve], [bringUp] or [stop]
     * returns. A mapped flow would lag one dispatch behind, and a caller waiting for an association could pass on the
     * stale value of the network just dropped.
     */
    val isStationAssociated: StateFlow<Boolean> = stationAssociated.asStateFlow()

    /**
     * Where the exchange is recorded, or null for no capture (the default). Management frames are captured as received
     * and as sent; data frames in their decrypted form (Protected bit cleared, no CCMP header or MIC), both directions.
     * A failing append is logged and never affects the protocol traffic.
     *
     * It can be set or cleared at any time, without waiting for the frame being handled: an append already in progress
     * still goes to the writer it started with, so a replaced writer may receive that one more frame after the swap.
     * Close the replaced writer's sink only once [stop] has returned; nothing is appended after that.
     */
    @Volatile
    var capture: PcapWriter? = null

    /**
     * The network-layer payloads the associated station sent, in arrival order: a buffered, cold flow over a channel
     * that survives [stop]/[start] and network stack restarts. When [INBOUND_CAPACITY] payloads are already waiting,
     * newer ones are dropped with a warning; the radio collector never waits for the network stack. Payloads still
     * queued when the network is dropped ([serve], [bringUp], [stop]) are discarded: they belong to an association that
     * no longer exists.
     */
    override val inbound: Flow<LinkPayload> = inboundFrames.receiveAsFlow()

    /**
     * Subscribes to the radio's received frames and notifications, launching the collectors in [scope]. The
     * subscriptions are in place when this returns, so a following [bringUp] cannot miss radiod's answer. Give it a
     * scope that lives as long as the access point is in use; [stop] cancels the collectors.
     *
     * @throws IllegalStateException if already started (call [stop] first).
     */
    fun start(scope: CoroutineScope) {
        check(collectors.isEmpty()) { "start(): already started" }
        log.i { "start(): listening to the radio" }
        // UNDISPATCHED runs each launch up to the collector's first suspension, which is the moment it subscribes.
        collectors += scope.launch(start = CoroutineStart.UNDISPATCHED) { radio.events.collect { onEvent(it) } }
        collectors += scope.launch(start = CoroutineStart.UNDISPATCHED) { radio.rxFrames.collect { onRxFrame(it) } }
    }

    /**
     * Brings the radio up on [channel]: OPEN_DEVICE, INIT, then waits for radiod's READY. A new bring-up means radiod
     * restarted or the radio was down, so the served network and its station are forgotten and the next [serve] sets
     * the identity and starts the beacon again.
     *
     * A [stop] that runs while this waits wakes it up as soon as the collectors are gone, and a READY that still
     * arrives after a stop began is not taken: the channel is recorded only if no stop ran since this bring-up began,
     * checked under the lock.
     *
     * @param channel the 2.4 GHz channel, one of [ApConfig.ALLOWED_CHANNELS]; every served network must use it.
     * @throws IllegalStateException if [start] was not called, [stop] ran while this was in progress (nothing is
     * recorded, so a following [serve] fails), radiod reports an error, or READY does not arrive within
     * [RadioOptions.readyTimeoutMs].
     * @throws IllegalArgumentException if [channel] is not one of [ApConfig.ALLOWED_CHANNELS].
     */
    suspend fun bringUp(channel: Int) {
        check(collectors.isNotEmpty()) { "bringUp(): start() must be called first, or radiod's answer is missed" }
        require(channel in ApConfig.ALLOWED_CHANNELS) {
            "bringUp(): channel must be one of ${ApConfig.ALLOWED_CHANNELS} -> channel=$channel"
        }
        val (generation, dropped) = lock.withLock {
            forgetRadioState()
            outcome.value = RadioOutcome.Pending
            stopGeneration to dropNetwork("bringUp()")
        }
        if (dropped != null) log.i { "bringUp(): the radio restarts, station dropped -> sta=$dropped" }

        log.i {
            "bringUp(): opening the adapter -> vid=${hex4(options.vid)} pid=${hex4(options.pid)} " +
                "channel=$channel bandwidthMhz=${options.bandwidthMhz} txPowerIndex=${options.txPowerIndex}"
        }
        radio.openDevice(options.vid, options.pid)
        radio.init(channel, options.bandwidthMhz, options.txPowerIndex)

        val result = withTimeoutOrNull(options.readyTimeoutMs) { outcome.first { it !is RadioOutcome.Pending } }
        lock.withLock {
            // A stop since this bring-up began shut radiod down: recording the channel would let a later serve()
            // send SET_IDENTITY and START_BEACON after SHUTDOWN.
            check(stopGeneration == generation) {
                "bringUp(): the access point was stopped during the bring-up, nothing recorded -> channel=$channel"
            }
            when (result) {
                is RadioOutcome.Ready -> Unit
                is RadioOutcome.Failed ->
                    throw IllegalStateException("bringUp(): radiod reported an error -> code=${result.code} text=${result.text}")
                // null: the timeout ran out. Stopped always comes with a new stop generation, refused above.
                else ->
                    throw IllegalStateException("bringUp(): radiod did not report READY -> timeoutMs=${options.readyTimeoutMs}")
            }
            broughtUpChannel = channel
        }
        log.i { "bringUp(): radio up, ready to serve -> channel=$channel" }
    }

    /**
     * Serves [config]: replaces the state machine with a new one (the station table is reset, so an associated
     * station is dropped, [associatedStation] turns null and the payloads it left queued on [inbound] are discarded),
     * sends SET_IDENTITY when the BSSID changed or after a bring-up, and starts the hardware beacon (START_BEACON the
     * first time after a bring-up, UPDATE_BEACON afterwards; an update keeps the interval of the running beacon). It
     * always replaces: not serving an unchanged network twice is the caller's business.
     *
     * After [stop] has begun it touches nothing: stop forgets the brought-up channel under the lock before it sends
     * STOP_BEACON and SHUTDOWN, and this checks the channel under the same lock before its first radio command.
     *
     * @throws IllegalStateException if the radio is not up ([bringUp] has not succeeded since the last [stop]).
     * @throws IllegalArgumentException if [config] names another channel than the one the radio was brought up on.
     */
    suspend fun serve(config: ApConfig) {
        lock.withLock {
            val channel = checkNotNull(broughtUpChannel) { "serve(): the radio is not up, call bringUp() first" }
            require(config.channel == channel) {
                "serve(): the network is on another channel than the radio -> configChannel=${config.channel} " +
                    "radioChannel=$channel"
            }
            val machine = ApStateMachine(config, primitives)
            val dropped = dropNetwork("serve()")
            network = ServedNetwork(config, machine)
            if (dropped != null) log.i { "serve(): network switched, station dropped -> sta=$dropped" }

            if (identity != config.bssid) {
                radio.setIdentity(config.bssid.toByteArray())
                identity = config.bssid
            }
            if (beaconRunning) {
                radio.updateBeacon(machine.beacon())
                log.i { "serve(): beacon updated -> ssid=${config.ssid} bssid=${config.bssid} channel=$channel" }
            } else {
                radio.startBeacon(config.beaconIntervalTu, machine.beacon())
                beaconRunning = true
                log.i {
                    "serve(): beacon started -> ssid=${config.ssid} bssid=${config.bssid} channel=$channel " +
                        "intervalTu=${config.beaconIntervalTu}"
                }
            }
        }
    }

    /**
     * Drops the station and forgets the network and the radio state, stops the beacon, asks radiod to shut down (it
     * powers the chip down; a chip left running keeps beaconing) and cancels the collectors. The forgetting comes
     * first, under the lock: from then on nothing is answered or served, and a [bringUp] still in progress fails
     * instead of recording its channel (one waiting for READY is woken when the collectors are gone). Runs to
     * completion even when the calling coroutine is cancelled; each radio command is best effort, so a radiod that is
     * already gone does not stop the rest. Safe to call twice: when not started it does nothing. [inbound] stays
     * usable for a later [start]; the payloads still queued on it are discarded.
     */
    suspend fun stop(): Unit = withContext(NonCancellable) {
        if (collectors.isEmpty()) {
            log.d { "stop(): not started, nothing to stop" }
            return@withContext
        }
        log.i { "stop(): shutting the radio down" }
        val (dropped, decrypted) = lock.withLock {
            stopGeneration++
            forgetRadioState()
            dropNetwork("stop()") to decryptedFrames
        }
        bestEffort("stopping the beacon") { radio.stopBeacon() }
        bestEffort("asking radiod to shut down") { radio.shutdown() }
        collectors.forEach { it.cancel() }
        collectors.forEach { it.join() }
        collectors.clear()
        // No READY or ERROR can arrive any more: wake a bring-up still waiting, so it fails now, not at its timeout.
        outcome.value = RadioOutcome.Stopped
        log.i { "stop(): radio stopped -> droppedStation=$dropped decryptedFrames=$decrypted" }
    }

    /**
     * Transmits [payload] to the associated station: LLC/SNAP with [etherType], CCMP-encrypted (from-DS, addr3 = the
     * BSSID, which is the AP's own address), TX_FRAME, then the clear form to the [capture]. With no associated
     * station the frame is dropped at debug level, and a TX_FRAME radiod does not take is logged as a lost frame;
     * neither throws, because TCP retransmits.
     */
    override suspend fun send(etherType: Int, payload: ByteArray) {
        lock.withLock {
            val served = network
            val destination = served?.machine?.station
            if (served == null || destination == null) {
                log.d {
                    "send(): no associated station, dropping the frame -> " +
                        "etherType=0x${etherType.toString(HEX_RADIX)} size=${payload.size}"
                }
                return
            }
            val body = LlcSnap.wrap(etherType, payload)
            // Encrypt and transmit under one hold of the lock, so packet numbers go on air in increasing order.
            val mpdu = served.machine.encryptData(destination = destination, source = served.bssid, body = body)
            if (!transmit(mpdu, FRAME_KIND_DATA)) return
            if (capture != null) Ieee80211Frame.parse(mpdu)?.let { captureFrame(clearFrame(it, body)) }
        }
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

    /** Handles one received frame under the lock; a failure on one frame is logged and never ends the collector. */
    private suspend fun onRxFrame(radioFrame: RadioFrame) {
        try {
            val parsed = Ieee80211Frame.parse(radioFrame.frame) ?: return
            when {
                parsed.isManagement -> lock.withLock { onManagementFrame(radioFrame, parsed) }
                parsed.isDataFrame -> lock.withLock { onDataFrame(radioFrame, parsed) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.e(failure) { "onRxFrame(): frame processing failed -> message=${failure.message}" }
        }
    }

    /** Feeds a management frame to the state machine and transmits its responses. Call with [lock] held. */
    private suspend fun onManagementFrame(radioFrame: RadioFrame, parsed: Ieee80211Frame) {
        val served = network ?: return // nothing is served yet, so nobody is answered
        val machine = served.machine
        val stationBefore = machine.station
        val responses = machine.onFrame(radioFrame.frame)
        val addressedToUs = parsed.addr1 == served.bssid || parsed.addr3 == served.bssid
        if (!addressedToUs && responses.isEmpty()) return // a neighbouring network's traffic

        logManagementFrame(radioFrame, parsed, responses.size)
        // Transmit first: the station is waiting for the response, and capturing must never delay or block it.
        val transmitted = responses.filter { transmit(it, FRAME_KIND_MANAGEMENT) }
        captureFrame(radioFrame.frame)
        transmitted.forEach { captureFrame(it) }

        val stationAfter = machine.station
        if (stationAfter == stationBefore) return
        publishStation(stationAfter)
        if (stationAfter != null) {
            decryptedSinceAssociation = 0
            log.i { "onManagementFrame(): KartAssociated -> sta=$stationAfter aid=${machine.aid}" }
        } else {
            log.i { "onManagementFrame(): station left -> sta=$stationBefore" }
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

    /**
     * Decrypts a data frame from the associated station, captures its clear form and delivers its payload on
     * [inbound]. Everything else (other networks, other stations, null data, unprotected or tampered frames, bodies
     * that are not LLC/SNAP) is dropped. Call with [lock] held.
     */
    private fun onDataFrame(radioFrame: RadioFrame, parsed: Ieee80211Frame) {
        val served = network ?: return
        val station = served.machine.station ?: return // nobody is associated yet
        val fromOurStation = parsed.toDs && parsed.addr1 == served.bssid && parsed.addr2 == station
        if (!fromOurStation) return // another network's or another station's data
        if (parsed.isNullData) return // power-save signalling, no payload
        if (!parsed.isProtected) {
            log.w { "onDataFrame(): ignoring an unprotected data frame -> sta=$station" }
            return
        }

        val plaintext = try {
            served.machine.decryptData(radioFrame.frame)
        } catch (rejected: CcmpException) {
            log.w { "onDataFrame(): CCMP rejected a frame -> sta=$station retry=${parsed.retry} reason=${rejected.message}" }
            return
        }

        captureFrame(clearFrame(parsed, plaintext))
        decryptedFrames++
        decryptedSinceAssociation++
        if (decryptedSinceAssociation == 1) {
            log.i { "onDataFrame(): first data frame decrypted -> sta=$station bytes=${plaintext.size} total=$decryptedFrames" }
        } else {
            log.d { "onDataFrame(): data frame decrypted -> sta=$station bytes=${plaintext.size} total=$decryptedFrames" }
        }

        val snap = LlcSnap.unwrap(plaintext)
        if (snap == null) {
            log.d { "onDataFrame(): body is not LLC/SNAP, dropping it -> sta=$station bytes=${plaintext.size}" }
            return
        }
        deliver(LinkPayload(snap.etherType, snap.payload))
    }

    /** Queues [payload] for the network stack without ever waiting; a full queue drops it. Call with [lock] held. */
    private fun deliver(payload: LinkPayload) {
        if (inboundFrames.trySend(payload).isSuccess) {
            inboundDropping = false
            return
        }
        inboundDropped++
        // Warn once per overflow episode; a stalled network stack would otherwise flood the log with one line per frame.
        if (!inboundDropping) {
            inboundDropping = true
            log.w {
                "deliver(): the inbound queue is full, dropping frames until the network stack catches up -> " +
                    "etherType=0x${payload.etherType.toString(HEX_RADIX)} capacity=$INBOUND_CAPACITY dropped=$inboundDropped"
            }
        } else {
            log.d { "deliver(): the inbound queue is still full, frame dropped -> dropped=$inboundDropped" }
        }
    }

    // ---- transmit and capture -----------------------------------------------------------------

    /**
     * Hands one MPDU to radiod. A command radiod does not take (it has gone away) is a lost frame: logged and
     * reported as false, never thrown, so the caller's protocol state carries on. Cancellation propagates.
     */
    private suspend fun transmit(frame: ByteArray, kind: String): Boolean = try {
        radio.txFrame(options.txRate, options.txRetryLimit, TX_FLAGS_DEFAULT, frame)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        log.w { "transmit(): radiod did not take the frame, treating it as lost -> kind=$kind bytes=${frame.size} message=${failure.message}" }
        false
    }

    /**
     * Appends [frame] to the [capture], if any. Best effort: a failure is logged (a warning for the first of a run,
     * then debug) and swallowed; the append does not suspend, so nothing here can be a cancellation of the caller.
     * Call with [lock] held.
     */
    private fun captureFrame(frame: ByteArray) {
        val writer = capture ?: return
        try {
            writer.append(frame)
            captureFailing = false
        } catch (failure: Exception) {
            if (!captureFailing) {
                captureFailing = true
                log.w { "captureFrame(): capture append failed, the traffic carries on uncaptured -> message=${failure.message}" }
            } else {
                log.d { "captureFrame(): capture append failed again -> message=${failure.message}" }
            }
        }
    }

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

    // ---- helpers ------------------------------------------------------------------------------

    /** Marks the radio as down: no channel, no beacon, no identity. Call with [lock] held. */
    private fun forgetRadioState() {
        broughtUpChannel = null
        beaconRunning = false
        identity = null
    }

    /**
     * Forgets the served network, publishes "no station" and discards the payloads still queued on [inbound]: they
     * came from the dropped network's association and mean nothing to the next one (a network stack that is being
     * relaunched would otherwise read them first). Returns the station it dropped. Call with [lock] held.
     *
     * @param caller the lifecycle call dropping the network, for the log line.
     */
    private fun dropNetwork(caller: String): MacAddress? {
        val dropped = network?.machine?.station
        network = null
        publishStation(null)
        var discarded = 0
        while (inboundFrames.tryReceive().isSuccess) discarded++
        inboundDropping = false
        if (discarded > 0) log.d { "$caller: discarded the payloads queued from the dropped network -> count=$discarded" }
        return dropped
    }

    /** Publishes [station] on [associatedStation] and [isStationAssociated] in one step. Call with [lock] held. */
    private fun publishStation(station: MacAddress?) {
        stationState.value = station
        stationAssociated.value = station != null
    }

    /**
     * Runs one shutdown command, logging a failure instead of throwing it. Inside [stop]'s NonCancellable context a
     * CancellationException can only come from the radio itself, so it is a failed command like any other; the
     * caller's own cancellation is still delivered when [stop] returns.
     */
    private suspend fun bestEffort(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            log.w { "stop(): $what failed, continuing -> message=${failure.message}" }
        }
    }

    private fun hex4(value: Int): String = value.toString(HEX_RADIX).padStart(HEX4_DIGITS, '0')

    /** The network being served: its description and the state machine that answers for it. */
    private class ServedNetwork(val config: ApConfig, val machine: ApStateMachine) {
        val bssid: MacAddress get() = config.bssid
    }

    /** The state of the bring-up as radiod reports it, or [Stopped] once [stop] has cancelled the collectors. */
    private sealed interface RadioOutcome {
        data object Pending : RadioOutcome

        data object Ready : RadioOutcome

        data class Failed(val code: UInt, val text: String) : RadioOutcome

        data object Stopped : RadioOutcome
    }

    companion object {
        /**
         * How many received payloads may wait for the network stack before newer ones are dropped. The stack drains
         * the queue continuously, so it only fills when the stack stalls; then dropping (TCP retransmits) is better
         * than holding up the radio collector, which also answers the station's management frames.
         */
        const val INBOUND_CAPACITY = 256

        private const val TAG = "RadioAccessPoint"
        private const val HEX_RADIX = 16
        private const val HEX4_DIGITS = 4
        private const val FRAME_KIND_DATA = "data"
        private const val FRAME_KIND_MANAGEMENT = "management"

        /** TX_FRAME flags 0: unicast frames are sent expecting a hardware ACK (radiod sends group frames without). */
        private const val TX_FLAGS_DEFAULT = 0

        /** The Protected Frame bit of the 16-bit Frame Control field. */
        private const val PROTECTED_FRAME_BIT = 0x4000
    }
}
