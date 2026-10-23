package com.mklhc.feature.link.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.mklhc.core.data.radio.UsbAdapterRepository
import com.mklhc.core.data.session.KartSession
import com.mklhc.core.domain.AdapterResolution
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.UsbAdapterCatalog
import com.mklhc.core.domain.UsbId
import com.mklhc.core.presentation.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state holder of the link screen.
 *
 * Business logic: the ViewModel is a thin mirror of the kart session and of the USB adapter repository plus the
 * screen's actions. It follows [KartSession.linkState], [KartSession.kartInfo] and [KartSession.battery] into one
 * [LinkScreenState] (mapping the link to its sentence and the domain models to UI models) and starts the session
 * flow on request. Starting does not move the link by itself: [KartSession.start] launches the orchestration and
 * returns, and the screen simply reflects the link states the session then publishes. The initial state is read
 * from the session's and the repository's current values, so a screen opened while the link is already up does not
 * flash the idle state.
 *
 * The radio adapter: [UsbAdapterRepository.overview] becomes [LinkScreenState.adapter] (the detected devices, the
 * mode, the status line). The user's choice is saved through the repository with [onAdapterAutomatic],
 * [onAdapterManual] and [onAdapterPick], and only while the radio is off ([LinkScreenState.canChangeAdapter]); while
 * it is up the section is read-only, so these actions do nothing. Manual starts from the adapter in use, else the one
 * the app would use now, else the first known adapter of the list, and does nothing when the list holds no known
 * adapter or the mode is already manual; only adapters the app knows can be picked. A failure to save is logged and
 * shown as [LinkScreenState.errorMessage], like a failed Start.
 *
 * Interaction style: public action functions ([onStartClick], [onCaptureToggle], [onAdapterAutomatic],
 * [onAdapterManual], [onAdapterPick]); there are no one-time effects, the Pair and Cockpit buttons are plain
 * navigation callbacks of the Root. The session and the repository are injected, and their flows are collected in
 * [viewModelScope], so everything is cancelled when the screen goes away.
 *
 * @param session the kart session this screen reflects and starts.
 * @param adapters the USB adapters the platform sees and the user's choice among them.
 */
class LinkViewModel(
    private val session: KartSession,
    private val adapters: UsbAdapterRepository,
) : ViewModel() {

    private val log = Logger.withTag(TAG)

    private val _state = MutableStateFlow(initialState())
    val state = _state.asStateFlow()

    init {
        observeLinkState()
        observeKartInfo()
        observeBattery()
        observeAdapters()
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

    /** Lets the radio pick the first compatible adapter it finds. Does nothing while the radio is up. */
    fun onAdapterAutomatic() {
        if (!adaptersCanChange("onAdapterAutomatic")) return
        log.i { "onAdapterAutomatic(): selecting automatic mode -> current=${adapters.overview.value.selection}" }
        saveAdapterSelection("onAdapterAutomatic", AdapterSelection.Automatic)
    }

    /**
     * Switches to the manual mode, starting from an adapter that makes sense: the one in use, else the one the app
     * would use now, else the first known adapter of the list. Does nothing while the radio is up, when the mode is
     * already manual, or when the list holds no known adapter.
     */
    fun onAdapterManual() {
        if (!adaptersCanChange("onAdapterManual")) return
        val overview = adapters.overview.value
        if (overview.selection is AdapterSelection.Manual) return
        val id = overview.inUse?.takeIf { it.isKnownAdapter() }
            ?: (overview.resolution as? AdapterResolution.Use)?.device?.id?.takeIf { it.isKnownAdapter() }
            ?: overview.firstCatalogDeviceId()
        if (id == null) {
            log.i { "onAdapterManual(): no known adapter in the list -> devices=${overview.devices?.size}" }
            return
        }
        log.i { "onAdapterManual(): selecting manual mode -> id=$id" }
        saveAdapterSelection("onAdapterManual", AdapterSelection.Manual(id))
    }

    /** Picks [id] as the adapter to use (manual mode). Only adapters the app knows can be picked. */
    fun onAdapterPick(id: UsbId) {
        if (!adaptersCanChange("onAdapterPick")) return
        if (!id.isKnownAdapter()) {
            log.w { "onAdapterPick(): the device is not a known adapter -> id=$id" }
            return
        }
        log.i { "onAdapterPick(): picking the adapter -> id=$id" }
        saveAdapterSelection("onAdapterPick", AdapterSelection.Manual(id))
    }

    private fun adaptersCanChange(action: String): Boolean {
        val allowed = _state.value.canChangeAdapter
        if (!allowed) log.i { "$action(): the radio is up, the adapter is read-only -> link=${_state.value.linkState}" }
        return allowed
    }

    private fun saveAdapterSelection(action: String, selection: AdapterSelection) {
        _state.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            try {
                adapters.setSelection(selection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.e(failure) { "$action(): saving the adapter selection failed -> selection=$selection message=${failure.message}" }
                _state.update { it.copy(errorMessage = failure.toUiText()) }
            }
        }
    }

    private fun UsbId.isKnownAdapter(): Boolean = UsbAdapterCatalog.find(this) != null

    private fun initialState(): LinkScreenState {
        val linkState = session.linkState.value
        return LinkScreenState(
            linkState = linkState,
            linkStatusText = linkState.toUiText(),
            kartInfo = session.kartInfo.value?.toKartInfoUi(),
            battery = session.battery.value?.toBatteryUi(),
            adapter = adapters.overview.value.toAdapterSectionUi(),
        )
    }

    private fun observeAdapters() {
        viewModelScope.launch {
            adapters.overview.collect { overview ->
                _state.update { it.copy(adapter = overview.toAdapterSectionUi()) }
            }
        }
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
