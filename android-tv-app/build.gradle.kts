// SPDX-License-Identifier: MPL-2.0

import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.plugin.compose)
}

// Debug-only developer prefill. Release builds always compile empty strings.
val devServerPropertiesFile = File(System.getProperty("user.home"), "Private/Keystores/dev-server.properties")
val devServerProperties =
    Properties().apply {
        if (devServerPropertiesFile.isFile) {
            devServerPropertiesFile.inputStream().use { load(it) }
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

// Local-only release signing. Incomplete credentials retain the debug fallback.
val releaseSigningDirectory = File(System.getProperty("user.home"), "Private/Keystores")
val releaseKeystorePropertiesFile = releaseSigningDirectory.resolve("keystore.properties")
val releaseKeystoreProperties =
    Properties().apply {
        if (releaseKeystorePropertiesFile.isFile) {
            releaseKeystorePropertiesFile.inputStream().use { load(it) }
        }
    }

fun releaseKeystoreProperty(key: String): String = releaseKeystoreProperties.getProperty(key, "")

val releaseKeystoreConfigured =
    releaseKeystorePropertiesFile.isFile &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .all { key -> releaseKeystoreProperty(key).isNotBlank() }

val jellyScopeVersionCode = providers.gradleProperty("jellyscope.versionCode").get().toInt()
val jellyScopeVersionName = providers.gradleProperty("jellyscope.versionName").get()

android {
    namespace = "com.jellyscope.tv"
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
        versionCode = jellyScopeVersionCode + 1
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
            if (releaseKeystoreConfigured) {
                val storeFilePath = releaseKeystoreProperty("storeFile")
                val configuredStoreFile = File(storeFilePath)
                storeFile =
                    if (configuredStoreFile.isAbsolute) {
                        configuredStoreFile
                    } else {
                        releaseSigningDirectory.resolve(storeFilePath)
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
                if (releaseKeystoreConfigured) {
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
        dependsOn(rootProject.tasks.named("verifyAndroidBinaryLicenseMetadataReadiness"))
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
    implementation(
        "org.jetbrains.compose.components:components-resources:${libs.versions.composeMultiplatform.get()}",
    )
    implementation(libs.androidx.activity.compose)
    implementation(libs.jetbrains.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.tv.material)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    debugImplementation("org.jetbrains.compose.ui:ui-tooling:${libs.versions.composeMultiplatform.get()}")
}
