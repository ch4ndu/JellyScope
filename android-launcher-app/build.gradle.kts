// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.plugin.serialization)
}

android {
    namespace = "com.jellyscope.launcher"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.udnahc.jellyscope.launcher"
        minSdk = 25
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local verification only; publication/signing is a later release concern.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(project(":ui-focus-scroll"))
    implementation(project(":android-hero-ui"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":auth-session"))
    implementation(project(":transport"))
    implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.runtime:runtime-saveable:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.ui:ui:${libs.versions.composeMultiplatform.get()}")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.koin.android)
    implementation(libs.jetbrains.lifecycle.runtime.compose)
    implementation(libs.jetbrains.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tv.material)
}
