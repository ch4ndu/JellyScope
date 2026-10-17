// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
}

kotlin {
    jvmToolchain(
        libs.versions.jdkToolchain
            .get()
            .toInt(),
    )

    iosArm64 {
        binaries.framework {
            baseName = "SharedUi"
            isStatic = true
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = "SharedUi"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared-ui"))
            implementation(project(":shared-core"))
            implementation(compose.components.resources)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
