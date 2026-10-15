plugins {
    `kotlin-dsl`
}

group = "com.mklhc.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.android.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.compose.compiler.gradlePlugin)
}
