// Build file for :core:data: Kotlin Multiplatform library (desktop + Android).
// Hosts the orchestration layer's data seams: the identity derived from the installation master
// secret, the persistent PairingStore (in-memory and DataStore-backed), and the KartRadioAp edge
// interface that the radio + access-point layer will implement.
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("mklhc.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Models the repository exposes (LinkState, KartInfo, KartSettings, ...).
            api(project(":core:domain"))
            // CryptoPrimitives is part of deriveKartIdentity / the store constructors.
            api(project(":core:crypto"))
            // FrameLink is part of the public KartRadioAp API.
            api(project(":core:netstack"))
            // RcdServerInfo (identity factory) and the Fuji/telemetry/teleop types the orchestrator reuses.
            api(project(":core:kart-protocol"))
            // The access-point stack KartRadioAp will be implemented over (MacAddress, ApConfig, LP2P).
            api(project(":core:wifi-ap"))
            api(libs.kotlinx.coroutines.core)
            // DataStore<Preferences> is part of the public DataStorePairingStore API.
            api(libs.androidx.datastore.preferences.core)
            implementation(libs.kermit)
        }
    }
}

// The desktop JVM test task picks up resources from every source set in its hierarchy, but the
// Android unit test only packages the resources of its own source sets. Point it at the shared
// commonTest directory so the identity golden vectors are visible to both. The extension is null
// when the Android target is disabled (-Pmklhc.android.enabled=false).
extensions.findByType(LibraryExtension::class.java)?.let { android ->
    android.sourceSets.getByName("test").resources.srcDir("src/commonTest/resources")
}
