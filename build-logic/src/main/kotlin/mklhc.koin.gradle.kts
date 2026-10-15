import com.mklhc.buildlogic.bundle
import com.mklhc.buildlogic.libs
import com.mklhc.buildlogic.library
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Koin convention for KMP modules: koin-core everywhere, plus the Compose integration
 * (koin-compose and koin-compose-viewmodel) when the Compose Multiplatform plugin is applied.
 * The Android-only koin-android artifact is left to the :app module.
 */
pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.commonMain.dependencies {
            implementation(libs.library("koin-core"))
        }
    }

    pluginManager.withPlugin("org.jetbrains.compose") {
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.commonMain.dependencies {
                implementation(libs.bundle("koin-compose"))
            }
        }
    }
}
