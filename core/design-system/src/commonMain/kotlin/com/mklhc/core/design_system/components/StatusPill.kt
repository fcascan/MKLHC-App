package com.mklhc.core.design_system.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mklhc.core.design_system.AppDimens
import com.mklhc.core.design_system.PillShape
import com.mklhc.core.design_system.PreviewSurface
import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.asString
import com.mklhc.core.presentation.toUiText
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * What a [StatusPill] is telling the user, which decides its colour.
 *
 * Business logic: the tone maps onto the theme's instrument colours: [Neutral] is plain information,
 * [Info] is a normal live activity (cyan), [Success] is something ready or healthy (green), [Warning] needs
 * attention but is recoverable (amber) and [Error] is a fault (red).
 */
enum class StatusTone { Neutral, Info, Success, Warning, Error }

/**
 * A small rounded capsule with a status dot and a short text, for the link screen and the cockpit overlay.
 *
 * Business logic: none; it is stateless. The colour comes from [tone] through the theme. The text is the
 * accessibility label, and the dot is decorative (the tone is conveyed in words by the text, never by colour
 * alone).
 *
 * @param text the short status text.
 * @param modifier the modifier to apply.
 * @param tone what kind of status this is.
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, accent) = when (tone) {
        StatusTone.Neutral -> Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outline)
        StatusTone.Info -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        StatusTone.Success -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        StatusTone.Warning -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
        StatusTone.Error -> Triple(scheme.errorContainer, scheme.onErrorContainer, scheme.error)
    }
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = PillShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppDimens.SpacingMedium, vertical = AppDimens.SpacingSmall),
            horizontalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(accent)
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
}

/**
 * The pill tone that fits where the link stands.
 *
 * Business logic: the ladder reads as progress toward driving: waiting rungs are [StatusTone.Info], a kart that
 * is ready or driving is [StatusTone.Success], a missing adapter or a lost link that is recovering is
 * [StatusTone.Warning], and a link that gave up is [StatusTone.Error]. A pending USB permission is a warning too,
 * since nothing proceeds until the user acts.
 */
fun LinkState.statusTone(): StatusTone = when (this) {
    LinkState.NoAdapter, LinkState.AwaitingUsbPermission, is LinkState.Recovering -> StatusTone.Warning
    LinkState.RadioStarting, is LinkState.Beaconing, LinkState.KartAssociated, LinkState.KartLeased,
    LinkState.KartHandshaked -> StatusTone.Info
    LinkState.KartReady, LinkState.Driving -> StatusTone.Success
    is LinkState.Failed -> StatusTone.Error
}

/**
 * A [StatusPill] for a [LinkState]: the sentence from [toUiText] in the tone from [statusTone].
 *
 * Business logic: the link screen and the cockpit overlay both show where the link stands, so the pairing of
 * wording and colour lives here once.
 *
 * @param state where the link stands.
 * @param modifier the modifier to apply.
 */
@Composable
fun LinkStatePill(
    state: LinkState,
    modifier: Modifier = Modifier,
) {
    StatusPill(text = state.toUiText().asString(), modifier = modifier, tone = state.statusTone())
}

@Preview
@Composable
private fun StatusPillPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            StatusPill(text = "Not connected")
            StatusPill(text = "Searching for the kart", tone = StatusTone.Info)
            StatusPill(text = "Kart ready", tone = StatusTone.Success)
            StatusPill(text = "Weak signal", tone = StatusTone.Warning)
            StatusPill(text = "Link failed", tone = StatusTone.Error)
        }
    }
}

@Preview
@Composable
private fun LinkStatePillPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.SpacingSmall)) {
            LinkStatePill(LinkState.NoAdapter)
            LinkStatePill(LinkState.Beaconing(KartNetworkKind.GAME))
            LinkStatePill(LinkState.Driving)
            LinkStatePill(LinkState.Recovering("RCD control channel closed"))
            LinkStatePill(LinkState.Failed("pairing cancelled"))
        }
    }
}
