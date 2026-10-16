package com.mklhc.feature.cockpit.presentation.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mklhc.feature.cockpit.presentation.CockpitRoot
import kotlinx.serialization.Serializable

/**
 * The route of the cockpit screen.
 *
 * Business logic: a route carries no state. The cockpit takes no arguments, so it is a `data object`; the live
 * video, the HUD and the drive controls all come from the kart session its ViewModel is bound to.
 */
@Serializable
data object CockpitRoute

/**
 * Adds the cockpit feature's destinations to the app's navigation graph.
 *
 * Business logic: the cockpit is a leaf of the app: its only exit is the back button, reported through [onBack]
 * so the host decides where it leads. Leaving the cockpit returns the kart to neutral (the screen and its
 * ViewModel see to that themselves). The cockpit has no intra-feature destination, so [navController] is not
 * used yet; it is part of the signature so every feature graph is wired the same way.
 *
 * @param navController the host's controller, for navigation inside the feature.
 * @param onBack the driver tapped the back button; the host pops the cockpit.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.cockpitGraph(
    navController: NavController,
    onBack: () -> Unit,
) {
    composable<CockpitRoute> {
        CockpitRoot(onNavigateBack = onBack)
    }
}
