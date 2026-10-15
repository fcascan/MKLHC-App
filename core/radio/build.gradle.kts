// Build file for :core:radio: Kotlin Multiplatform library (desktop + Android).
// Hosts the app-side client of the radiod helper: RadioLink, the IPC codec and the desktop transport.
plugins {
    id("mklhc.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Flow is part of the public RadioLink API.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
    }
}
