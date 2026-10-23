// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    jvmToolchain(
        libs.versions.jdkToolchain
            .get()
            .toInt(),
    )

    android {
        namespace = "com.jellyscope.auth.session"
        compileSdk =
            libs.versions.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":transport"))
            api(project(":storage"))
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            // Preserve the real DiscoveryCache integration; production stays below core.
            implementation(project(":shared-core"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        named("androidHostTest") {
            dependencies {
                implementation(libs.androidx.test.core)
                implementation(libs.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
            }
        }
    }
}
