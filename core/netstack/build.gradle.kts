// Build file for :core:netstack: Kotlin Multiplatform library (desktop + Android).
// Phase 1: ARP, IPv4, UDP and a single-lease DHCP server over the FrameLink boundary. Standalone:
// it deliberately does not depend on :core:wifi-ap (which owns 802.11 and LLC/SNAP).
plugins {
    id("mklhc.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Flow is part of the public FrameLink API.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
        }
    }
}
