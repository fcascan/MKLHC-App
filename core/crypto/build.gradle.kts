// Build file for :core:crypto: Kotlin Multiplatform library (desktop + Android).
// Hosts the platform-agnostic symmetric-crypto building blocks (CryptoPrimitives), an expect/actual
// class backed by the JVM Cryptography Architecture (JCA) in the jvmShared source set. Extracted from
// :core:wifi-ap so that :core:kart-protocol (RCD) and the Wi-Fi AP stack share one crypto surface.
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
