// Build file for :core:wifi-ap: Kotlin Multiplatform library (desktop + Android).
// Phase 1: hosts the CCMP crypto, which relies on an expect/actual CryptoPrimitives class, and the
// LP2P beacon/key derivation, which bundles the pre-encrypted nonce table as a classpath resource.
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("mklhc.kmp.library")
}

kotlin {
    // CryptoPrimitives is an expect/actual class; opt in to the (still Beta) multiplatform classes
    // feature so the build does not warn on every compilation.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
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
