pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Settings plugins cannot read the version catalog; keep this version in sync with
    // [versions].foojay in gradle/libs.versions.toml.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

includeBuild("build-logic")

rootProject.name = "MKLHC-App"

include(":app")

// Core modules
include(":core:domain")
include(":core:presentation")
include(":core:design-system")
include(":core:radio")
include(":core:crypto")
include(":core:wifi-ap")
include(":core:netstack")
include(":core:kart-protocol")
include(":core:video")
include(":core:data")

// Feature: link
include(":feature:link:domain")
include(":feature:link:data")
include(":feature:link:presentation")

// Feature: pairing
include(":feature:pairing:domain")
include(":feature:pairing:data")
include(":feature:pairing:presentation")

// Feature: cockpit
include(":feature:cockpit:domain")
include(":feature:cockpit:data")
include(":feature:cockpit:presentation")
