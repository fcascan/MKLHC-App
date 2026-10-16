package com.mklhc.feature.pairing.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state holder of the pairing screen.
 *
 * Business logic: opening the screen starts the pairing flow ([KartSession.startPairing]) once, when the
 * ViewModel is created (not on every composition, so a rotation or a recomposition never restarts it). From then
 * on the ViewModel mirrors the session: the QR payload the session publishes while the pairing network is up
 * ([KartSession.pairingQr]) and the link states ([KartSession.linkState]), which it turns into the status
 * sentence and the in-progress flag.
 *
 * When is the pairing complete? After the kart scans the code, the session hands it the game network's
 * credentials, the kart resets onto the game network and the session climbs to [LinkState.KartReady] and
 * [LinkState.Driving]. The ViewModel therefore completes the pairing (sets [PairingNavigation.Paired], once) when
 * the link reaches one of those two states after the pairing network was beaconed in this attempt. The "after the
 * pairing beacon" condition is what makes it safe to open the screen while a game session is already up: the
 * session only moves off that old ready state once the pairing flow actually starts, and a stale ready state must
 * not be mistaken for the new pairing finishing.
 *
 * Cancelling ([onCancel]) cancels the session's pairing and asks the host to leave ([PairingNavigation.Cancelled]).
 * If the screen is left in any other way while pairing is still open (the system back gesture, for example), the
 * pairing is cancelled when the ViewModel is cleared, so the pairing network is not left beaconing with nobody
 * showing its code. A completed or cancelled pairing is not cancelled again, since cancelling would also tear down
 * the game session the pairing just produced.
 *
 * Interaction style: public action functions ([onStartPairing], [onCancel], [onNavigationHandled]); the one-time
 * navigation effect is a consumable field of the state. The QR payload is secret key material: it is never logged.
 *
 * @param session the kart session this screen pairs through.
 */
class PairingViewModel(private val session: KartSession) : ViewModel() {

    private val log = Logger.withTag(TAG)

    private val _state = MutableStateFlow(initialState())
    val state = _state.asStateFlow()

    /** Whether this attempt has seen the pairing network being beaconed; completion is only valid after that. */
    private var pairingBeaconSeen = false

    /** True once this attempt has completed or been cancelled; the session is then no longer ours to cancel. */
    private var finished = false

    init {
        observePairingQr()
        observeLinkState()
        onStartPairing()
    }

    /**
     * Starts (or restarts, after a failure) the pairing flow. Each call is a new attempt: the pairing-beacon
     * condition for completion starts over. A failure to even launch it is logged and shown as
     * [PairingState.errorMessage].
     */
    fun onStartPairing() {
        pairingBeaconSeen = false
        finished = false
        _state.update { it.copy(isPairing = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                session.startPairing()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "onStartPairing(): starting the pairing failed -> message=${failure.message}" }
                _state.update { it.copy(isPairing = false, errorMessage = failure.toUiText()) }
            }
        }
    }

    /** Cancels the pairing in the session and asks the host to leave the screen. */
    fun onCancel() {
        log.i { "onCancel(): cancelling the pairing" }
        finished = true
        session.cancelPairing()
        _state.update { it.copy(isPairing = false, qrPayload = null, navigation = PairingNavigation.Cancelled) }
    }

    /** The host has performed the pending navigation; clear it so it does not fire again. */
    fun onNavigationHandled() {
        _state.update { it.copy(navigation = null) }
    }

    override fun onCleared() {
        if (!finished) {
            log.i { "onCleared(): the screen was left while pairing was open, cancelling the pairing" }
            session.cancelPairing()
        }
    }

    private fun initialState(): PairingState {
        val linkState = session.linkState.value
        return PairingState(
            qrPayload = session.pairingQr.value,
            linkState = linkState,
            statusText = linkState.toUiText(),
            isPairing = true,
        )
    }

    private fun observePairingQr() {
        viewModelScope.launch {
            session.pairingQr.collect { payload ->
                _state.update { it.copy(qrPayload = payload) }
            }
        }
    }

    private fun observeLinkState() {
        viewModelScope.launch {
            session.linkState.collect { linkState -> onLinkStateChanged(linkState) }
        }
    }

    private fun onLinkStateChanged(linkState: LinkState) {
        if (linkState == PAIRING_BEACON) pairingBeaconSeen = true
        val completed = !finished && pairingBeaconSeen && (linkState == LinkState.KartReady || linkState == LinkState.Driving)
        if (completed) {
            log.i { "onLinkStateChanged(): pairing completed -> linkState=$linkState" }
            finished = true
        }
        _state.update {
            it.copy(
                linkState = linkState,
                statusText = linkState.toUiText(),
                isPairing = !finished && linkState !is LinkState.Failed,
                navigation = if (completed) PairingNavigation.Paired else it.navigation,
            )
        }
    }

    private companion object {
        const val TAG = "PairingViewModel"
        val PAIRING_BEACON = LinkState.Beaconing(KartNetworkKind.PAIRING)
    }
}
