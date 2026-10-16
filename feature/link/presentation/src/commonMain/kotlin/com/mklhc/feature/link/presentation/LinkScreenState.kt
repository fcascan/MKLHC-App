package com.mklhc.feature.link.presentation

import androidx.compose.runtime.Immutable
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.UiText
import com.mklhc.core.presentation.toUiText

/**
 * Everything the link screen renders.
 *
 * Business logic: a single immutable snapshot, replaced as a whole by [LinkViewModel]. The screen only reflects
 * what the kart session publishes: [linkState] and its sentence [linkStatusText], the [kartInfo] once the kart
 * is identified, the [battery] once the kart reports it and the [captureEnabled] switch. The button flags are
 * derived from [linkState] so they can never disagree with it:
 * - [isAdapterReady]: the radio adapter is known to be present and usable (not missing, not awaiting USB access).
 * - [canStart]: no session flow is running, so Start is meaningful; that is when the link is idle at
 *   [LinkState.NoAdapter] or has [LinkState.Failed]. While the flow climbs the ladder, drives or recovers on
 *   its own, a second Start would only interrupt it.
 * - [canOpenCockpit]: the kart is identified and accepts driving ([LinkState.KartReady] or [LinkState.Driving]).
 *
 * There are no one-time effects here: Pair and Cockpit are plain navigation callbacks of the Root.
 *
 * @property linkState where the link currently stands.
 * @property linkStatusText the sentence that narrates [linkState].
 * @property kartInfo what is known about the identified kart, or null before it is identified.
 * @property battery the kart battery, or null before the first report.
 * @property captureEnabled whether packet capture is switched on.
 * @property errorMessage why the last Start failed, or null; cleared by the next Start.
 */
@Immutable
data class LinkScreenState(
    val linkState: LinkState = LinkState.NoAdapter,
    val linkStatusText: UiText = LinkState.NoAdapter.toUiText(),
    val kartInfo: KartInfoUi? = null,
    val battery: BatteryUi? = null,
    val captureEnabled: Boolean = false,
    val errorMessage: UiText? = null,
) {
    /** True when the radio adapter is not known to be missing or waiting for USB permission. */
    val isAdapterReady: Boolean
        get() = linkState != LinkState.NoAdapter && linkState != LinkState.AwaitingUsbPermission

    /** True when Start is meaningful: the link is idle at no adapter or has failed. */
    val canStart: Boolean
        get() = linkState == LinkState.NoAdapter || linkState is LinkState.Failed

    /** True when the kart accepts driving, so the cockpit can be opened. */
    val canOpenCockpit: Boolean
        get() = linkState == LinkState.KartReady || linkState == LinkState.Driving
}
