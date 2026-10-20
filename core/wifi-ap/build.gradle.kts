// Build file for :core:wifi-ap: Kotlin Multiplatform library (desktop + Android).
// Hosts the CCMP crypto, which relies on the shared CryptoPrimitives (now in :core:crypto), the LP2P
// beacon/key derivation, which bundles the pre-encrypted nonce table as a classpath resource, and the
// RadioAccessPoint: the netstack's FrameLink implemented over radiod's RadioLink. Neither :core:radio
// nor :core:netstack depends on this module; keep it that way (no dependency cycle).
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("mklhc.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // CryptoPrimitives is exposed through the public constructors of Ccmp, Lp2pKeys,
            // Lp2pBeacon, ApStateMachine and RadioAccessPoint, so it must stay on the consumer's compile classpath.
            api(project(":core:crypto"))
            // RadioLink is part of the public RadioAccessPoint constructor.
            api(project(":core:radio"))
            // RadioAccessPoint implements FrameLink and delivers LinkPayload; FakeStation builds DHCP messages.
            api(project(":core:netstack"))
            // CoroutineScope and StateFlow are part of the public RadioAccessPoint API.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
    }
}

// The desktop JVM target picks up resources from every source set in its hierarchy, but the Android
// target only packages the resources of its own source sets (src/androidMain, src/androidUnitTest).
// Point them at the shared directories so the AAR and the Android unit tests see the same files: the
// LP2P nonce table (jvmSharedMain) and the LP2P golden vectors (commonTest). The extension is null
// when the Android target is disabled (-Pmklhc.android.enabled=false).
extensions.findByType(LibraryExtension::class.java)?.let { android ->
    android.sourceSets.getByName("main").resources.srcDir("src/jvmSharedMain/resources")
    android.sourceSets.getByName("test").resources.srcDir("src/commonTest/resources")
}
