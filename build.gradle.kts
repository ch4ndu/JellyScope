// SPDX-License-Identifier: MPL-2.0

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
    description = "Fails until a clean binary candidate has no unresolved license-inventory entries."
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
    description = "Verifies a clean Android candidate and its platform dependency inventory."
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
    description = "Verifies a clean iOS candidate and its platform dependency inventory."
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
    description = "Verifies a clean macOS arm64 candidate and its platform-specific dependency inventory."
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
