// Build file for :core:kart-protocol: Kotlin Multiplatform library (desktop + Android).
// Phase 4A: hosts the RCD protocol (message framing, handshake service, client/server), ported from
// OpenKart's openkartd/rcd.py. Depends on :core:crypto for the SHA primitives used by the pairing-key
// derivation and the handshake running hash, and on :core:netstack for the TCP byte stream RCD runs
// over in production (unit tests drive it over an in-memory fake transport).
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("mklhc.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // CryptoPrimitives is exposed through RcdServerInfo / RcdHandshakeService constructors, and
            // TcpConnection through the production transport adapter, so both stay on the API surface.
            api(project(":core:crypto"))
            api(project(":core:netstack"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
    }
}

// The desktop JVM test task picks up resources from every source set in its hierarchy, but the
// Android unit test only packages the resources of its own source sets. Point it at the shared
// commonTest directory so the RCD golden vectors are visible to both. The extension is null when the
// Android target is disabled (-Pmklhc.android.enabled=false).
extensions.findByType(LibraryExtension::class.java)?.let { android ->
    android.sourceSets.getByName("test").resources.srcDir("src/commonTest/resources")
}
