package com.mklhc.feature.cockpit.presentation

import androidx.compose.runtime.Immutable
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.UiText
import com.mklhc.core.presentation.toUiText

/**
 * Everything the cockpit screen renders, except the video.
 *
 * Business logic: a single immutable snapshot, replaced as a whole by [CockpitViewModel]. It has three parts:
 * - The link: [linkState] and its sentence [statusText]. [isDriving] is derived from [linkState] so it can never
 *   disagree with it; the controls work only while it is true, and the screen puts a "connecting" overlay over
 *   the cockpit while it is false. There are no one-time effects: losing the link is a banner, not a navigation,
 *   because the session recovers by itself and the driver should find the cockpit waiting when it does.
 * - The HUD, already in display units: the attitude in degrees ([pitchDegrees] positive nose up, [rollDegrees]
 *   positive banking right), the G meter in g ([gX] lateral positive right, [gY] longitudinal positive forward),
 *   and the battery ([hasBattery] says whether the kart has reported it yet).
 * - The controls, as the driver is holding them: [steeringFraction] and [throttleFraction] in -1..1 and the
 *   [brakeLight] flag. They are state, not remembered inside the screen, so the ViewModel is the single source
 *   of truth for what the kart is being told, and a link loss can centre them. [throttleFraction] also feeds the
 *   throttle readout.
 *
 * The raw video frames are deliberately not part of this state: updating state per frame would recompose the
 * screen at the video rate. The Root hands the ViewModel's frame flow straight to the video surface.
 *
 * @property linkState where the link currently stands.
 * @property statusText the sentence that narrates [linkState].
 * @property pitchDegrees nose-up-positive pitch in degrees.
 * @property rollDegrees right-bank-positive roll in degrees.
 * @property gX lateral acceleration in g, positive to the right.
 * @property gY longitudinal acceleration in g, positive forward.
 * @property hasBattery whether the kart has reported its battery.
 * @property batteryBars the battery level in bars, 0..4.
 * @property batteryCharging whether the charging cable is connected.
 * @property steeringFraction the steering as held, -1 (full left) .. 1 (full right).
 * @property throttleFraction the throttle as held, -1 (full reverse) .. 1 (full forward).
 * @property brakeLight whether the brake-light button is held.
 */
@Immutable
data class CockpitState(
    val linkState: LinkState = LinkState.NoAdapter,
    val statusText: UiText = LinkState.NoAdapter.toUiText(),
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val gX: Float = 0f,
    val gY: Float = 0f,
    val hasBattery: Boolean = false,
    val batteryBars: Int = 0,
    val batteryCharging: Boolean = false,
    val steeringFraction: Float = 0f,
    val throttleFraction: Float = 0f,
    val brakeLight: Boolean = false,
) {
    /** True while the drive command stream is live; the controls are enabled only then. */
    val isDriving: Boolean
        get() = linkState == LinkState.Driving
}
