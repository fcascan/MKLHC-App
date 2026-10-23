package com.mklhc.feature.link.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mklhc.core.data.radio.AdapterOverview
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.components.AppButton
import com.mklhc.core.design_system.components.AppButtonStyle
import com.mklhc.core.design_system.components.LabeledValue
import com.mklhc.core.design_system.components.StatusPill
import com.mklhc.core.design_system.components.StatusTone
import com.mklhc.core.design_system.components.statusTone
import com.mklhc.core.design_system.hud.BatteryGauge
import com.mklhc.core.domain.AdapterSelection
import com.mklhc.core.domain.DetectedUsbDevice
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.domain.UsbDeviceAccess
import com.mklhc.core.domain.UsbId
import com.mklhc.core.presentation.asString
import com.mklhc.core.presentation.toUiText
import com.mklhc.feature.link.presentation.generated.resources.Res
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_locked_note
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_mode_automatic
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_mode_automatic_hint
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_mode_manual
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_mode_manual_hint
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_pill_cannot_open
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_pill_compatible
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_pill_in_use
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_pill_needs_driver
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_pill_no_permission
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_pill_not_recommended
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_pill_will_be_used
import com.mklhc.feature.link.presentation.generated.resources.link_adapter_title
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
 * collection stops while the screen is in the background, and wires the screen's actions. Start, the capture
 * toggle and the radio adapter choice go to the ViewModel; Pair and Cockpit are pure navigation and go straight to
 * the callbacks, because they carry no state of their own. Nothing here draws; that is [LinkScreen].
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
        onAdapterAutomatic = viewModel::onAdapterAutomatic,
        onAdapterManual = viewModel::onAdapterManual,
        onAdapterPick = viewModel::onAdapterPick,
    )
}

/**
 * The link screen: where the link stands, what is known about the kart, and what the user can do about it.
 *
 * Business logic: none; a pure function of [state] that reports interactions through lambdas, so it can be
 * previewed and tested without a ViewModel. From top to bottom: the status pill (the link sentence in a tone
 * that follows the link), an error line when starting failed, the kart card (serial, variant, firmware and the
 * battery gauge, or a hint while no kart is identified), the Start / Pair / Cockpit buttons (Start and Cockpit
 * follow [LinkScreenState.canStart] and [LinkScreenState.canOpenCockpit]; Pair is always available), the
 * "Radio adapter" card (the Automatic / Manual rows, the detected USB devices with their pills, a status line; read
 * only with a note while [LinkScreenState.canChangeAdapter] is false) and a small diagnostics area with the capture
 * switch. The adapter card sits under the buttons so Start never moves however long the device list is. The content
 * is capped in width, centred and scrolls, so it reads well on a wide desktop window as well as on a phone.
 *
 * @param state what to render.
 * @param onStartClick the user asked to start the session flow.
 * @param onCaptureToggle the user switched packet capture to this value.
 * @param onPairClick the user asked to pair a kart.
 * @param onCockpitClick the user asked to open the cockpit.
 * @param onAdapterAutomatic the user chose the automatic adapter mode.
 * @param onAdapterManual the user chose the manual adapter mode.
 * @param onAdapterPick the user picked this device in manual mode.
 * @param modifier the modifier to apply.
 */
@Composable
fun LinkScreen(
    state: LinkScreenState,
    onStartClick: () -> Unit,
    onCaptureToggle: (Boolean) -> Unit,
    onPairClick: () -> Unit,
    onCockpitClick: () -> Unit,
    onAdapterAutomatic: () -> Unit,
    onAdapterManual: () -> Unit,
    onAdapterPick: (UsbId) -> Unit,
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
            AdapterSection(
                adapter = state.adapter,
                enabled = state.canChangeAdapter,
                onAutomatic = onAdapterAutomatic,
                onManual = onAdapterManual,
                onPick = onAdapterPick,
            )
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

/**
 * The "Radio adapter" card: the two mode rows, the detected USB devices, the status line and, while the radio is
 * up, a note that the choice is read-only.
 */
@Composable
private fun AdapterSection(
    adapter: AdapterSectionUi,
    enabled: Boolean,
    onAutomatic: () -> Unit,
    onManual: () -> Unit,
    onPick: (UsbId) -> Unit,
) {
    SectionCard(title = stringResource(Res.string.link_adapter_title)) {
        // Each set of radio rows is one group, so a screen reader announces "1 of 2" and not a loose button.
        Column(modifier = Modifier.selectableGroup()) {
            AdapterModeRow(
                selected = adapter.mode == AdapterMode.AUTOMATIC,
                enabled = enabled,
                title = stringResource(Res.string.link_adapter_mode_automatic),
                hint = stringResource(Res.string.link_adapter_mode_automatic_hint),
                onClick = onAutomatic,
            )
            AdapterModeRow(
                selected = adapter.mode == AdapterMode.MANUAL,
                enabled = enabled && adapter.manualAvailable,
                title = stringResource(Res.string.link_adapter_mode_manual),
                hint = stringResource(Res.string.link_adapter_mode_manual_hint),
                onClick = onManual,
            )
        }
        if (adapter.devices.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.selectableGroup()) {
                adapter.devices.forEach { device ->
                    // Keyed by the device (two identical adapters differ by bus and address), so a row keeps its
                    // identity when another device is plugged in or removed.
                    key(device.id, device.bus, device.address) {
                        AdapterDeviceRow(
                            device = device,
                            reserveRadioSpace = adapter.mode == AdapterMode.MANUAL,
                            enabled = enabled,
                            onPick = onPick,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text(
            text = adapter.status.asString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!enabled) {
            Text(
                text = stringResource(Res.string.link_adapter_locked_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A radio-button row of the mode choice: the whole row is the control, so its label is part of it. */
@Composable
private fun AdapterModeRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    hint: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppDimens.MinTouchTarget)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One detected USB device: its name, `vvvv:pppp` and chip, and its pills. A selectable row (manual mode, a device the
 * app knows) is a radio button; any other row is plain information, merged into one node for screen readers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdapterDeviceRow(
    device: AdapterDeviceUi,
    reserveRadioSpace: Boolean,
    enabled: Boolean,
    onPick: (UsbId) -> Unit,
) {
    val base = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = AppDimens.MinTouchTarget)
        .alpha(if (enabled || !device.selectable) 1f else DisabledAlpha)
    val rowModifier = if (device.selectable) {
        base.selectable(
            selected = device.selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = { onPick(device.id) },
        )
    } else {
        base.semantics(mergeDescendants = true) {}
    }
    Row(
        modifier = rowModifier.padding(vertical = AppDimens.SpacingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
    ) {
        if (device.selectable) {
            RadioButton(selected = device.selected, onClick = null, enabled = enabled)
        } else if (reserveRadioSpace) {
            Spacer(Modifier.width(RadioButtonSpace))
        }
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            Text(
                text = device.name.asString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
                Text(
                    text = device.idText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                device.chip?.let { chip ->
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (device.pills.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
                    verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
                ) {
                    device.pills.forEach { pill -> StatusPill(text = pill.text(), tone = pill.tone()) }
                }
            }
        }
    }
}

/** The wording of a device pill. */
@Composable
private fun AdapterPill.text(): String = stringResource(
    when (this) {
        AdapterPill.COMPATIBLE -> Res.string.link_adapter_pill_compatible
        AdapterPill.NOT_RECOMMENDED -> Res.string.link_adapter_pill_not_recommended
        AdapterPill.IN_USE -> Res.string.link_adapter_pill_in_use
        AdapterPill.NEEDS_DRIVER -> Res.string.link_adapter_pill_needs_driver
        AdapterPill.NO_PERMISSION -> Res.string.link_adapter_pill_no_permission
        AdapterPill.CANNOT_BE_OPENED -> Res.string.link_adapter_pill_cannot_open
        AdapterPill.WILL_BE_USED -> Res.string.link_adapter_pill_will_be_used
    },
)

/** The colour of a device pill: green for good news, amber for a problem, cyan for plain activity. */
private fun AdapterPill.tone(): StatusTone = when (this) {
    AdapterPill.COMPATIBLE, AdapterPill.WILL_BE_USED -> StatusTone.Success
    AdapterPill.NOT_RECOMMENDED, AdapterPill.NEEDS_DRIVER, AdapterPill.NO_PERMISSION,
    AdapterPill.CANNOT_BE_OPENED -> StatusTone.Warning
    AdapterPill.IN_USE -> StatusTone.Info
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

/** The alpha of a disabled control, as the Material components use it. */
private const val DisabledAlpha = 0.38f

/** The room a radio button takes, kept free on rows without one so the names line up. */
private val RadioButtonSpace = 24.dp

private val previewKartInfo = KartInfoUi(
    serial = "SN-0012345",
    characterLabel = "Kart 1",
    firmware = "sys 1.4 / boot 0.9",
)

private val previewT3u = UsbId(0x2357, 0x012D)
private val previewNotRecommended = UsbId(0x0BDA, 0xC811)
private val previewUnknown = UsbId(0x1234, 0x5678)

private val previewDevices = listOf(
    DetectedUsbDevice(previewT3u, bus = 1, address = 4, access = UsbDeviceAccess.OPENABLE),
    DetectedUsbDevice(previewNotRecommended, bus = 1, address = 5, access = UsbDeviceAccess.DRIVER_UNSUPPORTED),
    DetectedUsbDevice(previewUnknown, bus = 1, address = 6, access = UsbDeviceAccess.OPENABLE),
)

/** A screen state whose adapter section comes from a repository overview, as the ViewModel would build it. */
private fun previewAdapterState(
    selection: AdapterSelection = AdapterSelection.Automatic,
    inUse: UsbId? = null,
    linkState: LinkState = LinkState.NoAdapter,
) = LinkScreenState(
    linkState = linkState,
    linkStatusText = linkState.toUiText(),
    adapter = AdapterOverview.of(previewDevices, selection, inUse).toAdapterSectionUi(),
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
            onAdapterAutomatic = {},
            onAdapterManual = {},
            onAdapterPick = {},
        )
    }
}

@Preview
@Composable
private fun LinkScreenAdapterAutomaticPreview() {
    PreviewSurface {
        LinkScreen(
            state = previewAdapterState(),
            onStartClick = {},
            onCaptureToggle = {},
            onPairClick = {},
            onCockpitClick = {},
            onAdapterAutomatic = {},
            onAdapterManual = {},
            onAdapterPick = {},
        )
    }
}

@Preview
@Composable
private fun LinkScreenAdapterManualPreview() {
    PreviewSurface {
        LinkScreen(
            state = previewAdapterState(selection = AdapterSelection.Manual(previewNotRecommended)),
            onStartClick = {},
            onCaptureToggle = {},
            onPairClick = {},
            onCockpitClick = {},
            onAdapterAutomatic = {},
            onAdapterManual = {},
            onAdapterPick = {},
        )
    }
}

@Preview
@Composable
private fun LinkScreenAdapterReadOnlyPreview() {
    PreviewSurface {
        LinkScreen(
            state = previewAdapterState(inUse = previewT3u, linkState = LinkState.Driving),
            onStartClick = {},
            onCaptureToggle = {},
            onPairClick = {},
            onCockpitClick = {},
            onAdapterAutomatic = {},
            onAdapterManual = {},
            onAdapterPick = {},
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
            onAdapterAutomatic = {},
            onAdapterManual = {},
            onAdapterPick = {},
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
            onAdapterAutomatic = {},
            onAdapterManual = {},
            onAdapterPick = {},
        )
    }
}
