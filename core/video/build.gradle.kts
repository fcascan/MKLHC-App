// Build file for :core:video: Compose Multiplatform library (desktop + Android).
// Hosts the video surface seam: the VideoSurface composable that renders the kart's H.264 stream. The Android
// actual decodes with the hardware MediaCodec straight onto a SurfaceView; the desktop actual decodes with FFmpeg
// through the JavaCPP presets (LGPL build) and draws the pictures in Compose. It is the only module that touches the
// raw frame flow.
plugins {
    id("mklhc.kmp.library")
    id("mklhc.kmp.compose")
}

// The JavaCPP classifier of the machine that builds: the desktop app is run and packaged per operating system, so the
// FFmpeg and JavaCPP native libraries of that system are the only ones it needs (about 20-30 MB).
val javacppPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.startsWith("windows") -> "windows-x86_64"
        os.startsWith("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Flow<ByteArray> (the reassembled access units) is part of the VideoSurface signature.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
        desktopMain.dependencies {
            // FFmpeg's libavcodec decodes the kart's H.264 (High profile, CABAC, 8x8 transform), which no pure-Java
            // decoder handles. The default build is LGPL; the -gpl classifiers are deliberately not used.
            implementation(libs.bytedeco.ffmpeg)
            implementation(libs.bytedeco.javacpp)
            implementation("${libs.bytedeco.ffmpeg.get()}:$javacppPlatform")
            implementation("${libs.bytedeco.javacpp.get()}:$javacppPlatform")
        }
        desktopTest.dependencies {
            // runComposeUiTest renders the surface offscreen on the JVM; it needs the Skiko natives.
            implementation(compose.desktop.currentOs)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
