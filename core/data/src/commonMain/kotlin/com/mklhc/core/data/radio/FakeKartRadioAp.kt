package com.mklhc.core.data.radio

import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.netstack.FrameLink
import com.mklhc.core.netstack.LinkPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A controllable stand-in for the radio + access-point layer, for the session repository's tests
 * (and for running the orchestrator without hardware).
 *
 * Business logic: nothing here touches a radio. The test scripts the world through setters and
 * inspects what the code under test did through recorded calls:
 * - [setAdapterState] and [setStationAssociated] drive [adapterState] and [associatedStation];
 *   [requestAdapterPermission] is counted and, with `grantPermissionOnRequest`, moves a
 *   [AdapterState.PERMISSION_REQUIRED] adapter to [AdapterState.READY] like a user tapping "allow".
 * - [start], [stop] and [beacon] are recorded ([startCount], [stopCount], [beaconCalls],
 *   [currentBeacon], [isStarted]); [startFailure] and [beaconFailure] make them throw, standing in for
 *   a radio that cannot come up. [beacon] before [start] is a programming error, as in the real layer.
 * - [frameLink] is the access-point end of an [InMemoryFrameLink] pair and [stationLink] the other end,
 *   so a simulated kart (a fake station) can sit on [stationLink] and talk to the network stack
 *   running on [frameLink]. [sentByAp] and [sentByStation] expose what each side transmitted.
 *
 * The fake's pieces are independent: for example setting the adapter to [AdapterState.ABSENT] does not
 * by itself stop the radio, so a test controls each effect explicitly. It is not thread-safe; drive it
 * from the test's coroutine. It lives in the main source set so other modules' tests (and a hardware-free
 * demo build) can use it; it carries no test-framework dependency.
 *
 * @param initialAdapterState the starting [adapterState]; ready by default.
 * @param grantPermissionOnRequest whether [requestAdapterPermission] grants a pending permission at once.
 */
class FakeKartRadioAp(
    initialAdapterState: AdapterState = AdapterState.READY,
    private val grantPermissionOnRequest: Boolean = false,
) : KartRadioAp {

    private val links = InMemoryFrameLink.pair()
    private val adapterStateFlow = MutableStateFlow(initialAdapterState)
    private val associatedFlow = MutableStateFlow(false)
    private val beacons = ArrayList<KartNetworkKind>()

    /** The access-point end of the link; the network stack under test runs on it. */
    override val frameLink: FrameLink = links.first

    /** The station (kart) end of the link; a simulated kart sends and receives here. */
    val stationLink: FrameLink = links.second

    override val adapterState: StateFlow<AdapterState> = adapterStateFlow.asStateFlow()

    override val associatedStation: StateFlow<Boolean> = associatedFlow.asStateFlow()

    /** When non-null, [start] throws it (after counting the attempt) and the radio stays stopped. */
    var startFailure: Throwable? = null

    /** When non-null, [beacon] throws it and records nothing. */
    var beaconFailure: Throwable? = null

    /** True between a successful [start] and the next [stop]. */
    var isStarted: Boolean = false
        private set

    /** How many times [start] was called, including failed attempts. */
    var startCount: Int = 0
        private set

    /** How many times [stop] was called. */
    var stopCount: Int = 0
        private set

    /** How many times [requestAdapterPermission] was called. */
    var permissionRequestCount: Int = 0
        private set

    /** How many times [beacon] was entered, including attempts that threw [beaconFailure]. */
    var beaconAttemptCount: Int = 0
        private set

    /** Every network [beacon] was asked to advertise, in order; kept across [stop] for assertions. */
    val beaconCalls: List<KartNetworkKind> get() = beacons.toList()

    /** The network being advertised now, or null when not beaconing. */
    var currentBeacon: KartNetworkKind? = null
        private set

    /** The pairing network the last PAIRING [beacon] carried, or null when none has been requested. */
    var lastPairingNetwork: PairingNetwork? = null
        private set

    /** Frames the network stack sent through [frameLink], in order. */
    val sentByAp: List<LinkPayload> get() = links.first.sent

    /** Frames the simulated station sent through [stationLink], in order. */
    val sentByStation: List<LinkPayload> get() = links.second.sent

    /** Scripts the adapter as attached, permission-pending, or absent. */
    fun setAdapterState(state: AdapterState) {
        adapterStateFlow.value = state
    }

    /** Scripts whether a station is associated. */
    fun setStationAssociated(associated: Boolean) {
        associatedFlow.value = associated
    }

    override fun requestAdapterPermission() {
        permissionRequestCount++
        if (grantPermissionOnRequest && adapterStateFlow.value == AdapterState.PERMISSION_REQUIRED) {
            adapterStateFlow.value = AdapterState.READY
        }
    }

    override suspend fun start() {
        startCount++
        startFailure?.let { throw it }
        isStarted = true
    }

    override suspend fun stop() {
        stopCount++
        isStarted = false
        currentBeacon = null
        associatedFlow.value = false
    }

    override suspend fun beacon(network: KartNetworkKind, pairing: PairingNetwork?) {
        check(isStarted) { "beacon($network) called before start()" }
        require((network == KartNetworkKind.PAIRING) == (pairing != null)) {
            "beacon($network) needs a PairingNetwork only when pairing"
        }
        beaconAttemptCount++
        beaconFailure?.let { throw it }
        beacons += network
        currentBeacon = network
        if (pairing != null) lastPairingNetwork = pairing
    }
}
