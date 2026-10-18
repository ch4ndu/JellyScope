// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    jvmToolchain(
        libs.versions.jdkToolchain
            .get()
            .toInt(),
    )

    android {
        namespace = "com.jellyscope.storage"
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
            api(project(":diagnostics-runtime"))
            api(libs.koin.core)
            api(libs.room.runtime)
            api(libs.kotlinx.serialization.json)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.atomicfu)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.koin.android)
        }

        named("androidHostTest") {
            dependencies {
                implementation(project(":shared-core"))
                implementation(libs.androidx.test.core)
                implementation(libs.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
                implementation(libs.room.testing)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspTvosArm64", libs.room.compiler)
    add("kspTvosSimulatorArm64", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}
