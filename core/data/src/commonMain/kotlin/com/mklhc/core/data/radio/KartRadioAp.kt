package com.mklhc.core.data.radio

import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.netstack.FrameLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pairing network a pairing beacon advertises, so the access point can stand the network up exactly
 * as the QR code the app shows describes it.
 *
 * Business logic: to pair, the app displays a QR code carrying a pairing [seed], the pairing network's
 * [ssid] and the [channel]; the kart scans it and joins that network. For the kart to find the network,
 * the access point must beacon the SAME ssid and channel, so the orchestrator hands them to [KartRadioAp.beacon]
 * alongside the game-vs-pairing choice. The access point derives the pairing PSK itself as `SHA-256(seed)`
 * (per the design), so the pre-shared key is never carried here.
 *
 * The [seed] is secret key material: it is never logged or echoed. Because this class holds a raw array,
 * [equals]/[hashCode] compare the [seed] by content so two values with the same bytes are equal.
 *
 * @property ssid the pairing network name, matching the QR payload.
 * @property seed the 0x10-octet pairing seed from the QR payload; the PSK is `SHA-256(seed)`.
 * @property channel the Wi-Fi channel to advertise on, matching the QR payload.
 */
data class PairingNetwork(val ssid: String, val seed: ByteArray, val channel: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingNetwork) return false
        return ssid == other.ssid && channel == other.channel && seed.contentEquals(other.seed)
    }

    override fun hashCode(): Int {
        var result = ssid.hashCode()
        result = 31 * result + seed.contentHashCode()
        result = 31 * result + channel
        return result
    }
}

/**
 * What the USB radio adapter currently allows the app to do.
 *
 * Business logic: the session repository maps this to the first rungs of its link state:
 * [ABSENT] is `NoAdapter`, [PERMISSION_REQUIRED] is `AwaitingUsbPermission`, and [READY] means the
 * radio may be started (`RadioStarting`).
 */
enum class AdapterState {
    /** No supported adapter is attached. */
    ABSENT,

    /** An adapter is attached but the user has not granted the app access to it. */
    PERMISSION_REQUIRED,

    /** An adapter is attached and the app may use it. */
    READY,
}

/**
 * Where a started radio stands while it is brought back up underneath a running session.
 *
 * Business logic: an unplugged adapter takes the radio helper down, and the radio is brought up again by itself once
 * the adapter is back, which takes most of a minute on the real chip. Meanwhile nothing is on the air, so the session
 * shows this instead of "beaconing": it tells the driver to plug the adapter back in, and then to wait.
 */
enum class RadioRestore {
    /** The radio is up, or not started: nothing is being restored. */
    NONE,

    /** The radio is gone and no usable adapter is plugged in: it comes back once one is. */
    WAITING_FOR_ADAPTER,

    /** The radio is being brought up again on an adapter (the chip starts, then the network is served again). */
    BRINGING_UP,
}

/** The [KartRadioAp.radioRestore] of a radio layer that is never restored underneath the session. */
private val NEVER_RESTORED: StateFlow<RadioRestore> = MutableStateFlow(RadioRestore.NONE).asStateFlow()

/**
 * The seam between the session repository and the radio + access-point layer.
 *
 * Business logic: everything below the network stack is one unit as far as the orchestrator is
 * concerned. It brings the USB radio up, runs the one-station Wi-Fi access point on it (beacons,
 * association, encryption) and exposes the result as a [FrameLink] that the network stack (DHCP, TCP,
 * UDP) runs on. The orchestrator never sees 802.11; it only needs to start and stop the unit, say
 * which of the two networks to advertise, and learn whether a station is associated. The production
 * implementation is [RadioKartRadioAp], the access point over the radio link; the session repository
 * is written and tested against this interface and [FakeKartRadioAp].
 *
 * The implementation owns the credentials: it derives the game network's SSID and key from the
 * installation identity and takes the BSSID and channel from the pairing store, so the orchestrator
 * only names the [KartNetworkKind].
 *
 * Failure contract: [start] and [beacon] throw when the radio or the access point cannot do what was
 * asked (the orchestrator turns that into its failed or recovering state); they must let coroutine
 * cancellation propagate. Neither [stop] nor [requestAdapterPermission] throws.
 */
interface KartRadioAp {

    /**
     * The link the network stack runs on: network-layer frames to and from the single associated
     * station. It is valid for the lifetime of the object and survives [stop] / [start] cycles, so the
     * stack can be built once. See [FrameLink] for the buffering contract of `inbound`.
     */
    val frameLink: FrameLink

    /** Whether a supported adapter is attached and usable; emits the current value on collection. */
    val adapterState: StateFlow<AdapterState>

    /** True while a station is associated with the access point; emits the current value on collection. */
    val associatedStation: StateFlow<Boolean>

    /**
     * Whether the radio is being brought back up underneath the session, and why it is not on the air meanwhile;
     * [RadioRestore.NONE] for a layer that never restores the radio by itself (the default).
     */
    val radioRestore: StateFlow<RadioRestore> get() = NEVER_RESTORED

    /**
     * Asks the platform for the user's permission to use the attached adapter. Fire-and-forget: the
     * outcome arrives through [adapterState] (to [AdapterState.READY] when granted). A no-op where no
     * permission is needed.
     */
    fun requestAdapterPermission()

    /**
     * Brings the radio up. Suspends until it is ready to beacon. Only meaningful while
     * [adapterState] is [AdapterState.READY]. Idempotent when already started.
     *
     * @throws AdapterUnavailableException when no usable adapter is found right before the bring-up; the radio is
     *   left down, and the orchestrator goes back to waiting for an adapter.
     * @throws Exception when the radio cannot be started.
     */
    suspend fun start()

    /**
     * Stops beaconing and brings the radio down; [associatedStation] becomes false. Safe to call when
     * already stopped.
     */
    suspend fun stop()

    /**
     * Starts advertising [network], or switches to it when another network is being advertised (the
     * implied beacon update). Requires a started radio.
     *
     * When [network] is [KartNetworkKind.PAIRING] the caller MUST supply [pairing] so the access point
     * advertises the pairing SSID and channel the QR code describes and derives the pairing PSK from the
     * seed; for [KartNetworkKind.GAME] the credentials come from the installation identity and [pairing]
     * MUST be null.
     *
     * @param network which network to advertise.
     * @param pairing the pairing network to stand up; required (and only valid) when [network] is PAIRING.
     * @throws IllegalStateException if the radio is not started.
     * @throws IllegalArgumentException if [pairing] is missing for PAIRING or supplied for GAME.
     * @throws Exception when the beacon cannot be set up.
     */
    suspend fun beacon(network: KartNetworkKind, pairing: PairingNetwork? = null)
}
