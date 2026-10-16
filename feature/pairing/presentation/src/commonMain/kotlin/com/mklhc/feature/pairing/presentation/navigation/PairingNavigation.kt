package com.mklhc.feature.pairing.presentation.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mklhc.feature.pairing.presentation.PairingRoot
import kotlinx.serialization.Serializable

/**
 * The route of the pairing screen.
 *
 * Business logic: a route carries no state. The pairing screen takes no arguments, so it is a `data object`;
 * opening it starts the pairing flow (its ViewModel does that when it is created, scoped to this destination's
 * back stack entry).
 */
@Serializable
data object PairingRoute

/**
 * Adds the pairing feature's destinations to the app's navigation graph.
 *
 * Business logic: the pairing screen ends in one of two ways, both reported to the host through callbacks so the
 * feature never names another feature's route: [onPaired] when the kart has joined the game network and the link
 * is ready, and [onCancel] when the user gives up. The pairing screen has no intra-feature destination, so
 * [navController] is not used yet; it is part of the signature so every feature graph is wired the same way.
 *
 * @param navController the host's controller, for navigation inside the feature.
 * @param onPaired the kart was paired; the host moves on (typically into the cockpit).
 * @param onCancel the user cancelled; the host leaves the screen.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.pairingGraph(
    navController: NavController,
    onPaired: () -> Unit,
    onCancel: () -> Unit,
) {
    composable<PairingRoute> {
        PairingRoot(
            onPaired = onPaired,
            onCancel = onCancel,
        )
    }
}
