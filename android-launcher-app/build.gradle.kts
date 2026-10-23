// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.plugin.compose)
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
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":auth-session"))
    implementation(project(":transport"))
    implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.runtime:runtime-saveable:${libs.versions.composeMultiplatform.get()}")
    implementation("org.jetbrains.compose.ui:ui:${libs.versions.composeMultiplatform.get()}")
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tv.material)
}
