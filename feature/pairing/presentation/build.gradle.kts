// Build file for :feature:pairing:presentation: Compose Multiplatform UI library (desktop + Android).
// Hosts the pairing screen: the QR code the kart scans, the pairing progress and the cancel action. It needs
// no feature-specific domain or data layer: the ViewModel consumes the KartSession seam from :core:data
// directly, so :feature:pairing:domain and :feature:pairing:data stay placeholders. The QR matrix is computed
// with ZXing, a JVM library, in the jvmShared source set (both targets are JVM); the commonMain code only
// draws the finished matrix.
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("mklhc.kmp.library")
    id("mklhc.kmp.compose")
    id("mklhc.koin")
    id("mklhc.testing")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // KartSession is the PairingViewModel constructor parameter, so it is part of this module's API.
            api(project(":core:data"))
            // LinkState is part of the screen state, UiText is a field of it.
            api(project(":core:domain"))
            api(project(":core:presentation"))
            // The status pill, the buttons and the theme the screen is built from.
            implementation(project(":core:design-system"))
            // ViewModel is the PairingViewModel supertype; collectAsStateWithLifecycle is used by the Root.
            api(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
            // The common @Preview annotation (org.jetbrains.compose.ui.tooling.preview.Preview).
            implementation(compose.components.uiToolingPreview)
        }
        // The QR encoder: ZXing is a plain JVM library, shared by the desktop and the Android target.
        val jvmSharedMain = getByName("jvmSharedMain")
        jvmSharedMain.dependencies {
            implementation(libs.zxing.core)
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
