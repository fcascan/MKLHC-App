package com.mklhc.feature.pairing.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.design_system.components.AppButton
import com.mklhc.core.design_system.components.AppButtonStyle
import com.mklhc.core.design_system.components.StatusPill
import com.mklhc.core.design_system.components.statusTone
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.kart_protocol.pairing.encodePairingPayload
import com.mklhc.core.presentation.asString
import com.mklhc.core.presentation.toUiText
import com.mklhc.feature.pairing.presentation.generated.resources.Res
import com.mklhc.feature.pairing.presentation.generated.resources.cd_pairing_qr
import com.mklhc.feature.pairing.presentation.generated.resources.pairing_cancel
import com.mklhc.feature.pairing.presentation.generated.resources.pairing_code_error
import com.mklhc.feature.pairing.presentation.generated.resources.pairing_instructions
import com.mklhc.feature.pairing.presentation.generated.resources.pairing_preparing_code
import com.mklhc.feature.pairing.presentation.generated.resources.pairing_retry
import com.mklhc.feature.pairing.presentation.generated.resources.pairing_title
import com.mklhc.feature.pairing.presentation.qr.QrCode
import com.mklhc.feature.pairing.presentation.qr.QrMatrix
import com.mklhc.feature.pairing.presentation.qr.encodeQrMatrix
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

/**
 * The pairing screen with its ViewModel: the entry point the app's navigation graph calls.
 *
 * Business logic: holds the [PairingViewModel] (from Koin), collects its state with the lifecycle, and performs
 * the ViewModel's one-time navigation effect: when the state asks to leave, it calls the matching callback and
 * then tells the ViewModel the effect was handled, so it fires once and not again on recomposition or rotation.
 * Creating the ViewModel starts the pairing flow, so the host must put this screen in its own navigation entry.
 *
 * Navigation contract for the host: [onPaired] is called once when the kart has been paired and is on the game
 * network (typically navigate to the cockpit, or back to the link screen, and pop this screen); [onCancel] is
 * called once after the user cancelled (typically pop back to the link screen). If the user leaves by the system
 * back gesture instead, the ViewModel cancels the pairing itself when it is cleared; the host needs to do nothing.
 *
 * @param onPaired the pairing completed.
 * @param onCancel the user cancelled the pairing.
 * @param viewModel the state holder; injected by Koin.
 */
@Composable
fun PairingRoot(
    onPaired: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PairingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnPaired by rememberUpdatedState(onPaired)
    val currentOnCancel by rememberUpdatedState(onCancel)

    LaunchedEffect(state.navigation) {
        when (state.navigation) {
            PairingNavigation.Paired -> {
                currentOnPaired()
                viewModel.onNavigationHandled()
            }

            PairingNavigation.Cancelled -> {
                currentOnCancel()
                viewModel.onNavigationHandled()
            }

            null -> Unit
        }
    }

    PairingScreen(
        state = state,
        onStartPairing = viewModel::onStartPairing,
        onCancel = viewModel::onCancel,
    )
}

/**
 * The pairing screen: the QR code the kart scans, and how the pairing is going.
 *
 * Business logic: none; a pure function of [state] that reports interactions through lambdas, so it can be
 * previewed and tested without a ViewModel. From top to bottom: the title and the instruction to point the kart's
 * camera at the code; the code itself, big and centred at full contrast (black on white, whatever the theme), or
 * a placeholder of the same size while it is not there yet (so the layout does not jump when it appears); a
 * progress bar and the status pill while pairing is in progress; and the buttons: Try again when the pairing is
 * not running (it failed) and Cancel. The QR matrix is computed here from the payload with [encodeQrMatrix] and
 * remembered per payload, so it is not recomputed while the status changes.
 *
 * @param state what to render.
 * @param onStartPairing the user asked for another pairing attempt.
 * @param onCancel the user cancelled the pairing.
 * @param modifier the modifier to apply.
 */
@Composable
fun PairingScreen(
    state: PairingState,
    onStartPairing: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canRetry = !state.isPairing && state.navigation == null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDimens.SpacingMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingMedium),
        ) {
            Text(
                text = stringResource(Res.string.pairing_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.pairing_instructions),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            QrSection(payload = state.qrPayload)
            if (state.isPairing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            StatusPill(text = state.statusText.asString(), tone = state.linkState.statusTone())
            state.errorMessage?.let { error ->
                Text(
                    text = error.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            if (canRetry) {
                AppButton(
                    text = stringResource(Res.string.pairing_retry),
                    onClick = onStartPairing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AppButton(
                text = stringResource(Res.string.pairing_cancel),
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.navigation == null,
                style = AppButtonStyle.Secondary,
            )
        }
    }
}

/** The QR code, or a same-sized placeholder while there is no code (yet) or it could not be encoded. */
@Composable
private fun QrSection(payload: ByteArray?) {
    val matrix = remember(payload) { payload?.let(::encodeOrNull) }
    val frame = Modifier
        .widthIn(max = QrMaxWidth)
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(MaterialTheme.shapes.medium)

    if (matrix != null) {
        QrCode(
            matrix = matrix,
            modifier = frame,
            contentDescription = stringResource(Res.string.cd_pairing_qr),
        )
    } else {
        val encodeFailed = payload != null
        Surface(modifier = frame, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(AppDimens.SpacingMedium)) {
                if (encodeFailed) {
                    Text(
                        text = stringResource(Res.string.pairing_code_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(Res.string.pairing_preparing_code),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Encodes the payload, or logs and returns null when it cannot be encoded (never logs the payload itself). */
private fun encodeOrNull(payload: ByteArray): QrMatrix? = try {
    encodeQrMatrix(payload)
} catch (failure: IllegalArgumentException) {
    Logger.withTag("PairingScreen").e(failure) { "encodeOrNull(): the QR code could not be encoded -> message=${failure.message}" }
    null
}

private val ContentMaxWidth = 560.dp
private val QrMaxWidth = 420.dp

/** A realistic 62-octet pairing payload for the previews: seed, pairing SSID and channel. */
private fun previewPayload(): ByteArray =
    encodePairingPayload(seed = ByteArray(16) { (it * 17 + 5).toByte() }, pairingSsid = "kart-pair-01", channel = 11)

@Preview
@Composable
private fun PairingScreenWaitingPreview() {
    val waiting = LinkState.Beaconing(KartNetworkKind.PAIRING)
    PreviewSurface {
        PairingScreen(
            state = PairingState(
                qrPayload = previewPayload(),
                linkState = waiting,
                statusText = waiting.toUiText(),
                isPairing = true,
            ),
            onStartPairing = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PairingScreenPreparingPreview() {
    PreviewSurface {
        PairingScreen(
            state = PairingState(
                linkState = LinkState.RadioStarting,
                statusText = LinkState.RadioStarting.toUiText(),
                isPairing = true,
            ),
            onStartPairing = {},
            onCancel = {},
        )
    }
}

@Preview
@Composable
private fun PairingScreenFailedPreview() {
    val failed = LinkState.Failed("pairing timed out")
    PreviewSurface {
        PairingScreen(
            state = PairingState(linkState = failed, statusText = failed.toUiText(), isPairing = false),
            onStartPairing = {},
            onCancel = {},
        )
    }
}
