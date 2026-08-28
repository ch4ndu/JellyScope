// SPDX-License-Identifier: MPL-2.0

import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.ktlint)
}

val developerPropertyKeys =
    listOf(
        "devServerUrl",
        "devUsername",
        "devPassword",
        "openSubtitlesApiKey",
    )
val developerPropertiesFile = layout.projectDirectory.file("developer.properties")

fun isDeveloperPropertiesDisabledByEnvironment(value: String?): Boolean = value?.trim()?.lowercase() in setOf("true", "1")

fun developerPropertiesAreEnabled(
    forceDisableValue: String?,
    requestedValue: String?,
    ciValue: String?,
): Boolean =
    when {
        isDeveloperPropertiesDisabledByEnvironment(forceDisableValue) -> false
        requestedValue?.trim().equals("false", ignoreCase = true) -> false
        isDeveloperPropertiesDisabledByEnvironment(ciValue) -> false
        else -> true
    }

fun loadDeveloperProperties(
    enabled: Boolean,
    fileExists: Boolean,
    reader: () -> Properties,
): Map<String, String> {
    if (!enabled || !fileExists) return emptyMap()
    val properties = reader()
    return developerPropertyKeys.associateWith { key -> properties.getProperty(key).orEmpty() }
}

val developerPropertiesEnabled =
    providers.provider {
        val forceDisableValue = providers.environmentVariable("JELLYSCOPE_FORCE_DISABLE_DEVELOPER_PROPERTIES").orNull
        if (isDeveloperPropertiesDisabledByEnvironment(forceDisableValue)) {
            false
        } else {
            developerPropertiesAreEnabled(
                forceDisableValue = forceDisableValue,
                requestedValue = providers.gradleProperty("jellyscopeDeveloperPropertiesEnabled").orNull,
                ciValue = providers.environmentVariable("CI").orNull,
            )
        }
    }
val developerProperties =
    developerPropertiesEnabled.map { enabled ->
        if (!enabled) {
            emptyMap()
        } else {
            loadDeveloperProperties(
                enabled = true,
                fileExists = developerPropertiesFile.asFile.isFile,
            ) {
                Properties().apply {
                    developerPropertiesFile.asFile.inputStream().use(::load)
                }
            }
        }
    }

extra["developerPropertiesFile"] = developerPropertiesFile
extra["developerPropertiesEnabled"] = developerPropertiesEnabled
extra["developerProperties"] = developerProperties

tasks.register("verifyDeveloperPropertiesIsolation") {
    group = "verification"
    description = "Verifies developer-property gating and in-memory loading without reading local credentials."
    doLast {
        check(
            !developerPropertiesAreEnabled(
                forceDisableValue = "true",
                requestedValue = "true",
                ciValue = "false",
            ),
        )
        check(
            !developerPropertiesAreEnabled(
                forceDisableValue = null,
                requestedValue = "false",
                ciValue = "false",
            ),
        )
        check(
            !developerPropertiesAreEnabled(
                forceDisableValue = null,
                requestedValue = null,
                ciValue = "TrUe",
            ),
        )
        check(
            !developerPropertiesAreEnabled(
                forceDisableValue = null,
                requestedValue = null,
                ciValue = "1",
            ),
        )
        check(
            developerPropertiesAreEnabled(
                forceDisableValue = null,
                requestedValue = null,
                ciValue = "false",
            ),
        )

        var readerInvoked = false
        val reader = {
            readerInvoked = true
            Properties()
        }
        val forceDisabled =
            developerPropertiesAreEnabled(
                forceDisableValue = "true",
                requestedValue = "true",
                ciValue = "false",
            )
        check(loadDeveloperProperties(enabled = forceDisabled, fileExists = true, reader = reader).isEmpty())
        check(!readerInvoked)
        check(loadDeveloperProperties(enabled = false, fileExists = true, reader = reader).isEmpty())
        check(!readerInvoked)
        check(loadDeveloperProperties(enabled = true, fileExists = false, reader = reader).isEmpty())
        check(!readerInvoked)

        val inMemoryProperties =
            Properties().apply {
                setProperty("devServerUrl", "https://developer.example")
                setProperty("devUsername", "developer")
                setProperty("devPassword", "password")
                setProperty("openSubtitlesApiKey", "subtitle-key")
                setProperty("unsupportedKey", "must-not-leak")
            }
        val normalized =
            loadDeveloperProperties(
                enabled = true,
                fileExists = true,
                reader = { inMemoryProperties },
            )
        check(normalized.keys == developerPropertyKeys.toSet())
        check(
            normalized ==
                mapOf(
                    "devServerUrl" to "https://developer.example",
                    "devUsername" to "developer",
                    "devPassword" to "password",
                    "openSubtitlesApiKey" to "subtitle-key",
                ),
        )
    }
}

val releaseLicenseMetadataDir = layout.buildDirectory.dir("generated/release-license-metadata")
val androidReleaseLicenseAssetsDir = layout.buildDirectory.dir("generated/android-release-license-assets")

tasks.register<Exec>("prepareReleaseLicenseMetadata") {
    group = "distribution"
    description = "Prepares source, license, and third-party metadata for release packages."
    commandLine(
        rootProject.layout.projectDirectory
            .file("scripts/prepare-release-license-metadata.sh")
            .asFile
            .absolutePath,
        releaseLicenseMetadataDir.get().asFile.absolutePath,
    )
    outputs.dir(releaseLicenseMetadataDir)
    outputs.upToDateWhen { false }
}

tasks.register<Exec>("verifyReleaseLicenseMetadata") {
    group = "verification"
    description = "Verifies release source, license, and third-party metadata."
    dependsOn("prepareReleaseLicenseMetadata")
    commandLine(
        rootProject.layout.projectDirectory
            .file("scripts/verify-release-license-metadata.sh")
            .asFile
            .absolutePath,
        releaseLicenseMetadataDir.get().asFile.absolutePath,
    )
}

tasks.register<Exec>("verifyCleanSourceReleaseBinding") {
    group = "verification"
    description = "Verifies that release source metadata is bound to a clean Git candidate."
    dependsOn("prepareReleaseLicenseMetadata")
    commandLine(
        rootProject.layout.projectDirectory
            .file("scripts/verify-release-license-metadata.sh")
            .asFile
            .absolutePath,
        releaseLicenseMetadataDir.get().asFile.absolutePath,
        "--require-clean",
    )
}

tasks.register<Exec>("verifyBinaryLicenseMetadataReadiness") {
    group = "verification"
    description = "Fails until the binary candidate has no unresolved license-inventory entries."
    dependsOn("prepareReleaseLicenseMetadata")
    commandLine(
        rootProject.layout.projectDirectory
            .file("scripts/verify-release-license-metadata.sh")
            .asFile
            .absolutePath,
        releaseLicenseMetadataDir.get().asFile.absolutePath,
        "--require-binary-ready",
    )
}

tasks.register<Exec>("verifyAndroidBinaryLicenseMetadataReadiness") {
    group = "verification"
    description = "Verifies the Android platform dependency inventory."
    dependsOn("prepareReleaseLicenseMetadata")
    commandLine(
        rootProject.layout.projectDirectory
            .file("scripts/verify-release-license-metadata.sh")
            .asFile
            .absolutePath,
        releaseLicenseMetadataDir.get().asFile.absolutePath,
        "--require-android-ready",
    )
}

tasks.register<Exec>("verifyIosBinaryLicenseMetadataReadiness") {
    group = "verification"
    description = "Verifies the iOS platform dependency inventory."
    dependsOn("prepareReleaseLicenseMetadata")
    commandLine(
        rootProject.layout.projectDirectory
            .file("scripts/verify-release-license-metadata.sh")
            .asFile
            .absolutePath,
        releaseLicenseMetadataDir.get().asFile.absolutePath,
        "--require-ios-ready",
    )
}

tasks.register<Exec>("verifyMacosArm64BinaryLicenseMetadataReadiness") {
    group = "verification"
    description = "Verifies the macOS arm64 platform dependency inventory."
    dependsOn(":desktop-app:verifyDesktopJvmRuntimeLicenseInventory")
    dependsOn("prepareReleaseLicenseMetadata")
    commandLine(
        rootProject.layout.projectDirectory
            .file("scripts/verify-release-license-metadata.sh")
            .asFile
            .absolutePath,
        releaseLicenseMetadataDir.get().asFile.absolutePath,
        "--require-macos-arm64-ready",
    )
}

tasks.register<Sync>("prepareAndroidReleaseLicenseAssets") {
    group = "distribution"
    description = "Stages release license metadata under the Android license-metadata asset directory."
    dependsOn("prepareReleaseLicenseMetadata")
    from(releaseLicenseMetadataDir) {
        into("license-metadata")
    }
    into(androidReleaseLicenseAssetsDir)
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            exclude("**/generated/**")
            exclude { element -> element.file.path.contains("/build/generated/") }
            // android-libmpv vendors the pinned upstream wrapper source with
            // three deliberate patches; upstream formatting must be preserved
            // so the source guard's diff against the pinned commit stays exact.
            exclude { element -> element.file.path.contains("android-libmpv/src/main/java/dev/jdtech/mpv/") }
        }
    }

    // Apply the shared Compose stability config to every module that compiles
    // Compose, so immutable domain models participate in normal skip decisions.
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(
                rootProject.layout.projectDirectory.file("compose-stability.conf"),
            )
        }
    }
}
