import com.mklhc.buildlogic.moduleNamespace

/**
 * Compose Multiplatform convention, applied on top of a KMP module: the Compose Multiplatform
 * plugin, the Compose compiler plugin and the common Compose dependencies.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation(compose.components.resources)
    }
}

// Several modules share a leaf name (domain, data, presentation); a path-based package keeps the
// generated `Res` classes of modules that ship Compose resources from colliding.
compose.resources {
    packageOfResClass = "${moduleNamespace()}.generated.resources"
}
