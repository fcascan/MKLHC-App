package com.mklhc.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mklhc.core.design_system.AppTheme
import com.mklhc.feature.cockpit.presentation.navigation.CockpitRoute
import com.mklhc.feature.cockpit.presentation.navigation.cockpitGraph
import com.mklhc.feature.link.presentation.navigation.LinkRoute
import com.mklhc.feature.link.presentation.navigation.linkGraph
import com.mklhc.feature.pairing.presentation.navigation.PairingRoute
import com.mklhc.feature.pairing.presentation.navigation.pairingGraph

/**
 * Root composable shared by the Android and desktop entry points.
 *
 * Business logic: hosts the app content. The screens resolve their ViewModels with `koinViewModel()` from the Koin
 * the platform entry point started before composing ([com.mklhc.app.di.initKoin]); Koin's Compose integration picks
 * that running instance up on its own, so no `KoinContext` wrapper is needed (it is deprecated for that reason).
 * Koin must therefore be started first; composing this without it fails on the first `koinViewModel()`.
 */
@Composable
fun App() {
    AppContent()
}

/**
 * The app's themed navigation host. Separate from [App] so tests can compose it with their own navigation controller
 * (to inspect the back stack) inside their own Koin application.
 *
 * Business logic: the three features are assembled into one navigation graph here, and only here. Each feature
 * exposes `<feature>Graph(...)` with callbacks for the places it can lead to, and the host is the only code that
 * knows how the features connect:
 * - Link (the start destination) opens the pairing screen or the cockpit.
 * - Pairing returns to the Link screen when cancelled, and moves on into the cockpit once the kart is paired; the
 *   pairing screen is popped off the back stack on the way, so Back from the cockpit lands on the Link screen
 *   (pairing is a one-way step, not something to return to).
 * - The cockpit goes back to the Link screen.
 * Navigation is guarded against double taps: a destination is launched single-top, and a pop never removes the last
 * screen of the back stack (which would leave an empty window).
 *
 * Insets: the Link and pairing screens do not pad for the system bars themselves, so the host does, except on the
 * cockpit, which draws its video edge to edge and pads its own overlay.
 *
 * @param navController the controller driving the host; tests pass their own to inspect the back stack.
 */
@Composable
internal fun AppContent(navController: NavHostController = rememberNavController()) {
    AppTheme {
        val backStackEntry by navController.currentBackStackEntryAsState()
        val onCockpit = backStackEntry?.destination?.hasRoute<CockpitRoute>() == true
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            NavHost(
                navController = navController,
                startDestination = LinkRoute,
                modifier = if (onCockpit) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding(),
            ) {
                linkGraph(
                    navController = navController,
                    onNavigateToPairing = { navController.navigate(PairingRoute) { launchSingleTop = true } },
                    onNavigateToCockpit = { navController.navigate(CockpitRoute) { launchSingleTop = true } },
                )
                pairingGraph(
                    navController = navController,
                    onPaired = {
                        navController.navigate(CockpitRoute) {
                            popUpTo(LinkRoute)
                            launchSingleTop = true
                        }
                    },
                    onCancel = { navController.popBackStackSafely() },
                )
                cockpitGraph(
                    navController = navController,
                    onBack = { navController.popBackStackSafely() },
                )
            }
        }
    }
}

/** Pops the current screen unless it is the only one left, so a double tap on Back or Cancel cannot empty the stack. */
internal fun NavController.popBackStackSafely() {
    if (previousBackStackEntry != null) popBackStack()
}
