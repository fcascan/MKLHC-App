package com.mklhc.core.presentation

import com.mklhc.core.domain.KartNetworkKind
import com.mklhc.core.domain.LinkState
import com.mklhc.core.presentation.generated.resources.Res
import com.mklhc.core.presentation.generated.resources.error_unknown
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The mappers from errors and from [LinkState] to [UiText]. The link mapper must cover every rung of the
 * ladder (the `when` is exhaustive over the sealed type, so a new state breaks the build rather than the UI),
 * and these tests pin the text each one resolves to.
 */
class UiTextMappersTest {

    @Test
    fun throwableWithAMessageCarriesTheMessageAsDetail() = runTest {
        assertEquals("Something went wrong: link dropped", IllegalStateException("link dropped").toUiText().resolve())
    }

    @Test
    fun throwableWithoutAMessageFallsBackToTheGenericText() = runTest {
        val text = IllegalStateException().toUiText()
        assertEquals(UiText.StringResource(Res.string.error_unknown), text)
        assertEquals("Something went wrong.", text.resolve())
    }

    @Test
    fun throwableWithABlankMessageFallsBackToTheGenericText() {
        assertEquals(UiText.StringResource(Res.string.error_unknown), IllegalStateException("   ").toUiText())
    }

    @Test
    fun everyLinkStateMapsToAResourceBackedText() = runTest {
        val expected = mapOf(
            LinkState.NoAdapter to "No radio adapter found",
            LinkState.AwaitingUsbPermission to "Waiting for USB permission",
            LinkState.RadioStarting to "Starting the radio",
            LinkState.Beaconing(KartNetworkKind.GAME) to "Waiting for the kart to join",
            LinkState.Beaconing(KartNetworkKind.PAIRING) to "Waiting for the kart to pair",
            LinkState.KartAssociated to "Kart joined the network",
            LinkState.KartLeased to "Kart received its address",
            LinkState.KartHandshaked to "Kart connected",
            LinkState.KartReady to "Kart ready",
            LinkState.Driving to "Driving",
            LinkState.Recovering("RCD control channel closed") to "Link lost, reconnecting: RCD control channel closed",
            LinkState.Failed("pairing cancelled") to "Link failed: pairing cancelled",
        )
        for ((state, text) in expected) {
            val mapped = state.toUiText()
            assertIs<UiText.StringResource>(mapped, "state $state")
            assertEquals(text, mapped.resolve(), "state $state")
        }
    }
}
