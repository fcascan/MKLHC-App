// Build file for :feature:link:presentation: Compose Multiplatform UI library (desktop + Android).
// Hosts the link screen: the radio/kart link status, the kart information, the start / pair / cockpit actions
// and the capture toggle. It needs no feature-specific domain or data layer: the ViewModel consumes the
// KartSession seam from :core:data directly, so :feature:link:domain and :feature:link:data stay placeholders.
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
            // KartSession is the LinkViewModel constructor parameter, so it is part of this module's API.
            api(project(":core:data"))
            // LinkState and KartInfo are part of the screen state, UiText is a field of it.
            api(project(":core:domain"))
            api(project(":core:presentation"))
            // The link-status pill, the labelled values, the buttons and the battery gauge.
            implementation(project(":core:design-system"))
            // ViewModel is the LinkViewModel supertype; collectAsStateWithLifecycle is used by the Root.
            api(libs.jetbrains.lifecycle.viewmodel.compose)
            // NavGraphBuilder and NavController are part of the public navigation-graph function of this module.
            api(libs.jetbrains.navigation.compose)
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
