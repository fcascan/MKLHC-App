// Build file for :app, the Android application and Compose Desktop entry point.
// Plugin order matters: the Kotlin Multiplatform plugin is applied first, then the Android
// application convention (declares the androidTarget), then the desktop application convention
// (declares jvm("desktop"), the Compose plugins and the compose.desktop application block).
// :app is the composition root: it assembles the feature navigation graphs, the Koin modules and the
// KartSession binding. The desktop target already gets kotlinx-coroutines-swing (Dispatchers.Main) from
// the desktop convention, and koin-core plus the Koin Compose integration from mklhc.koin.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("mklhc.android.application")
    id("mklhc.desktop.application")
    id("mklhc.koin")
    id("mklhc.testing")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The three screens, each with its navigation route/graph and its Koin module.
            implementation(project(":feature:link:presentation"))
            implementation(project(":feature:pairing:presentation"))
            implementation(project(":feature:cockpit:presentation"))
            // KartSession (the seam the ViewModels depend on) and the types the demo session publishes.
            implementation(project(":core:data"))
            implementation(project(":core:domain"))
            implementation(project(":core:kart-protocol"))
            // The root theme.
            implementation(project(":core:design-system"))
            implementation(project(":core:presentation"))
            // NavHost, rememberNavController, the type-safe navigate(route).
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // androidContext(), used when the Application starts Koin.
            implementation(libs.koin.android)
        }
        desktopTest.dependencies {
            // runComposeUiTest renders the app offscreen on the JVM; it needs the Skiko natives.
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            // LocalLifecycleOwner, which the test host provides to the navigation graph.
            implementation(libs.jetbrains.lifecycle.runtime.compose)
        }
    }
}
