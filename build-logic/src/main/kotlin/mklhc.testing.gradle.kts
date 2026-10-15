import com.mklhc.buildlogic.bundle
import com.mklhc.buildlogic.libs
import com.mklhc.buildlogic.library
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Testing conventions: JUnit 5 platform on every JVM test task, plus coroutines-test, Turbine and
 * AssertK in commonTest. JUnit Jupiter is only added to the JVM-based leaf test source sets
 * (desktopTest and androidUnitTest) because it is a JVM-only library.
 */

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.commonTest.dependencies {
            implementation(libs.library("kotlin-test"))
            implementation(libs.bundle("testing-common"))
        }
        sourceSets.configureEach {
            if (name == "desktopTest" || name == "androidUnitTest") {
                dependencies {
                    implementation(project.dependencies.platform(libs.library("junit-bom")))
                    implementation(libs.library("junit-jupiter"))
                    runtimeOnly(libs.library("junit-platform-launcher"))
                }
            }
        }
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    dependencies {
        add("testImplementation", platform(libs.library("junit-bom")))
        add("testImplementation", libs.library("junit-jupiter"))
        add("testImplementation", libs.library("kotlin-test"))
        add("testImplementation", libs.bundle("testing-common"))
        add("testRuntimeOnly", libs.library("junit-platform-launcher"))
    }
}
