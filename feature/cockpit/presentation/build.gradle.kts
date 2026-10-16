// Build file for :feature:cockpit:presentation: Compose Multiplatform UI library (desktop + Android).
// Hosts the cockpit screen: the live video with the HUD (artificial horizon, G meter, battery, throttle readout)
// and the touch drive controls (steering, throttle, brake light) over it. It needs no feature-specific domain or
// data layer: the ViewModel consumes the KartSession seam from :core:data directly, so :feature:cockpit:domain and
// :feature:cockpit:data stay placeholders.
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("mklhc.kmp.library")
    id("mklhc.kmp.compose")
    id("mklhc.koin")
    id("mklhc.testing")
    // The route objects of the navigation graph are @Serializable (type-safe navigation).
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // KartSession is the CockpitViewModel constructor parameter, so it is part of this module's API.
            api(project(":core:data"))
            // LinkState is part of the screen state, UiText is a field of it.
            api(project(":core:domain"))
            api(project(":core:presentation"))
            // ViewModel is the CockpitViewModel supertype; collectAsStateWithLifecycle is used by the Root.
            api(libs.jetbrains.lifecycle.viewmodel.compose)
            // NavGraphBuilder and NavController are part of the public navigation-graph function of this module.
            api(libs.jetbrains.navigation.compose)
            // The HUD widgets, the drive controls and driveCommandOf.
            implementation(project(":core:design-system"))
            // VideoSurface, the platform seam that renders the kart's H.264 stream.
            implementation(project(":core:video"))
            // Telemetry.Imu and DriveCommand, which the ViewModel maps and forwards.
            implementation(project(":core:kart-protocol"))
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
            // The common @Preview annotation (org.jetbrains.compose.ui.tooling.preview.Preview).
            implementation(compose.components.uiToolingPreview)
        }
        desktopTest.dependencies {
            // runComposeUiTest renders the screen offscreen on the JVM; it needs the Skiko natives.
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

// Android Studio renders @Preview composables of a library module from its debug variant, which needs the
// tooling artifact on the debug classpath. The extension is null when the Android target is disabled
// (-Pmklhc.android.enabled=false).
extensions.findByType(LibraryExtension::class.java)?.let {
    dependencies {
        add("debugImplementation", compose.uiTooling)
    }
}
