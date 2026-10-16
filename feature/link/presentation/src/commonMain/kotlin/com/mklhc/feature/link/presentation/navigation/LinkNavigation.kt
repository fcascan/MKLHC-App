package com.mklhc.feature.link.presentation.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mklhc.feature.link.presentation.LinkRoot
import kotlinx.serialization.Serializable

/**
 * The route of the link screen, the start destination of the app.
 *
 * Business logic: a route carries no state. The link screen takes no arguments, so it is a `data object`; what
 * it shows comes from the kart session its ViewModel mirrors.
 */
@Serializable
data object LinkRoute

/**
 * Adds the link feature's destinations to the app's navigation graph.
 *
 * Business logic: the link screen is the hub of the app, so both of its exits leave the feature: "Pair a kart"
 * and "Open cockpit" are the [onNavigateToPairing] and [onNavigateToCockpit] callbacks, which the host maps onto
 * the other features' routes. This feature never imports another feature's route; the host is the only place
 * that knows how the features connect. The link screen has no intra-feature destination, so [navController] is
 * not used yet; it is part of the signature so every feature graph is wired the same way.
 *
 * @param navController the host's controller, for navigation inside the feature.
 * @param onNavigateToPairing opens the pairing screen.
 * @param onNavigateToCockpit opens the cockpit.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.linkGraph(
    navController: NavController,
    onNavigateToPairing: () -> Unit,
    onNavigateToCockpit: () -> Unit,
) {
    composable<LinkRoute> {
        LinkRoot(
            onNavigateToCockpit = onNavigateToCockpit,
            onNavigateToPairing = onNavigateToPairing,
        )
    }
}
