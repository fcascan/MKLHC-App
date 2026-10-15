// Build file for :app, the Android application and Compose Desktop entry point.
// Plugin order matters: the Kotlin Multiplatform plugin is applied first, then the Android
// application convention (declares the androidTarget), then the desktop application convention
// (declares jvm("desktop"), the Compose plugins and the compose.desktop application block).
// Phase 0: no inter-module dependencies are wired yet.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("mklhc.android.application")
    id("mklhc.desktop.application")
    id("mklhc.koin")
    id("mklhc.testing")
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
