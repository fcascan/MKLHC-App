// Build file for :core:design-system: Compose Multiplatform UI library (desktop + Android).
// Hosts the cockpit theme (AppTheme, colour/type/shape tokens), the reusable buttons and pills, the HUD
// widgets (battery, signal, horizon, G meter, readout) and the touch drive controls (steering, throttle,
// brake light). Everything here is pure presentation: it takes plain values and reports through lambdas,
// and never reaches the session repository.
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("mklhc.kmp.library")
    id("mklhc.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // LinkState drives the link status pill.
            api(project(":core:domain"))
            // DriveCommand is what the drive-control helpers produce.
            api(project(":core:kart-protocol"))
            // UiText / toUiText resolve the link-state sentence the pill shows.
            api(project(":core:presentation"))
            // The common @Preview annotation (org.jetbrains.compose.ui.tooling.preview.Preview).
            implementation(compose.components.uiToolingPreview)
        }
        desktopTest.dependencies {
            // runComposeUiTest renders the widgets offscreen on the JVM; it needs the Skiko natives.
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
