package com.mklhc.core.presentation

import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.generated.resources.Res
import com.mklhc.core.presentation.generated.resources.error_unknown
import com.mklhc.core.presentation.generated.resources.error_with_detail
import com.mklhc.core.presentation.generated.resources.link_awaiting_usb_permission
import com.mklhc.core.presentation.generated.resources.link_beaconing_game
import com.mklhc.core.presentation.generated.resources.link_beaconing_pairing
import com.mklhc.core.presentation.generated.resources.link_driving
import com.mklhc.core.presentation.generated.resources.link_failed
import com.mklhc.core.presentation.generated.resources.link_kart_associated
import com.mklhc.core.presentation.generated.resources.link_kart_handshaked
import com.mklhc.core.presentation.generated.resources.link_kart_leased
import com.mklhc.core.presentation.generated.resources.link_kart_ready
import com.mklhc.core.presentation.generated.resources.link_no_adapter
import com.mklhc.core.presentation.generated.resources.link_radio_starting
import com.mklhc.core.presentation.generated.resources.link_recovering

/**
 * Maps a failure to the text a screen can show.
 *
 * Business logic: there is no shared `DataError` type in this codebase (the lower layers surface plain
 * exceptions and a [LinkState.Failed] text), so this is the generic fallback the features can specialise: a
 * failure with a message is shown as "Something went wrong: <message>", one without (or with a blank one) as
 * the generic "Something went wrong." A feature that has a typed error maps it to its own resource instead and
 * keeps this only for the unexpected remainder. The message is shown verbatim, so exception messages must stay
 * free of secrets (the orchestrator's link texts are written that way).
 */
fun Throwable.toUiText(): UiText {
    val detail = message?.takeIf { it.isNotBlank() }
    return if (detail == null) {
        UiText.StringResource(Res.string.error_unknown)
    } else {
        UiText.StringResource(Res.string.error_with_detail, listOf(detail))
    }
}

/**
 * Maps where the link stands to one line of status text.
 *
 * Business logic: the link screen and the cockpit's connection overlay both narrate the same ladder of
 * [LinkState] rungs, so the wording lives here once. The [LinkState.Recovering] and [LinkState.Failed] texts
 * carry the orchestrator's reason as a trailing detail. The `when` is exhaustive over the sealed type, so a new
 * state fails the build until it is given a sentence.
 */
fun LinkState.toUiText(): UiText = when (this) {
    LinkState.NoAdapter -> UiText.StringResource(Res.string.link_no_adapter)
    LinkState.AwaitingUsbPermission -> UiText.StringResource(Res.string.link_awaiting_usb_permission)
    LinkState.RadioStarting -> UiText.StringResource(Res.string.link_radio_starting)
    is LinkState.Beaconing -> when (network) {
        KartNetworkKind.GAME -> UiText.StringResource(Res.string.link_beaconing_game)
        KartNetworkKind.PAIRING -> UiText.StringResource(Res.string.link_beaconing_pairing)
    }
    LinkState.KartAssociated -> UiText.StringResource(Res.string.link_kart_associated)
    LinkState.KartLeased -> UiText.StringResource(Res.string.link_kart_leased)
    LinkState.KartHandshaked -> UiText.StringResource(Res.string.link_kart_handshaked)
    LinkState.KartReady -> UiText.StringResource(Res.string.link_kart_ready)
    LinkState.Driving -> UiText.StringResource(Res.string.link_driving)
    is LinkState.Recovering -> UiText.StringResource(Res.string.link_recovering, listOf(reason))
    is LinkState.Failed -> UiText.StringResource(Res.string.link_failed, listOf(error))
}
