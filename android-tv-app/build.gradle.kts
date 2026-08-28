// SPDX-License-Identifier: MPL-2.0

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.plugin.compose)
}

abstract class GenerateTvDeveloperConfig : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

@Suppress("UNCHECKED_CAST")
val developerProperties = rootProject.extra["developerProperties"] as Provider<Map<String, String>>

@Suppress("UNCHECKED_CAST")
val developerPropertiesEnabled = rootProject.extra["developerPropertiesEnabled"] as Provider<Boolean>

val developerPropertiesFile = rootProject.extra["developerPropertiesFile"] as RegularFile
val developerPropertiesInput =
    files(
        providers.provider {
            if (!developerPropertiesEnabled.get()) {
                emptyList()
            } else {
                developerPropertiesFile.asFile
                    .takeIf(File::isFile)
                    ?.let(::listOf)
                    .orEmpty()
            }
        },
    )

fun escapeKotlinStringLiteral(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append('\\').append('\\')
                '"' -> append('\\').append('"')
                '$' -> append('\\').append('$')
                '\n' -> append('\\').append('n')
                '\r' -> append('\\').append('r')
                '\t' -> append('\\').append('t')
                else -> append(character)
            }
        }
    }

fun writeTvDeveloperConfig(
    target: File,
    includeDeveloperProperties: Boolean,
    serverUrl: String,
    username: String,
    password: String,
    openSubtitlesApiKey: String,
) {
    target.parentFile.mkdirs()
    val generatedServerUrl = if (includeDeveloperProperties) serverUrl else ""
    val generatedUsername = if (includeDeveloperProperties) username else ""
    val generatedPassword = if (includeDeveloperProperties) password else ""
    val generatedOpenSubtitlesApiKey = if (includeDeveloperProperties) openSubtitlesApiKey else ""
    target.writeText(
        """
        |// SPDX-License-Identifier: MPL-2.0
        |// Generated local developer prefill.
        |package com.jellyscope.tv
        |
        |internal object TvDeveloperConfig {
        |    const val SERVER_URL: String = "${escapeKotlinStringLiteral(generatedServerUrl)}"
        |    const val USERNAME: String = "${escapeKotlinStringLiteral(generatedUsername)}"
        |    const val PASSWORD: String = "${escapeKotlinStringLiteral(generatedPassword)}"
        |    const val OPEN_SUBTITLES_API_KEY: String = "${escapeKotlinStringLiteral(generatedOpenSubtitlesApiKey)}"
        |}
        |
        """.trimMargin(),
    )
}

val generateTvDeveloperConfig =
    tasks.register<GenerateTvDeveloperConfig>("generateTvDeveloperConfig") {
        inputs.files(developerPropertiesInput).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("developerPropertiesEnabled", developerPropertiesEnabled)
        outputDirectory.set(layout.buildDirectory.dir("generated/tvDeveloperConfig/kotlin"))
        outputs.cacheIf { false }
        doLast {
            val includeDeveloperProperties = developerPropertiesEnabled.get()
            val properties = if (includeDeveloperProperties) developerProperties.get() else emptyMap()
            writeTvDeveloperConfig(
                target =
                    outputDirectory
                        .get()
                        .file("com/jellyscope/tv/TvDeveloperConfig.kt")
                        .asFile,
                includeDeveloperProperties = includeDeveloperProperties,
                serverUrl = properties["devServerUrl"].orEmpty(),
                username = properties["devUsername"].orEmpty(),
                password = properties["devPassword"].orEmpty(),
                openSubtitlesApiKey = properties["openSubtitlesApiKey"].orEmpty(),
            )
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
        release {
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

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generateTvDeveloperConfig,
            GenerateTvDeveloperConfig::outputDirectory,
        )
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
        check(escapeKotlinStringLiteral(value) == "slash\\\\quote\\\"dollar\\${'$'}line\\r\\n雪") {
            "Kotlin developer-config escaping changed"
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
