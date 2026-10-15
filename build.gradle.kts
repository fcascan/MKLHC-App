// Root build file. Plugins are declared here (apply false) so every module shares a single
// plugin classloader. Module configuration lives in the convention plugins under build-logic.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
}
