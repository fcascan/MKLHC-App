import com.android.build.api.dsl.ApplicationExtension
import com.mklhc.buildlogic.intVersionOf
import com.mklhc.buildlogic.libs
import com.mklhc.buildlogic.versionOf
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Android application convention. Applies the Android application plugin and, when the module is
 * also a Kotlin Multiplatform module, declares its Android target.
 */
plugins {
    id("com.android.application")
}

val jvmVersion = libs.intVersionOf("jvmTarget")

extensions.configure<ApplicationExtension> {
    namespace = "com.mklhc.app"
    compileSdk = libs.intVersionOf("androidCompileSdk")

    defaultConfig {
        applicationId = "com.mklhc.app"
        minSdk = libs.intVersionOf("androidMinSdk")
        targetSdk = libs.intVersionOf("androidTargetSdk")
        versionCode = libs.intVersionOf("appVersionCode")
        versionName = libs.versionOf("appVersionName")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmVersion)
        targetCompatibility = JavaVersion.toVersion(jvmVersion)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        jvmToolchain(jvmVersion)
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(jvmVersion.toString()))
            }
        }
    }
}
