// SPDX-License-Identifier: MPL-2.0

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
}

@Suppress("UNCHECKED_CAST")
val developerProperties = rootProject.extra["developerProperties"] as Provider<Map<String, String>>

@Suppress("UNCHECKED_CAST")
val developerPropertiesEnabled = rootProject.extra["developerPropertiesEnabled"] as Provider<Boolean>

val developerPropertiesFile = rootProject.extra["developerPropertiesFile"] as RegularFile
val xcodeConfiguration = providers.environmentVariable("CONFIGURATION").orElse("Release")
val developerPropertiesAllowed =
    providers.provider {
        developerPropertiesEnabled.get() && !xcodeConfiguration.get().equals("StoreRelease", ignoreCase = true)
    }
val developerPropertiesInput =
    files(
        providers.provider {
            if (!developerPropertiesAllowed.get()) {
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

fun writeDevServerConfig(
    target: File,
    configuration: String,
    includeDeveloperProperties: Boolean,
    serverUrl: String,
    username: String,
    password: String,
    openSubtitlesApiKey: String,
) {
    target.parentFile.mkdirs()
    val isDebugBuild = configuration.equals("Debug", ignoreCase = true)
    val generatedServerUrl = if (includeDeveloperProperties) serverUrl else ""
    val generatedUsername = if (includeDeveloperProperties) username else ""
    val generatedPassword = if (includeDeveloperProperties) password else ""
    val generatedOpenSubtitlesApiKey = if (includeDeveloperProperties) openSubtitlesApiKey else ""
    target.writeText(
        """
        |// SPDX-License-Identifier: MPL-2.0
        |// Generated local developer prefill.
        |package com.jellyscope.ui
        |
        |internal object DevServerConfig {
        |    const val SERVER_URL: String = "${escapeKotlinStringLiteral(generatedServerUrl)}"
        |    const val USERNAME: String = "${escapeKotlinStringLiteral(generatedUsername)}"
        |    const val PASSWORD: String = "${escapeKotlinStringLiteral(generatedPassword)}"
        |    const val OPEN_SUBTITLES_API_KEY: String = "${escapeKotlinStringLiteral(generatedOpenSubtitlesApiKey)}"
        |    const val IS_DEBUG_BUILD: Boolean = $isDebugBuild
        |}
        |
        """.trimMargin(),
    )
}

val generateDevServerConfig =
    tasks.register("generateDevServerConfig") {
        val outputDir = layout.buildDirectory.dir("generated/devServerConfig/kotlin")
        inputs.files(developerPropertiesInput).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("developerPropertiesEnabled", developerPropertiesEnabled)
        inputs.property("xcodeConfiguration", xcodeConfiguration)
        outputs.dir(outputDir)
        outputs.cacheIf { false }
        doLast {
            val configuration = xcodeConfiguration.get()
            val includeDeveloperProperties = developerPropertiesAllowed.get()
            val properties = if (includeDeveloperProperties) developerProperties.get() else emptyMap()
            val target = outputDir.get().file("com/jellyscope/ui/DevServerConfig.kt").asFile
            writeDevServerConfig(
                target = target,
                configuration = configuration,
                includeDeveloperProperties = includeDeveloperProperties,
                serverUrl = properties["devServerUrl"].orEmpty(),
                username = properties["devUsername"].orEmpty(),
                password = properties["devPassword"].orEmpty(),
                openSubtitlesApiKey = properties["openSubtitlesApiKey"].orEmpty(),
            )
        }
    }

val generateDistributionBuildInfo =
    tasks.register("generateDistributionBuildInfo") {
        val outputDir = layout.buildDirectory.dir("generated/distributionBuildInfo/kotlin")
        val sourceRevision =
            providers
                .exec {
                    commandLine("git", "rev-parse", "HEAD")
                }.standardOutput
                .asText
                .map(String::trim)
        inputs.property("sourceRevision", sourceRevision)
        outputs.dir(outputDir)
        doLast {
            val target = outputDir.get().file("com/jellyscope/ui/DistributionBuildInfo.kt").asFile
            target.parentFile.mkdirs()
            target.writeText(
                """
                |// SPDX-License-Identifier: MPL-2.0
                |// GENERATED by :shared-ui:generateDistributionBuildInfo.
                |package com.jellyscope.ui
                |
                |internal object DistributionBuildInfo {
                |    const val SOURCE_REVISION: String = "${sourceRevision.get()}"
                |}
                |
                """.trimMargin(),
            )
        }
    }

val verifyReleaseDevServerConfig =
    tasks.register("verifyReleaseDevServerConfig") {
        group = "verification"
        description = "Verifies local and StoreRelease developer-config generation."
        val outputDir = layout.buildDirectory.dir("verification/devServerConfig")
        outputs.dir(outputDir)
        doLast {
            val sentinelServerUrl = "https://sentinel.invalid\\path\"${'$'}server\r\n雪"
            val sentinelUsername = "sentinel-${'$'}user"
            val sentinelPassword = "sentinel-password\t雪"
            val sentinelOpenSubtitlesApiKey = "sentinel-open-subtitles-${'$'}key"
            val localReleaseTarget = outputDir.get().file("local-release/DevServerConfig.kt").asFile
            val storeReleaseTarget = outputDir.get().file("store-release/DevServerConfig.kt").asFile
            val disabledTarget = outputDir.get().file("disabled/DevServerConfig.kt").asFile
            val debugTarget = outputDir.get().file("debug/DevServerConfig.kt").asFile

            writeDevServerConfig(
                target = localReleaseTarget,
                configuration = "Release",
                includeDeveloperProperties = true,
                serverUrl = sentinelServerUrl,
                username = sentinelUsername,
                password = sentinelPassword,
                openSubtitlesApiKey = sentinelOpenSubtitlesApiKey,
            )
            writeDevServerConfig(
                target = storeReleaseTarget,
                configuration = "StoreRelease",
                includeDeveloperProperties = false,
                serverUrl = sentinelServerUrl,
                username = sentinelUsername,
                password = sentinelPassword,
                openSubtitlesApiKey = sentinelOpenSubtitlesApiKey,
            )
            writeDevServerConfig(
                target = disabledTarget,
                configuration = "Release",
                includeDeveloperProperties = false,
                serverUrl = sentinelServerUrl,
                username = sentinelUsername,
                password = sentinelPassword,
                openSubtitlesApiKey = sentinelOpenSubtitlesApiKey,
            )
            writeDevServerConfig(
                target = debugTarget,
                configuration = "Debug",
                includeDeveloperProperties = true,
                serverUrl = sentinelServerUrl,
                username = sentinelUsername,
                password = sentinelPassword,
                openSubtitlesApiKey = sentinelOpenSubtitlesApiKey,
            )

            val localReleaseSource = localReleaseTarget.readText()
            val storeReleaseSource = storeReleaseTarget.readText()
            val disabledSource = disabledTarget.readText()
            val debugSource = debugTarget.readText()

            fun credentialValues(
                source: String,
                field: String,
            ): List<String> =
                Regex("""const val $field: String = \"([^\"]*)\"""")
                    .findAll(source)
                    .map { match -> match.groupValues[1] }
                    .toList()

            listOf("SERVER_URL", "USERNAME", "PASSWORD", "OPEN_SUBTITLES_API_KEY").forEach { field ->
                check(credentialValues(localReleaseSource, field).single().isNotEmpty()) {
                    "Local Release $field verification failed"
                }
                listOf(storeReleaseSource, disabledSource).forEach { source ->
                    check(credentialValues(source, field) == listOf("")) {
                        "$field emptiness verification failed"
                    }
                }
            }
            check("const val IS_DEBUG_BUILD: Boolean = false" in localReleaseSource) {
                "Release IS_DEBUG_BUILD verification failed"
            }
            check("const val IS_DEBUG_BUILD: Boolean = false" in storeReleaseSource) {
                "StoreRelease IS_DEBUG_BUILD verification failed"
            }
            check("const val SERVER_URL: String = \"${escapeKotlinStringLiteral(sentinelServerUrl)}\"" in debugSource) {
                "Debug SERVER_URL escaping verification failed"
            }
            check("const val USERNAME: String = \"${escapeKotlinStringLiteral(sentinelUsername)}\"" in debugSource) {
                "Debug USERNAME escaping verification failed"
            }
            check("const val PASSWORD: String = \"${escapeKotlinStringLiteral(sentinelPassword)}\"" in debugSource) {
                "Debug PASSWORD escaping verification failed"
            }
            val expectedOpenSubtitlesApiKey =
                "const val OPEN_SUBTITLES_API_KEY: String = " +
                    "\"${escapeKotlinStringLiteral(sentinelOpenSubtitlesApiKey)}\""
            check(
                expectedOpenSubtitlesApiKey in debugSource,
            ) {
                "Debug OPEN_SUBTITLES_API_KEY escaping verification failed"
            }
            check("const val IS_DEBUG_BUILD: Boolean = true" in debugSource) {
                "Debug IS_DEBUG_BUILD verification failed"
            }
        }
    }

kotlin {
    jvmToolchain(
        libs.versions.jdkToolchain
            .get()
            .toInt(),
    )

    android {
        namespace = "com.jellyscope.ui"
        compileSdk =
            libs.versions.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()

        // Compose MP resources need Android resources enabled under the
        // com.android.kotlin.multiplatform.library plugin (off by default);
        // without this the .cvr assets never reach the APK and string
        // lookups crash at runtime.
        experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true

        withHostTest {}
    }

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

    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateDevServerConfig)
            kotlin.srcDir(generateDistributionBuildInfo)
        }
        commonMain.dependencies {
            api(project(":shared-core"))
            api(compose.runtime)
            implementation(compose.components.resources)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.jetbrains.ui.backhandler)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.atomicfu)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            // ui-tooling (ComposeViewAdapter) must be on the module classpath for
            // Android Studio to render @Preview composables defined in this module.
            // The KMP android-library plugin has no debug/release variant split, so
            // this cannot be debug-scoped here.
            implementation(compose.uiTooling)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui)
            implementation(libs.androidx.palette.ktx)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.common)
            implementation(libs.jna)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    packageOfResClass = "com.jellyscope.ui.generated.resources"
    // Public so android-tv-app can reuse shared string resources (e.g. the
    // media-info labels) instead of duplicating them into TV's own strings.xml.
    publicResClass = true
}
