// SPDX-License-Identifier: MPL-2.0

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.plugin.compose)
}

// Debug-only developer prefill sourced from untracked .local/dev-server.properties.
// Release builds always compile empty strings, so credentials cannot ship.
val devServerProperties =
    Properties().apply {
        val file = rootProject.file(".local/dev-server.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }

fun devProperty(key: String): String = devServerProperties.getProperty(key, "")

fun escapeJavaStringLiteral(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append('\\').append('\\')
                '"' -> append('\\').append('"')
                '\n' -> append('\\').append('n')
                '\r' -> append('\\').append('r')
                '\t' -> append('\\').append('t')
                else -> append(character)
            }
        }
    }

// Local-only release signing. With no properties file, release remains
// debug-signed so the credential-free assembleRelease gate stays usable.
val releaseKeystorePropertiesFile = rootProject.file(".local/keystore.properties")
val releaseKeystoreProperties =
    Properties().apply {
        if (releaseKeystorePropertiesFile.isFile) {
            releaseKeystorePropertiesFile.inputStream().use { load(it) }
        }
    }

fun releaseKeystoreProperty(key: String): String = releaseKeystoreProperties.getProperty(key, "")

val jellyScopeVersionCode = providers.gradleProperty("jellyscope.versionCode").get().toInt()
val jellyScopeVersionName = providers.gradleProperty("jellyscope.versionName").get()

android {
    namespace = "com.jellyscope.android"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.jellyscope"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = jellyScopeVersionCode
        versionName = jellyScopeVersionName
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("scripts/android-mpv-bundle"))
            assets.srcDir(
                rootProject.layout.buildDirectory
                    .dir("generated/android-release-license-assets")
                    .get()
                    .asFile,
            )
        }
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (releaseKeystorePropertiesFile.isFile) {
                val storeFilePath = releaseKeystoreProperty("storeFile")
                if (storeFilePath.isNotBlank()) {
                    storeFile = rootProject.file(storeFilePath)
                }
                storePassword = releaseKeystoreProperty("storePassword")
                keyAlias = releaseKeystoreProperty("keyAlias")
                keyPassword = releaseKeystoreProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_SERVER_URL", "\"${escapeJavaStringLiteral(devProperty("devServerUrl"))}\"")
            buildConfigField("String", "DEV_USERNAME", "\"${escapeJavaStringLiteral(devProperty("devUsername"))}\"")
            buildConfigField("String", "DEV_PASSWORD", "\"${escapeJavaStringLiteral(devProperty("devPassword"))}\"")
        }
        release {
            buildConfigField("String", "DEV_SERVER_URL", "\"\"")
            buildConfigField("String", "DEV_USERNAME", "\"\"")
            buildConfigField("String", "DEV_PASSWORD", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (releaseKeystorePropertiesFile.isFile) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
}

tasks.named("preBuild").configure {
    dependsOn(rootProject.tasks.named("prepareAndroidReleaseLicenseAssets"))
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(rootProject.tasks.named("verifyCleanSourceReleaseBinding"))
    }
}

tasks.register("verifyDevServerLiteralEscaping") {
    doLast {
        val value = "slash\\quote\"dollar${'$'}line\r\n雪"
        check(escapeJavaStringLiteral(value) == "slash\\\\quote\\\"dollar${'$'}line\\r\\n雪") {
            "Java build-config escaping changed"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // Keep the project-owned mpv module direct and before shared-core so the
    // app's coexistence rule resolves its audited NDK-29 libc++ runtime.
    implementation(project(":android-libmpv"))
    implementation(project(":shared-core"))
    implementation(project(":shared-ui"))
    implementation("org.jetbrains.compose.ui:ui-tooling-preview:${libs.versions.composeMultiplatform.get()}")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ffmpeg.decoder)
    testImplementation(libs.junit)
    debugImplementation("org.jetbrains.compose.ui:ui-tooling:${libs.versions.composeMultiplatform.get()}")
}
