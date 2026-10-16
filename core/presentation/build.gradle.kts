// Build file for :core:presentation: Compose Multiplatform UI library (desktop + Android).
// Hosts the presentation utilities every feature screen shares: UiText (a string that is either dynamic
// or a Compose Multiplatform string resource) and the mappers from domain states and errors to UiText.
plugins {
    id("mklhc.kmp.library")
    id("mklhc.kmp.compose")
}

compose.resources {
    // The shared strings (generic errors, link-state texts) are referenced by token from other modules,
    // for example `Res.string.error_unknown`, so the generated Res class has to be public here.
    publicResClass = true
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // LinkState is mapped to UiText here, and it is part of the public mapper signature.
            api(project(":core:domain"))
            // UiText.StringResource carries an org.jetbrains.compose.resources.StringResource, so the
            // resources library is on this module's API surface (the convention only adds it as implementation).
            api(compose.components.resources)
        }
        desktopTest.dependencies {
            // runComposeUiTest needs the Skiko natives to compose offscreen; the desktop test source set is
            // where UiText.asString() is exercised end to end against the real resource files.
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
