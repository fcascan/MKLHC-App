package com.mklhc.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.getByType

/**
 * Helpers shared by the MKLHC convention plugins.
 *
 * Precompiled script plugins do not get the type-safe `libs` accessor, so the version catalog of
 * the consuming project is looked up by name instead.
 */
val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Returns the raw version string declared under `[versions]` for [alias]. */
fun VersionCatalog.versionOf(alias: String): String =
    findVersion(alias)
        .orElseThrow { IllegalStateException("Version '$alias' is missing from libs.versions.toml") }
        .requiredVersion

/** Returns the numeric version declared under `[versions]` for [alias]. */
fun VersionCatalog.intVersionOf(alias: String): Int = versionOf(alias).toInt()

/** Returns the library declared under `[libraries]` for [alias]. */
fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias)
        .orElseThrow { IllegalStateException("Library '$alias' is missing from libs.versions.toml") }

/** Returns the bundle declared under `[bundles]` for [alias]. */
fun VersionCatalog.bundle(alias: String) =
    findBundle(alias)
        .orElseThrow { IllegalStateException("Bundle '$alias' is missing from libs.versions.toml") }

/** Path segments of the project, for example `:feature:link:domain` gives `[feature, link, domain]`. */
fun Project.modulePathSegments(): List<String> = path.split(':').filter { it.isNotEmpty() }

/**
 * Unique Android namespace derived from the Gradle path, for example `:core:design-system`
 * gives `com.mklhc.core.design_system`. Several modules share a leaf name (domain, data,
 * presentation), so the full path is required to keep namespaces unique.
 */
fun Project.moduleNamespace(): String =
    (listOf("com", "mklhc") + modulePathSegments()).joinToString(".") { it.replace('-', '_') }

/**
 * Unique archive base name derived from the Gradle path, for example `:feature:link:domain`
 * gives `feature-link-domain`. Several modules share a leaf name, and identical jar names would
 * overwrite each other in the packaged desktop distribution.
 */
fun Project.moduleArchiveName(): String = modulePathSegments().joinToString("-")

/**
 * Whether the Android target of KMP library modules should be configured.
 *
 * The Android target is required by default, so a green `check` always means the Android sources
 * compiled too. When no Android SDK can be located the Android Gradle Plugin then fails fast at
 * configuration time instead of the build silently narrowing to the desktop target. Opt out
 * explicitly with `-Pmklhc.android.enabled=false` (for example on a machine without an SDK); any
 * other value, or no property at all, keeps the Android target enabled.
 */
fun Project.isAndroidTargetEnabled(): Boolean {
    val enabled = providers.gradleProperty("mklhc.android.enabled").orNull?.toBoolean() ?: true
    if (enabled) return true

    if (!rootProject.extra.has(ANDROID_SKIPPED_WARNING_KEY)) {
        rootProject.extra[ANDROID_SKIPPED_WARNING_KEY] = true
        logger.warn(
            "mklhc.android.enabled=false: skipping the Android target of KMP library modules; " +
                "only the desktop target is configured, so this build does not verify Android.",
        )
    }
    return false
}

private const val ANDROID_SKIPPED_WARNING_KEY = "mklhc.androidSkippedWarning"
