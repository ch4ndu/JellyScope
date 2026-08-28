// SPDX-License-Identifier: MPL-2.0

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

@Suppress("UNCHECKED_CAST")
val developerProperties = rootProject.extra["developerProperties"] as Provider<Map<String, String>>

@Suppress("UNCHECKED_CAST")
val developerPropertiesEnabled = rootProject.extra["developerPropertiesEnabled"] as Provider<Boolean>

val developerPropertiesFile = rootProject.extra["developerPropertiesFile"] as RegularFile
val xcodeConfiguration = providers.environmentVariable("CONFIGURATION").orElse("Release")
val developerPropertiesAllowed =
    providers.provider {
        developerPropertiesEnabled.get() && xcodeConfiguration.get().equals("Debug", ignoreCase = true)
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

fun writeTvDevServerConfig(
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
        |package com.jellyscope.tvos
        |
        |object TvDevServerConfig {
        |    const val IS_DEBUG_BUILD: Boolean = $isDebugBuild
        |    const val SERVER_URL: String = "${escapeKotlinStringLiteral(generatedServerUrl)}"
        |    const val USERNAME: String = "${escapeKotlinStringLiteral(generatedUsername)}"
        |    const val PASSWORD: String = "${escapeKotlinStringLiteral(generatedPassword)}"
        |    const val OPEN_SUBTITLES_API_KEY: String = "${escapeKotlinStringLiteral(generatedOpenSubtitlesApiKey)}"
        |}
        |
        """.trimMargin(),
    )
}

val generateTvDevServerConfig =
    tasks.register("generateTvDevServerConfig") {
        val outputDir = layout.buildDirectory.dir("generated/tvDevServerConfig/kotlin")
        inputs.files(developerPropertiesInput).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("developerPropertiesEnabled", developerPropertiesEnabled)
        inputs.property("xcodeConfiguration", xcodeConfiguration)
        outputs.dir(outputDir)
        outputs.cacheIf { false }
        doLast {
            val configuration = xcodeConfiguration.get()
            val includeDeveloperProperties = developerPropertiesAllowed.get()
            val properties = if (includeDeveloperProperties) developerProperties.get() else emptyMap()
            val target = outputDir.get().file("com/jellyscope/tvos/TvDevServerConfig.kt").asFile
            writeTvDevServerConfig(
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

val generateTvDistributionBuildInfo =
    tasks.register("generateTvDistributionBuildInfo") {
        val outputDir = layout.buildDirectory.dir("generated/tvDistributionBuildInfo/kotlin")
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
            val target = outputDir.get().file("com/jellyscope/tvos/TvDistributionBuildInfo.kt").asFile
            target.parentFile.mkdirs()
            target.writeText(
                """
                |// SPDX-License-Identifier: MPL-2.0
                |// GENERATED by :shared-tvos:generateTvDistributionBuildInfo.
                |package com.jellyscope.tvos
                |
                |internal object TvDistributionBuildInfo {
                |    const val SOURCE_REVISION: String = "${sourceRevision.get()}"
                |}
                |
                """.trimMargin(),
            )
        }
    }

tasks.register("verifyDevServerLiteralEscaping") {
    doLast {
        val value = "slash\\quote\"dollar${'$'}line\r\n雪"
        check(escapeKotlinStringLiteral(value) == "slash\\\\quote\\\"dollar\\${'$'}line\\r\\n雪") {
            "Kotlin tvOS dev-server escaping changed"
        }
        val outputDir = layout.buildDirectory.dir("verification/tvDevServerConfig")
        val debugTarget = outputDir.get().file("debug/TvDevServerConfig.kt").asFile
        val releaseTarget = outputDir.get().file("release/TvDevServerConfig.kt").asFile
        writeTvDevServerConfig(
            target = debugTarget,
            configuration = "Debug",
            includeDeveloperProperties = true,
            serverUrl = value,
            username = value,
            password = value,
            openSubtitlesApiKey = value,
        )
        writeTvDevServerConfig(
            target = releaseTarget,
            configuration = "Release",
            includeDeveloperProperties = false,
            serverUrl = value,
            username = value,
            password = value,
            openSubtitlesApiKey = value,
        )
        val debugSource = debugTarget.readText()
        val releaseSource = releaseTarget.readText()
        check("const val SERVER_URL: String = \"${escapeKotlinStringLiteral(value)}\"" in debugSource) {
            "tvOS Debug SERVER_URL verification failed"
        }
        check("const val IS_DEBUG_BUILD: Boolean = true" in debugSource) {
            "tvOS Debug IS_DEBUG_BUILD verification failed"
        }
        check("const val OPEN_SUBTITLES_API_KEY: String = \"${escapeKotlinStringLiteral(value)}\"" in debugSource) {
            "tvOS Debug OPEN_SUBTITLES_API_KEY verification failed"
        }
        listOf("SERVER_URL", "USERNAME", "PASSWORD", "OPEN_SUBTITLES_API_KEY").forEach { field ->
            check("const val $field: String = \"\"" in releaseSource) {
                "tvOS Release $field emptiness verification failed"
            }
        }
        check("const val IS_DEBUG_BUILD: Boolean = false" in releaseSource) {
            "tvOS Release IS_DEBUG_BUILD verification failed"
        }
    }
}

kotlin {
    jvmToolchain(
        libs.versions.jdkToolchain
            .get()
            .toInt(),
    )

    tvosArm64 {
        binaries.framework {
            baseName = "SharedTv"
            isStatic = true
            export(project(":shared-core"))
        }
    }

    tvosSimulatorArm64 {
        binaries.framework {
            baseName = "SharedTv"
            isStatic = true
            export(project(":shared-core"))
        }
    }

    // JVM target exists solely so commonTest presenter suites run fast on the
    // host, matching the project's JVM-first unit test strategy.
    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateTvDevServerConfig)
            kotlin.srcDir(generateTvDistributionBuildInfo)
        }
        commonMain.dependencies {
            api(project(":shared-core"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
