// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.jellyscope.launcher.homehelper"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.udnahc.jellyscope.launcher.homehelper"
        minSdk = 25
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Local verification only; publication/signing is a later release concern.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
