// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.plugin.compose)
}

android {
    namespace = "com.jellyscope.hero"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()
    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.animation:animation:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.ui:ui:${libs.versions.composeMultiplatform.get()}")
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
