package com.mklhc.feature.link.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.presentation.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state holder of the link screen.
 *
 * Business logic: the ViewModel is a thin mirror of the kart session plus the screen's two actions. It follows
 * [KartSession.linkState], [KartSession.kartInfo] and [KartSession.battery] into one [LinkScreenState] (mapping
 * the link to its sentence and the domain models to UI models) and starts the session flow on request. Starting
 * does not move the link by itself: [KartSession.start] launches the orchestration and returns, and the screen
 * simply reflects the link states the session then publishes. The initial state is read from the session's
 * current values, so a screen opened while the link is already up does not flash the idle state.
 *
 * Interaction style: public action functions ([onStartClick], [onCaptureToggle]); there are no one-time
 * effects, the Pair and Cockpit buttons are plain navigation callbacks of the Root. The session is injected,
 * and its flows are collected in [viewModelScope], so everything is cancelled when the screen goes away.
 *
 * @param session the kart session this screen reflects and starts.
 */
class LinkViewModel(private val session: KartSession) : ViewModel() {

    private val log = Logger.withTag(TAG)

    private val _state = MutableStateFlow(initialState())
    val state = _state.asStateFlow()

    init {
        observeLinkState()
        observeKartInfo()
        observeBattery()
    }

    /**
     * Starts the normal session flow: ensure the adapter, bring the radio up and beacon the game network.
     * Progress is published by the session as link states; a failure to even launch is logged and shown as
     * [LinkScreenState.errorMessage], which the next call clears.
     */
    fun onStartClick() {
        _state.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            try {
                session.start()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "onStartClick(): starting the session failed -> message=${failure.message}" }
                _state.update { it.copy(errorMessage = failure.toUiText()) }
            }
        }
    }

    /** Switches packet capture on or off in the session and in the screen state. */
    fun onCaptureToggle(enabled: Boolean) {
        session.setCaptureEnabled(enabled)
        _state.update { it.copy(captureEnabled = enabled) }
    }

    private fun initialState(): LinkScreenState {
        val linkState = session.linkState.value
        return LinkScreenState(
            linkState = linkState,
            linkStatusText = linkState.toUiText(),
            kartInfo = session.kartInfo.value?.toKartInfoUi(),
            battery = session.battery.value?.toBatteryUi(),
        )
    }

    private fun observeLinkState() {
        viewModelScope.launch {
            session.linkState.collect { linkState ->
                _state.update { it.copy(linkState = linkState, linkStatusText = linkState.toUiText()) }
            }
        }
    }

    private fun observeKartInfo() {
        viewModelScope.launch {
            session.kartInfo.collect { info ->
                _state.update { it.copy(kartInfo = info?.toKartInfoUi()) }
            }
        }
    }

    private fun observeBattery() {
        viewModelScope.launch {
            session.battery.collect { status ->
                _state.update { it.copy(battery = status?.toBatteryUi()) }
            }
        }
    }

    private companion object {
        const val TAG = "LinkViewModel"
    }
}
