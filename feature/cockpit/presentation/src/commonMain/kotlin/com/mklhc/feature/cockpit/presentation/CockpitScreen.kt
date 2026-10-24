package com.mklhc.feature.cockpit.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.components.AppIconButton
import com.mklhc.core.design_system.components.StatusPill
import com.mklhc.core.design_system.components.statusTone
import com.mklhc.core.design_system.drive.BrakeLightButton
import com.mklhc.core.design_system.drive.SteeringSlider
import com.mklhc.core.design_system.drive.ThrottlePedal
import com.mklhc.core.design_system.hud.ArtificialHorizon
import com.mklhc.core.design_system.hud.BatteryGauge
import com.mklhc.core.design_system.hud.GMeter
import com.mklhc.core.design_system.hud.SpeedOrThrottleReadout
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.asString
import com.mklhc.core.presentation.toUiText
import com.mklhc.core.video.VideoSurface
import com.mklhc.feature.cockpit.presentation.generated.resources.Res
import com.mklhc.feature.cockpit.presentation.generated.resources.cockpit_back
import com.mklhc.feature.cockpit.presentation.generated.resources.cockpit_throttle_label
import com.mklhc.feature.cockpit.presentation.generated.resources.cockpit_throttle_unit
import com.mklhc.feature.cockpit.presentation.generated.resources.cockpit_waiting_hint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/**
 * The cockpit screen with its ViewModel: the entry point the app's navigation graph calls.
 *
 * Business logic: holds the [CockpitViewModel] (from Koin), collects its state with the lifecycle, and wires the
 * screen's actions to it. Two safety rules live here because only the screen knows when it stops being in front of
 * the driver: the kart is returned to neutral when the host activity pauses (a system dialog, the notification
 * shade, another app) and when this composable leaves the composition, on top of the ViewModel doing the same when
 * it is cleared. The touch controls already release themselves when their finger lifts or they are removed; this
 * covers everything else. There are no one-time effects to observe: a lost link is an overlay in the state, and
 * the back button is the plain [onNavigateBack] callback.
 *
 * The video frames go from the ViewModel's flow straight to the screen's video surface; they never pass through
 * the state collected here.
 *
 * @param onNavigateBack called when the driver taps the back button; the host pops the cockpit.
 * @param viewModel the state holder; injected by Koin.
 */
@Composable
fun CockpitRoot(
    onNavigateBack: () -> Unit,
    viewModel: CockpitViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onControlRelease() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.onControlRelease() }
    }

    CockpitScreen(
        state = state,
        videoAccessUnits = viewModel.videoAccessUnits,
        onSteering = viewModel::onSteering,
        onThrottle = viewModel::onThrottle,
        onSteeringRelease = viewModel::onSteeringRelease,
        onThrottleRelease = viewModel::onThrottleRelease,
        onBrake = viewModel::onBrake,
        onBack = onNavigateBack,
    )
}

/**
 * The cockpit: the live video, the HUD and the drive controls, full bleed, for a landscape screen.
 *
 * Business logic: none; a pure function of [state] and the video flow that reports interactions through lambdas,
 * so it can be previewed and tested without a ViewModel. From back to front:
 * - The video surface fills the screen.
 * - The HUD floats over it: the artificial horizon and the G meter at the top centre, and the throttle readout
 *   above the brake button at the bottom centre. The readout and the battery sit on a translucent panel so they
 *   stay legible over any picture.
 * - The drive controls sit in the lower corners: the steering slider at the left, the throttle pedal at the
 *   right and the brake-light button between them, so each is under a thumb.
 * - While the kart is not driving, a dimmed overlay with the link status pill covers the cockpit, and the drive
 *   controls are disabled (the overlay is a banner, not a navigation: the session recovers by itself and the
 *   cockpit is waiting when it does).
 * - The back button and the battery gauge stay on top of everything, so the driver can always leave.
 *
 * The controls are stateless: the values they show come from [state], and the lambdas report what the finger does.
 * [onSteeringRelease] and [onThrottleRelease] are separate so letting go with one thumb does not touch the other
 * axis; the brake button reports its own release through [onBrake] with false. The keyboard drives through the same
 * lambdas ([keyboardDrive]: W/A/S/D or the arrows, the space bar for the brake), which is how the desktop drives both
 * axes at once with one mouse.
 *
 * @param state what to render.
 * @param videoAccessUnits the kart's H.264 access units, for the video surface; pass an empty flow in previews.
 * @param onSteering the steering control moved to this value, -1..1.
 * @param onThrottle the throttle pedal moved to this value, -1..1.
 * @param onSteeringRelease the finger left the steering control.
 * @param onThrottleRelease the finger left the throttle pedal.
 * @param onBrake the brake button was pressed (true) or released (false).
 * @param onBack the driver asked to leave the cockpit.
 * @param modifier the modifier to apply.
 */
@Composable
fun CockpitScreen(
    state: CockpitState,
    videoAccessUnits: Flow<ByteArray>,
    onSteering: (Float) -> Unit,
    onThrottle: (Float) -> Unit,
    onSteeringRelease: () -> Unit,
    onThrottleRelease: () -> Unit,
    onBrake: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .keyboardDrive(
                enabled = state.isDriving,
                onSteering = onSteering,
                onThrottle = onThrottle,
                onSteeringRelease = onSteeringRelease,
                onThrottleRelease = onThrottleRelease,
                onBrake = onBrake,
            ),
    ) {
        VideoSurface(accessUnits = videoAccessUnits, modifier = Modifier.fillMaxSize())

        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(AppDimens.SpacingMedium)) {
            HudInstruments(state = state, modifier = Modifier.align(Alignment.TopCenter))
            DriveControls(
                state = state,
                onSteering = onSteering,
                onThrottle = onThrottle,
                onSteeringRelease = onSteeringRelease,
                onThrottleRelease = onThrottleRelease,
                onBrake = onBrake,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (!state.isDriving) {
            ConnectingOverlay(state = state, modifier = Modifier.fillMaxSize())
        }

        TopBar(
            state = state,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(AppDimens.SpacingMedium),
        )
    }
}

/** The artificial horizon and the G meter side by side. */
@Composable
private fun HudInstruments(state: CockpitState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtificialHorizon(pitchDegrees = state.pitchDegrees, rollDegrees = state.rollDegrees)
        GMeter(gX = state.gX, gY = state.gY)
    }
}

/** The back button and, once the kart reports it, the battery gauge. */
@Composable
private fun TopBar(state: CockpitState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconButton(onClick = onBack, contentDescription = stringResource(Res.string.cockpit_back)) {
            BackArrowIcon()
        }
        if (state.hasBattery) {
            HudPanel {
                BatteryGauge(bars = state.batteryBars, charging = state.batteryCharging)
            }
        }
    }
}

/** The steering slider, the throttle readout above the brake button, and the throttle pedal, along the bottom. */
@Composable
private fun DriveControls(
    state: CockpitState,
    onSteering: (Float) -> Unit,
    onThrottle: (Float) -> Unit,
    onSteeringRelease: () -> Unit,
    onThrottleRelease: () -> Unit,
    onBrake: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        SteeringSlider(
            value = state.steeringFraction,
            onValue = onSteering,
            onRelease = onSteeringRelease,
            enabled = state.isDriving,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
        ) {
            HudPanel {
                SpeedOrThrottleReadout(
                    value = (state.throttleFraction * PERCENT).roundToInt().toString(),
                    unit = stringResource(Res.string.cockpit_throttle_unit),
                    label = stringResource(Res.string.cockpit_throttle_label),
                    fraction = state.throttleFraction,
                )
            }
            BrakeLightButton(active = state.brakeLight, onActiveChange = onBrake, enabled = state.isDriving)
        }
        ThrottlePedal(
            value = state.throttleFraction,
            onValue = onThrottle,
            onRelease = onThrottleRelease,
            enabled = state.isDriving,
        )
    }
}

/** The dimmed cover shown while the kart is not driving: where the link stands, and why the controls are off. */
@Composable
private fun ConnectingOverlay(state: CockpitState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
        ) {
            StatusPill(text = state.statusText.asString(), tone = state.linkState.statusTone())
            Text(
                text = stringResource(Res.string.cockpit_waiting_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A translucent rounded panel that keeps HUD text legible over the video. */
@Composable
private fun HudPanel(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
    ) {
        Box(modifier = Modifier.padding(AppDimens.SpacingSmall)) {
            content()
        }
    }
}

/** A left-pointing chevron, drawn in the content colour; the design system carries no icon set. */
@Composable
private fun BackArrowIcon() {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(20.dp)) {
        val chevron = Path().apply {
            moveTo(size.width * 0.65f, size.height * 0.15f)
            lineTo(size.width * 0.25f, size.height * 0.50f)
            lineTo(size.width * 0.65f, size.height * 0.85f)
        }
        drawPath(
            path = chevron,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private const val PERCENT = 100
private const val SCRIM_ALPHA = 0.55f
private const val PANEL_ALPHA = 0.6f

@Preview(widthDp = 800, heightDp = 400)
@Composable
private fun CockpitScreenDrivingPreview() {
    PreviewSurface {
        CockpitScreen(
            state = CockpitState(
                linkState = LinkState.Driving,
                statusText = LinkState.Driving.toUiText(),
                pitchDegrees = 8f,
                rollDegrees = -12f,
                gX = 0.4f,
                gY = 0.3f,
                hasBattery = true,
                batteryBars = 3,
                steeringFraction = -0.35f,
                throttleFraction = 0.6f,
            ),
            videoAccessUnits = emptyFlow(),
            onSteering = {},
            onThrottle = {},
            onSteeringRelease = {},
            onThrottleRelease = {},
            onBrake = {},
            onBack = {},
        )
    }
}

@Preview(widthDp = 800, heightDp = 400)
@Composable
private fun CockpitScreenConnectingPreview() {
    val beaconing = LinkState.Beaconing(KartNetworkKind.GAME)
    PreviewSurface {
        CockpitScreen(
            state = CockpitState(linkState = beaconing, statusText = beaconing.toUiText()),
            videoAccessUnits = emptyFlow(),
            onSteering = {},
            onThrottle = {},
            onSteeringRelease = {},
            onThrottleRelease = {},
            onBrake = {},
            onBack = {},
        )
    }
}
