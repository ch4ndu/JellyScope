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
        namespace = "com.jellyscope.core"
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
            api(project(":core-model"))
            api(project(":diagnostics"))
            api(project(":platform"))
            api(project(":network"))
            api(project(":player"))
            api(project(":playback-policy"))
            api(project(":jellyfin-api"))
            api(project(":diagnostics-runtime"))
            api(project(":player-runtime"))
            api(project(":storage"))
            api(project(":transport"))
            api(project(":auth-session"))
            api(libs.kermit)
            api(libs.koin.core)
            api(libs.ktor.client.core)
            api(libs.kotlinx.datetime)
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.network)
            implementation(libs.ktor.client.resources)
            implementation(libs.ktor.client.serialization)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.koin.android)
            // API 33 and lower use the one app-owned foreground dataSync worker.
            // API 34+ is deliberately scheduled through UIDT JobScheduler only;
            // this dependency does not make WorkManager an SDK-34 fallback.
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.ktor.client.okhttp)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
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

        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
