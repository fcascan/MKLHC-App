package com.mklhc.feature.pairing.presentation

import androidx.compose.runtime.Stable
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.UiText
import com.mklhc.core.presentation.toUiText

/**
 * Where the pairing screen asks its host to go next. A one-time effect, modelled as a consumable field of
 * [PairingState.navigation]: the Root performs it and then tells the ViewModel it was handled.
 */
enum class PairingNavigation {
    /** The kart is paired and on the game network; leave the pairing screen (to the cockpit or the link screen). */
    Paired,

    /** The user cancelled the pairing; leave the pairing screen (back to the link screen). */
    Cancelled,
}

/**
 * Everything the pairing screen renders.
 *
 * Business logic: a single immutable snapshot, replaced as a whole by [PairingViewModel]. The screen shows the
 * [qrPayload] (the 62 raw octets the kart scans, while the pairing network is up) and narrates the pairing
 * through [statusText], which follows [linkState]. [isPairing] is true while the flow is running (it is false
 * once the link has failed or the pairing was cancelled, which is when the screen offers another attempt).
 * The one-time effect is [navigation], consumed through [PairingViewModel.onNavigationHandled].
 *
 * Marked [Stable] because it holds a [ByteArray], which the Compose compiler cannot prove stable on its own. That
 * is safe here: the array is never mutated after it is put into the state, and [equals] compares its content, so
 * two states with the same octets are equal and a re-emission of the same code does not recompose the screen.
 *
 * @property qrPayload the raw QR payload octets, or null while no code is shown.
 * @property linkState where the link currently stands.
 * @property statusText the sentence that narrates [linkState].
 * @property isPairing whether the pairing flow is in progress.
 * @property errorMessage why the pairing could not be started, or null.
 * @property navigation the pending one-time navigation, or null.
 */
@Stable
data class PairingState(
    val qrPayload: ByteArray? = null,
    val linkState: LinkState = LinkState.NoAdapter,
    val statusText: UiText = LinkState.NoAdapter.toUiText(),
    val isPairing: Boolean = false,
    val errorMessage: UiText? = null,
    val navigation: PairingNavigation? = null,
) {
    // A ByteArray property compares by reference in a data class; compare its content instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingState) return false
        return qrPayload.contentEquals(other.qrPayload) &&
            linkState == other.linkState &&
            statusText == other.statusText &&
            isPairing == other.isPairing &&
            errorMessage == other.errorMessage &&
            navigation == other.navigation
    }

    override fun hashCode(): Int {
        var result = qrPayload?.contentHashCode() ?: 0
        result = 31 * result + linkState.hashCode()
        result = 31 * result + statusText.hashCode()
        result = 31 * result + isPairing.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + (navigation?.hashCode() ?: 0)
        return result
    }

    // The payload is secret key material: say that a code is present, never print its octets.
    override fun toString(): String =
        "PairingState(qrPayload=${qrPayload?.let { "<${it.size} octets>" }}, linkState=$linkState, " +
            "isPairing=$isPairing, errorMessage=$errorMessage, navigation=$navigation)"
}
