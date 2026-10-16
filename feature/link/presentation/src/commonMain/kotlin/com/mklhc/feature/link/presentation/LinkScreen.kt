package com.mklhc.feature.link.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.components.AppButton
import com.mklhc.core.design_system.components.AppButtonStyle
import com.mklhc.core.design_system.components.LabeledValue
import com.mklhc.core.design_system.components.StatusPill
import com.mklhc.core.design_system.components.statusTone
import com.mklhc.core.design_system.hud.BatteryGauge
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.asString
import com.mklhc.core.presentation.toUiText
import com.mklhc.feature.link.presentation.generated.resources.Res
import com.mklhc.feature.link.presentation.generated.resources.link_capture_label
import com.mklhc.feature.link.presentation.generated.resources.link_cockpit
import com.mklhc.feature.link.presentation.generated.resources.link_diagnostics_title
import com.mklhc.feature.link.presentation.generated.resources.link_kart_title
import com.mklhc.feature.link.presentation.generated.resources.link_kart_unknown
import com.mklhc.feature.link.presentation.generated.resources.link_label_battery
import com.mklhc.feature.link.presentation.generated.resources.link_label_firmware
import com.mklhc.feature.link.presentation.generated.resources.link_label_serial
import com.mklhc.feature.link.presentation.generated.resources.link_label_variant
import com.mklhc.feature.link.presentation.generated.resources.link_pair
import com.mklhc.feature.link.presentation.generated.resources.link_start
import com.mklhc.feature.link.presentation.generated.resources.link_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

/**
 * The link screen with its ViewModel: the entry point the app's navigation graph calls.
 *
 * Business logic: holds the [LinkViewModel] (from Koin), collects its state with the lifecycle so the
 * collection stops while the screen is in the background, and wires the screen's actions. Start and the capture
 * toggle go to the ViewModel; Pair and Cockpit are pure navigation and go straight to the callbacks, because
 * they carry no state of their own. Nothing here draws; that is [LinkScreen].
 *
 * Navigation contract for the host: [onNavigateToPairing] is called when the user taps "Pair a kart" and
 * [onNavigateToCockpit] when they tap "Open cockpit", which is only enabled while the link is ready or driving.
 * The host decides what the destinations are (push the pairing screen, push the cockpit).
 *
 * @param onNavigateToCockpit opens the cockpit.
 * @param onNavigateToPairing opens the pairing screen.
 * @param viewModel the state holder; injected by Koin.
 */
@Composable
fun LinkRoot(
    onNavigateToCockpit: () -> Unit,
    onNavigateToPairing: () -> Unit,
    viewModel: LinkViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LinkScreen(
        state = state,
        onStartClick = viewModel::onStartClick,
        onCaptureToggle = viewModel::onCaptureToggle,
        onPairClick = onNavigateToPairing,
        onCockpitClick = onNavigateToCockpit,
    )
}

/**
 * The link screen: where the link stands, what is known about the kart, and what the user can do about it.
 *
 * Business logic: none; a pure function of [state] that reports interactions through lambdas, so it can be
 * previewed and tested without a ViewModel. From top to bottom: the status pill (the link sentence in a tone
 * that follows the link), an error line when starting failed, the kart card (serial, variant, firmware and the
 * battery gauge, or a hint while no kart is identified), the Start / Pair / Cockpit buttons (Start and Cockpit
 * follow [LinkScreenState.canStart] and [LinkScreenState.canOpenCockpit]; Pair is always available) and a small
 * diagnostics area with the capture switch. The content is capped in width and centred, so it reads well on a
 * wide desktop window as well as on a phone.
 *
 * @param state what to render.
 * @param onStartClick the user asked to start the session flow.
 * @param onCaptureToggle the user switched packet capture to this value.
 * @param onPairClick the user asked to pair a kart.
 * @param onCockpitClick the user asked to open the cockpit.
 * @param modifier the modifier to apply.
 */
@Composable
fun LinkScreen(
    state: LinkScreenState,
    onStartClick: () -> Unit,
    onCaptureToggle: (Boolean) -> Unit,
    onPairClick: () -> Unit,
    onCockpitClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDimens.SpacingMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium),
        ) {
            Text(
                text = stringResource(Res.string.link_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            LinkStatusSection(state)
            KartCard(kartInfo = state.kartInfo, battery = state.battery)
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
                AppButton(
                    text = stringResource(Res.string.link_start),
                    onClick = onStartClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canStart,
                )
                AppButton(
                    text = stringResource(Res.string.link_pair),
                    onClick = onPairClick,
                    modifier = Modifier.fillMaxWidth(),
                    style = AppButtonStyle.Secondary,
                )
                AppButton(
                    text = stringResource(Res.string.link_cockpit),
                    onClick = onCockpitClick,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canOpenCockpit,
                )
            }
            DiagnosticsSection(captureEnabled = state.captureEnabled, onCaptureToggle = onCaptureToggle)
        }
    }
}

/** The status pill with the link sentence, and the reason the last Start failed when there is one. */
@Composable
private fun LinkStatusSection(state: LinkScreenState) {
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
        StatusPill(text = state.linkStatusText.asString(), tone = state.linkState.statusTone())
        state.errorMessage?.let { error ->
            Text(
                text = error.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** What is known about the kart: its information rows and battery, or a hint while it is not identified. */
@Composable
private fun KartCard(kartInfo: KartInfoUi?, battery: BatteryUi?) {
    SectionCard(title = stringResource(Res.string.link_kart_title)) {
        if (kartInfo == null) {
            Text(
                text = stringResource(Res.string.link_kart_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LabeledValue(label = stringResource(Res.string.link_label_serial), value = kartInfo.serial)
            LabeledValue(label = stringResource(Res.string.link_label_variant), value = kartInfo.characterLabel)
            LabeledValue(label = stringResource(Res.string.link_label_firmware), value = kartInfo.firmware)
        }
        if (battery != null) {
            Column {
                Text(
                    text = stringResource(Res.string.link_label_battery),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BatteryGauge(bars = battery.bars, charging = battery.charging)
            }
        }
    }
}

/** The small diagnostics area: the packet-capture switch. */
@Composable
private fun DiagnosticsSection(captureEnabled: Boolean, onCaptureToggle: (Boolean) -> Unit) {
    SectionCard(title = stringResource(Res.string.link_diagnostics_title)) {
        // The whole row is the switch, so the label is part of the control for touch and for screen readers.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AppDimens.MinTouchTarget)
                .toggleable(value = captureEnabled, role = Role.Switch, onValueChange = onCaptureToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.link_capture_label),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Switch(checked = captureEnabled, onCheckedChange = null)
        }
    }
}

/** A titled, outlined group of content on the surface colour. */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(AppDimens.SpacingMedium),
            verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

private val ContentMaxWidth = 560.dp

private val previewKartInfo = KartInfoUi(
    serial = "SN-0012345",
    characterLabel = "Kart 1",
    firmware = "sys 1.4 / boot 0.9",
)

@Preview
@Composable
private fun LinkScreenNoAdapterPreview() {
    PreviewSurface {
        LinkScreen(
            state = LinkScreenState(),
            onStartClick = {},
            onCaptureToggle = {},
            onPairClick = {},
            onCockpitClick = {},
        )
    }
}

@Preview
@Composable
private fun LinkScreenBeaconingPreview() {
    val beaconing = LinkState.Beaconing(KartNetworkKind.GAME)
    PreviewSurface {
        LinkScreen(
            state = LinkScreenState(linkState = beaconing, linkStatusText = beaconing.toUiText()),
            onStartClick = {},
            onCaptureToggle = {},
            onPairClick = {},
            onCockpitClick = {},
        )
    }
}

@Preview
@Composable
private fun LinkScreenDrivingPreview() {
    PreviewSurface {
        LinkScreen(
            state = LinkScreenState(
                linkState = LinkState.Driving,
                linkStatusText = LinkState.Driving.toUiText(),
                kartInfo = previewKartInfo,
                battery = BatteryUi(bars = 3, charging = false),
                captureEnabled = true,
            ),
            onStartClick = {},
            onCaptureToggle = {},
            onPairClick = {},
            onCockpitClick = {},
        )
    }
}
