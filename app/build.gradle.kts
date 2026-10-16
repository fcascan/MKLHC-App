// Build file for :app, the Android application and Compose Desktop entry point.
// Plugin order matters: the Kotlin Multiplatform plugin is applied first, then the Android
// application convention (declares the androidTarget), then the desktop application convention
// (declares jvm("desktop"), the Compose plugins and the compose.desktop application block).
// :app is the composition root: it assembles the feature navigation graphs, the Koin modules and the
// KartSession binding. The desktop target already gets kotlinx-coroutines-swing (Dispatchers.Main) from
// the desktop convention, and koin-core plus the Koin Compose integration from mklhc.koin.
import org.gradle.jvm.toolchain.JavaLanguageVersion

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
        desktopMain.dependencies {
            // The radio hardware spike (Task 1.8, com.mklhc.app.spike) wires the desktop radio link, the software
            // access point, the network stack and the capture writer by hand, outside Koin and the UI.
            implementation(project(":core:radio"))
            implementation(project(":core:wifi-ap"))
            implementation(project(":core:netstack"))
            implementation(project(":core:crypto"))
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

// Task 1.8 hardware spike: runs com.mklhc.app.spike.SpikeMainKt (a console program, no window) on the desktop
// runtime classpath. It runs from the repository root so the relative defaults (radiod/build, spike-captures/)
// resolve, and forwards stdin so Enter stops it. Options go through --args, for example:
//   ./gradlew :app:runSpike --args="--radiod C:\tools\radiod.exe --channel 6 --duration 300"
// The JVM prints UTF-8 because the console QR code is drawn with block characters.
val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
tasks.register<JavaExec>("runSpike") {
    group = "application"
    description = "Runs the radio hardware spike (Task 1.8): pairing beacon, association, DHCP DISCOVER and a decrypted pcap."
    mainClass.set("com.mklhc.app.spike.SpikeMainKt")
    classpath(desktopMainCompilation.output.allOutputs, desktopMainCompilation.runtimeDependencyFiles)
    workingDir = rootProject.projectDir
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(libs.versions.jvmTarget.get().toInt()))
        },
    )
}
