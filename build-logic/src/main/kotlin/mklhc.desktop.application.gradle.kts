import com.mklhc.buildlogic.intVersionOf
import com.mklhc.buildlogic.libs
import com.mklhc.buildlogic.library
import com.mklhc.buildlogic.versionOf
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/**
 * Compose Desktop application convention for a KMP module: declares the `desktop` JVM target,
 * its Compose Desktop runtime dependencies and the `application {}` block. The module can
 * override `mainClass` in its own build file.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("mklhc.kmp.compose")
}

val jvmVersion = libs.intVersionOf("jvmTarget")

kotlin {
    jvmToolchain(jvmVersion)

    jvm("desktop")

    sourceSets.getByName("desktopMain").dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.library("kotlinx-coroutines-swing"))
    }
}

// Run and package with the toolchain JDK instead of the JVM that happens to run Gradle.
// Resolving the launcher can provision (download) a JDK, and Compose Desktop reads javaHome while
// it configures the run task, before the task graph is ready. So the toolchain is resolved only
// when a desktop run or package task was actually requested; help, tasks, projects, check and
// assemble never trigger it.
val desktopToolchainTasks =
    Regex("^(run.*|package.*|create(Release)?(Distributable|RuntimeImage)|checkRuntime|notarize.*|proguard.*|suggestRuntimeModules)$")
val desktopToolchainRequested: Boolean = gradle.startParameter.taskNames.any { requested ->
    desktopToolchainTasks.matches(requested.substringAfterLast(':'))
}

fun toolchainJavaHome(): String = project.extensions.getByType<JavaToolchainService>()
    .launcherFor { languageVersion.set(JavaLanguageVersion.of(jvmVersion)) }
    .get()
    .metadata
    .installationPath
    .asFile
    .absolutePath

compose.desktop {
    application {
        mainClass = "com.mklhc.app.MainKt"
        if (desktopToolchainRequested) {
            javaHome = toolchainJavaHome()
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MKLHC"
            packageVersion = libs.versionOf("appVersionName")
        }
    }
}
