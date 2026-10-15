@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.android.build.api.dsl.LibraryExtension
import com.mklhc.buildlogic.intVersionOf
import com.mklhc.buildlogic.isAndroidTargetEnabled
import com.mklhc.buildlogic.libs
import com.mklhc.buildlogic.moduleArchiveName
import com.mklhc.buildlogic.moduleNamespace
import org.gradle.api.plugins.BasePluginExtension
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Kotlin Multiplatform library convention: a desktop JVM target and an Android library target
 * (on by default; opt out with `-Pmklhc.android.enabled=false`), with an intermediate `jvmShared`
 * source set that both androidMain and desktopMain depend on.
 *
 * Hierarchy:
 *   commonMain -> jvmShared -> androidMain, desktopMain
 *
 * commonTest is wired with kotlin.test, coroutines-test, Turbine and AssertK by mklhc.testing.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("mklhc.testing")
}

val jvmVersion = libs.intVersionOf("jvmTarget")
val androidEnabled = isAndroidTargetEnabled()

if (androidEnabled) {
    pluginManager.apply("com.android.library")
}

// Several modules share a leaf name (domain, data, presentation); a path-based archive name keeps
// jar and aar file names unique.
extensions.configure<BasePluginExtension> {
    archivesName.set(moduleArchiveName())
}

kotlin {
    jvmToolchain(jvmVersion)

    jvm("desktop")

    if (androidEnabled) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget(jvmVersion.toString()))
            }
        }
    }

    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                if (androidEnabled) {
                    withAndroidTarget()
                }
            }
        }
    }
}

if (androidEnabled) {
    extensions.configure<LibraryExtension> {
        namespace = moduleNamespace()
        compileSdk = libs.intVersionOf("androidCompileSdk")
        defaultConfig {
            minSdk = libs.intVersionOf("androidMinSdk")
        }
        compileOptions {
            sourceCompatibility = JavaVersion.toVersion(jvmVersion)
            targetCompatibility = JavaVersion.toVersion(jvmVersion)
        }
    }
}
