// Build file for :core:video: Compose Multiplatform library (desktop + Android).
// Hosts the video surface seam: the VideoSurface composable that renders the kart's H.264 stream. The Android
// actual decodes with the hardware MediaCodec straight onto a SurfaceView; the desktop actual is a placeholder
// (a real desktop decoder is a separate, deferred spike). It is the only module that touches the raw frame flow.
plugins {
    id("mklhc.kmp.library")
    id("mklhc.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Flow<ByteArray> (the reassembled access units) is part of the VideoSurface signature.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
        desktopTest.dependencies {
            // runComposeUiTest renders the placeholder offscreen on the JVM; it needs the Skiko natives.
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
