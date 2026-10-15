package com.mklhc.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Basics of [LinkState] and [KartNetworkKind]: value semantics and exhaustive handling. */
class LinkStateTest {

    /** Every state once, in progression order; adding a state without updating this list fails [everyStateIsHandledExhaustively]. */
    private val allStates: List<LinkState> = listOf(
        LinkState.NoAdapter,
        LinkState.AwaitingUsbPermission,
        LinkState.RadioStarting,
        LinkState.Beaconing(KartNetworkKind.GAME),
        LinkState.KartAssociated,
        LinkState.KartLeased,
        LinkState.KartHandshaked,
        LinkState.KartReady,
        LinkState.Driving,
        LinkState.Recovering("link lost"),
        LinkState.Failed("radio crashed"),
    )

    @Test
    fun stateWithoutPayloadIsASingleton() {
        assertSame(LinkState.NoAdapter, LinkState.NoAdapter)
        assertEquals<LinkState>(LinkState.KartReady, LinkState.KartReady)
        assertNotEquals<LinkState>(LinkState.KartAssociated, LinkState.KartLeased)
    }

    @Test
    fun beaconingIsComparedByItsNetwork() {
        assertEquals(LinkState.Beaconing(KartNetworkKind.PAIRING), LinkState.Beaconing(KartNetworkKind.PAIRING))
        assertNotEquals(LinkState.Beaconing(KartNetworkKind.PAIRING), LinkState.Beaconing(KartNetworkKind.GAME))
        assertEquals(KartNetworkKind.GAME, LinkState.Beaconing(KartNetworkKind.GAME).network)
    }

    @Test
    fun recoveringAndFailedCarryTheirText() {
        assertEquals("link lost", LinkState.Recovering("link lost").reason)
        assertEquals("radio crashed", LinkState.Failed("radio crashed").error)
        assertEquals(LinkState.Recovering("a"), LinkState.Recovering("a"))
        assertNotEquals(LinkState.Recovering("a"), LinkState.Recovering("b"))
        assertNotEquals<LinkState>(LinkState.Recovering("x"), LinkState.Failed("x"))
    }

    @Test
    fun everyStateIsHandledExhaustively() {
        // The `when` has no else branch: the compiler rejects it if a LinkState subtype is added and not handled here.
        val labels = allStates.map { state ->
            when (state) {
                LinkState.NoAdapter -> "no-adapter"
                LinkState.AwaitingUsbPermission -> "awaiting-permission"
                LinkState.RadioStarting -> "radio-starting"
                is LinkState.Beaconing -> "beaconing-${state.network.name.lowercase()}"
                LinkState.KartAssociated -> "associated"
                LinkState.KartLeased -> "leased"
                LinkState.KartHandshaked -> "handshaked"
                LinkState.KartReady -> "ready"
                LinkState.Driving -> "driving"
                is LinkState.Recovering -> "recovering"
                is LinkState.Failed -> "failed"
            }
        }
        assertEquals(allStates.size, labels.toSet().size, "each state maps to its own label")
    }

    @Test
    fun networkKindHasExactlyThePairingAndGameNetworks() {
        assertEquals(listOf(KartNetworkKind.PAIRING, KartNetworkKind.GAME), KartNetworkKind.entries)
    }

    @Test
    fun toStringNamesTheStateAndItsPayload() {
        assertTrue(LinkState.Beaconing(KartNetworkKind.GAME).toString().contains("GAME"))
        assertTrue(LinkState.Failed("radio crashed").toString().contains("radio crashed"))
        assertTrue(LinkState.Driving.toString().contains("Driving"))
    }
}
