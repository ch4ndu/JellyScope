// SPDX-License-Identifier: MPL-2.0

import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.process.JavaForkOptions
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
}

val desktopVersion = providers.gradleProperty("jellyscope.desktop.version").orElse("0.1.0")
val desktopPackageVersion = providers.gradleProperty("jellyscope.desktop.packageVersion").orElse("1.0.0")
val macosSigningDirectory = File(System.getProperty("user.home"), "Private/Keystores")
val macosSigningPropertiesFile = macosSigningDirectory.resolve("macos-signing.properties")
val macosSigningProperties =
    Properties().apply {
        if (macosSigningPropertiesFile.isFile) {
            macosSigningPropertiesFile.inputStream().use { load(it) }
        }
    }

fun macosCredential(
    propertyKey: String,
    environmentKey: String,
): String =
    macosSigningProperties.getProperty(propertyKey)
        ?: System.getenv(environmentKey).orEmpty()

val macosSigningIdentity = macosCredential("signingIdentity", "APPLE_SIGNING_IDENTITY")
val macosTeamId = macosCredential("teamId", "APPLE_TEAM_ID")
val macosAppleId = macosCredential("appleId", "APPLE_ID")
val macosAppSpecificPassword =
    macosCredential("appleAppSpecificPassword", "APPLE_APP_SPECIFIC_PASSWORD")
val macosNotaryKeyId = macosCredential("notaryKeyId", "APPLE_NOTARY_KEY_ID")
val macosNotaryIssuerId = macosCredential("notaryIssuerId", "APPLE_NOTARY_ISSUER_ID")
val macosNotaryPrivateKeyPath =
    macosCredential("notaryPrivateKeyPath", "APPLE_NOTARY_PRIVATE_KEY_PATH")
val macosSigningConfigured = macosSigningIdentity.isNotBlank()
val macosAppPasswordNotarizationConfigured =
    macosAppleId.isNotBlank() && macosAppSpecificPassword.isNotBlank() && macosTeamId.isNotBlank()
val macosApiKeyNotarizationConfigured =
    macosNotaryKeyId.isNotBlank() &&
        macosNotaryIssuerId.isNotBlank() &&
        macosNotaryPrivateKeyPath.isNotBlank()
val desktopStaticAppResourcesDir = layout.projectDirectory.dir("src/main/desktopResources")
val preparedDesktopAppResourcesDir = layout.buildDirectory.dir("preparedDesktopAppResources")
val desktopMpvManifestFile =
    rootProject.layout.projectDirectory.file("scripts/desktop-mpv-bundle/manifest-iina-1.4.0-arm64.txt")
val bundledMpvLibraryNames =
    desktopMpvManifestFile.asFile
        .readLines()
        .mapNotNull { line ->
            Regex("^file=([^\\s]+)$").matchEntire(line)?.groupValues?.get(1)
        }.toSet()
require(bundledMpvLibraryNames.size == 69) {
    "Desktop mpv manifest must parse to 69 pinned library names."
}
val desktopInitialPlaybackItemId =
    providers
        .systemProperty("jellyscope.desktop.initialPlaybackItemId")
        .map(String::trim)
        .orElse("")
val desktopInitialDetailItemId =
    providers
        .systemProperty("jellyscope.desktop.initialDetailItemId")
        .map(String::trim)
        .orElse("")
val desktopDeepLink =
    providers
        .systemProperty("jellyscope.desktop.deepLink")
        .map(String::trim)
        .orElse("")
val desktopPlaybackProbeLogEnabled =
    providers
        .systemProperty("jellyscope.desktop.playbackProbeLog")
        .map { value -> value.toBooleanStrictOrNull() == true }
        .orElse(false)
val desktopForceMpvOpenGlSurface =
    providers
        .systemProperty("jellyscope.desktop.forceMpvOpenGlSurface")
        .map { value -> value.toBooleanStrictOrNull() == true }
        .orElse(false)

// Compose Desktop copies ONLY the platform-scoped subdirectories of
// appResourcesRootDir (`common`, `<os>`, `<os>-<arch>`) into the packaged
// application; anything left at the root is silently dropped. Staging the
// vendored libmpv stack at the root therefore produced a release that still
// depended on a system libmpv. The pinned stack is arm64-specific, so it belongs
// in `<os>-<arch>`.
val composeAppResourcesPlatformDir =
    run {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val os =
            when {
                osName.contains("mac") || osName.contains("darwin") -> "macos"
                osName.contains("win") -> "windows"
                else -> "linux"
            }
        val arch =
            when (System.getProperty("os.arch").orEmpty().lowercase()) {
                "aarch64", "arm64" -> "arm64"
                else -> "x64"
            }
        "$os-$arch"
    }
val preparedDesktopAppResourcesPlatformDir =
    preparedDesktopAppResourcesDir.map { root -> root.dir(composeAppResourcesPlatformDir) }
val desktopMacInteropJvmArgs =
    listOf(
        "-Dcompose.interop.blending=true",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
    )
val isMacOsDesktopBuild = composeAppResourcesPlatformDir.startsWith("macos-")

val isAppleSiliconMacDesktopBuild = composeAppResourcesPlatformDir == "macos-arm64"
val desktopHotReloadJvmArgs =
    buildList {
        add("-Djellyscope.version=${desktopVersion.get()}")
        add("-Djellyscope.debug=true")
        if (isMacOsDesktopBuild) {
            add("-Dcompose.application.resources.dir=${preparedDesktopAppResourcesPlatformDir.get().asFile.absolutePath}")
            addAll(desktopMacInteropJvmArgs)
        }
        desktopInitialPlaybackItemId
            .get()
            .takeIf(String::isNotBlank)
            ?.let { itemId -> add("-Djellyscope.desktop.initialPlaybackItemId=$itemId") }
        desktopInitialDetailItemId
            .get()
            .takeIf(String::isNotBlank)
            ?.let { itemId -> add("-Djellyscope.desktop.initialDetailItemId=$itemId") }
        desktopDeepLink
            .get()
            .takeIf(String::isNotBlank)
            ?.let { deepLink -> add("-Djellyscope.desktop.deepLink=$deepLink") }
        if (desktopPlaybackProbeLogEnabled.get()) {
            add("-Djellyscope.desktop.playbackProbeLog=true")
        }
        if (desktopForceMpvOpenGlSurface.get()) {
            add("-Djellyscope.desktop.forceMpvOpenGlSurface=true")
        }
    }
val prepareDesktopAppResources =
    tasks.register<Sync>("prepareDesktopAppResources") {
        from(desktopStaticAppResourcesDir)
        from(rootProject.layout.buildDirectory.dir("generated/release-license-metadata")) {
            into("license-metadata")
        }
        into(preparedDesktopAppResourcesPlatformDir)
        dependsOn(rootProject.tasks.named("prepareReleaseLicenseMetadata"))
    }
val prepareDesktopMpvBundle =
    tasks.register<Exec>("prepareDesktopMpvBundle") {
        group = "build"
        description = "Fetches, verifies, and prepares the pinned IINA arm64 libmpv runtime."
        dependsOn(prepareDesktopAppResources)
        inputs.files(
            rootProject.layout.projectDirectory.file("scripts/fetch-desktop-mpv-runtime.sh"),
            rootProject.layout.projectDirectory.file("scripts/prepare-desktop-mpv-bundle.sh"),
            desktopMpvManifestFile,
            rootProject.layout.projectDirectory.file("scripts/desktop-mpv-bundle/ATTRIBUTION.md"),
        )
        inputs.dir(rootProject.layout.projectDirectory.dir("scripts/desktop-mpv-bundle/licenses"))
        outputs.dir(preparedDesktopAppResourcesDir)
        outputs.upToDateWhen { false }
        doFirst {
            if (!isAppleSiliconMacDesktopBuild) {
                throw GradleException("The pinned desktop mpv runtime supports macOS arm64 only.")
            }
        }
        commandLine(
            rootProject.layout.projectDirectory
                .file("scripts/prepare-desktop-mpv-bundle.sh")
                .asFile
                .absolutePath,
            preparedDesktopAppResourcesPlatformDir.get().asFile.absolutePath,
        )
    }
val prepareDesktopVlcBundle =
    tasks.register<Exec>("prepareDesktopVlcBundle") {
        group = "build"
        description = "Copies the audited VLC 3 runtime into macOS (Apple Silicon) desktop app resources."
        onlyIf { isAppleSiliconMacDesktopBuild }
        dependsOn(prepareDesktopAppResources)
        inputs.files(
            rootProject.layout.projectDirectory.file("scripts/prepare-desktop-vlc-bundle.sh"),
            rootProject.layout.projectDirectory.file("scripts/vlc-bundle/manifest-3.0.23-arm64.txt"),
            rootProject.layout.projectDirectory.file("scripts/vlc-bundle/ATTRIBUTION.md"),
            rootProject.layout.projectDirectory.file("scripts/vlc-bundle/GPL-2.0.txt"),
            rootProject.layout.projectDirectory.file("scripts/vlc-bundle/LGPL-2.1.txt"),
        )
        commandLine(
            rootProject.layout.projectDirectory
                .file("scripts/prepare-desktop-vlc-bundle.sh")
                .asFile
                .absolutePath,
            preparedDesktopAppResourcesPlatformDir.get().asFile.absolutePath,
        )
    }
val verifyDesktopMpvBundle =
    tasks.register("verifyDesktopMpvBundle") {
        group = "verification"
        description = "Checks that desktop app resources contain a bundled libmpv runtime before packaging."
        dependsOn(prepareDesktopMpvBundle)
        inputs.dir(preparedDesktopAppResourcesDir)

        doLast {
            val bundleDir = preparedDesktopAppResourcesPlatformDir.get().asFile
            val bundled =
                bundleDir
                    .listFiles()
                    ?.filter(File::isFile)
                    ?.map(File::getName)
                    ?.toSet()
                    .orEmpty()
            val missing = bundledMpvLibraryNames - bundled
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Desktop release packaging is missing pinned libmpv files in " +
                        "${bundleDir.relativeTo(projectDir)}: ${missing.sorted().joinToString()}.",
                )
            }
        }
    }

// Check each app-image variant in its own output directory so a stale image
// from another variant cannot affect the package task being verified.
fun registerVerifyPackagedDesktopMpvBundle(
    taskName: String,
    binariesSubdir: String,
) = tasks.register(taskName) {
    group = "verification"
    description = "Checks that the packaged $binariesSubdir app image contains the complete libmpv runtime."

    doLast {
        val appImageRoot =
            layout.buildDirectory
                .dir("compose/binaries/$binariesSubdir")
                .get()
                .asFile
        val appDirs =
            appImageRoot
                .walkTopDown()
                .filter { file -> file.isDirectory && file.name.endsWith(".app") }
                .toList()
        if (appDirs.isEmpty()) {
            throw GradleException("No packaged .app image found under ${appImageRoot.relativeTo(projectDir)}.")
        }
        appDirs.forEach { appDir ->
            val entrypoint =
                appDir
                    .walkTopDown()
                    .firstOrNull { file -> file.isFile && file.name == "libmpv.2.dylib" }
                    ?: throw GradleException("Packaged app ${appDir.name} does not contain libmpv.2.dylib.")
            val bundled =
                entrypoint.parentFile
                    .listFiles()
                    ?.filter(File::isFile)
                    ?.map(File::getName)
                    ?.toSet()
                    .orEmpty()
            val missing = bundledMpvLibraryNames - bundled
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Packaged app ${appDir.name} is missing pinned libmpv files: " +
                        missing.sorted().joinToString() + ".",
                )
            }
        }
    }
}

val verifyPackagedDesktopMpvBundleMain =
    registerVerifyPackagedDesktopMpvBundle("verifyPackagedDesktopMpvBundleMain", "main")
val verifyPackagedDesktopMpvBundleMainRelease =
    registerVerifyPackagedDesktopMpvBundle("verifyPackagedDesktopMpvBundleMainRelease", "main-release")

fun registerVerifyPackagedDesktopLicenseMetadata(
    taskName: String,
    binariesSubdir: String,
) = tasks.register(taskName) {
    group = "verification"
    description = "Checks that the packaged $binariesSubdir app image contains source and license metadata."

    doLast {
        val appImageRoot =
            layout.buildDirectory
                .dir("compose/binaries/$binariesSubdir")
                .get()
                .asFile
        val appDirs =
            appImageRoot
                .walkTopDown()
                .filter { file -> file.isDirectory && file.name.endsWith(".app") }
                .toList()
        if (appDirs.isEmpty()) {
            throw GradleException("No packaged .app image found under ${appImageRoot.relativeTo(projectDir)}.")
        }
        appDirs.forEach { appDir ->
            val metadataDirs =
                appDir
                    .walkTopDown()
                    .filter { file -> file.isDirectory && file.name == "license-metadata" }
                    .toList()
            if (metadataDirs.size != 1) {
                throw GradleException(
                    "Packaged app ${appDir.name} must contain exactly one license-metadata directory; " +
                        "found ${metadataDirs.size}.",
                )
            }
            val packagedNames =
                metadataDirs
                    .single()
                    .walkTopDown()
                    .filter(File::isFile)
                    .map(File::getName)
                    .toSet()
            val missing = packagedLicenseMetadataFileNames - packagedNames
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Packaged app ${appDir.name} is missing release license metadata: " +
                        missing.sorted().joinToString(),
                )
            }
            val exit =
                ProcessBuilder(
                    rootProject.layout.projectDirectory
                        .file("scripts/verify-release-license-metadata.sh")
                        .asFile
                        .absolutePath,
                    metadataDirs.single().absolutePath,
                ).inheritIO().start().waitFor()
            if (exit != 0) {
                throw GradleException("Packaged app ${appDir.name} has invalid release license metadata.")
            }
        }
    }
}

val verifyPackagedDesktopLicenseMetadataMain =
    registerVerifyPackagedDesktopLicenseMetadata("verifyPackagedDesktopLicenseMetadataMain", "main")
val verifyPackagedDesktopLicenseMetadataMainRelease =
    registerVerifyPackagedDesktopLicenseMetadata("verifyPackagedDesktopLicenseMetadataMainRelease", "main-release")

// Same app-image check as libmpv: staging-only verification cannot prove the VLC
// bundle survived Compose's platform-scoped resource copy. Unlike the libmpv
// verifier, each variant checks ONLY its own binaries subdirectory — a stale
// pre-VLC image from the other variant must not fail this build.
fun registerVerifyPackagedDesktopVlcBundle(
    taskName: String,
    binariesSubdir: String,
) = tasks.register(taskName) {
    group = "verification"
    description = "Checks that the packaged $binariesSubdir app image actually contains the bundled VLC runtime."
    onlyIf { isAppleSiliconMacDesktopBuild }

    doLast {
        val appImageRoot =
            layout.buildDirectory
                .dir("compose/binaries/$binariesSubdir")
                .get()
                .asFile
        val appDirs =
            appImageRoot
                .walkTopDown()
                .filter { file -> file.isDirectory && file.name.endsWith(".app") }
                .toList()
        if (appDirs.isEmpty()) {
            throw GradleException("No packaged .app image found under ${appImageRoot.relativeTo(projectDir)}.")
        }
        appDirs.forEach { appDir ->
            val bundled =
                appDir
                    .walkTopDown()
                    .filter { file -> file.isFile && file.name in bundledVlcRequiredFileNames }
                    .map { file -> file.name }
                    .toSet()
            if (!bundled.containsAll(bundledVlcRequiredFileNames)) {
                throw GradleException(
                    "Packaged app ${appDir.name} is missing bundled VLC files: " +
                        (bundledVlcRequiredFileNames - bundled).sorted().joinToString() +
                        ". Compose Desktop only copies the platform-scoped subdirectories of " +
                        "appResourcesRootDir (common / <os> / <os>-<arch>).",
                )
            }
        }
    }
}

val verifyPackagedDesktopVlcBundleMain =
    registerVerifyPackagedDesktopVlcBundle("verifyPackagedDesktopVlcBundleMain", "main")
val verifyPackagedDesktopVlcBundleMainRelease =
    registerVerifyPackagedDesktopVlcBundle("verifyPackagedDesktopVlcBundleMainRelease", "main-release")

// Notarization requires every bundled Mach-O to carry a Developer ID + hardened-runtime
// signature. Compose signs the app bundle but not the vendored libmpv dylibs, so sign them
// in the prepared resources (inside-out) before packaging copies them into the .app. Gated on
// credentials so credential-free packaging still produces an unsigned dmg.
val signBundledMpvDylibs =
    tasks.register("signBundledMpvDylibs") {
        group = "distribution"
        description = "Deep-signs every bundled native dylib (libmpv and VLC) with the Developer ID identity before packaging."
        dependsOn(prepareDesktopMpvBundle)
        // The signing walk covers the whole prepared-resources tree, so the VLC
        // bundle must exist before it runs or its dylibs ship unsigned.
        dependsOn(prepareDesktopVlcBundle)
        onlyIf { macosSigningConfigured }
        doLast {
            preparedDesktopAppResourcesDir
                .get()
                .asFile
                .walkTopDown()
                .filter { file -> file.isFile && file.name.endsWith(".dylib") }
                .forEach { dylib ->
                    val exit =
                        ProcessBuilder(
                            "codesign",
                            "--force",
                            "--options",
                            "runtime",
                            "--timestamp",
                            "--sign",
                            macosSigningIdentity,
                            dylib.absolutePath,
                        ).inheritIO().start().waitFor()
                    if (exit != 0) {
                        throw GradleException("codesign failed for ${dylib.name} (exit $exit).")
                    }
                }
        }
    }

version = desktopVersion.get()

kotlin {
    jvmToolchain(
        libs.versions.jdkToolchain
            .get()
            .toInt(),
    )
}

compose.desktop {
    application {
        mainClass = "com.jellyscope.desktop.MainKt"
        jvmArgs += "-Djellyscope.version=${desktopVersion.get()}"
        if (isMacOsDesktopBuild) {
            jvmArgs += desktopMacInteropJvmArgs
        }
        desktopInitialPlaybackItemId
            .get()
            .takeIf { value -> value.isNotBlank() }
            ?.let { itemId ->
                jvmArgs += "-Djellyscope.desktop.initialPlaybackItemId=$itemId"
            }
        desktopInitialDetailItemId
            .get()
            .takeIf { value -> value.isNotBlank() }
            ?.let { itemId ->
                jvmArgs += "-Djellyscope.desktop.initialDetailItemId=$itemId"
            }
        desktopDeepLink
            .get()
            .takeIf { value -> value.isNotBlank() }
            ?.let { deepLink ->
                jvmArgs += "-Djellyscope.desktop.deepLink=$deepLink"
            }
        if (desktopPlaybackProbeLogEnabled.get()) {
            jvmArgs += "-Djellyscope.desktop.playbackProbeLog=true"
        }
        if (desktopForceMpvOpenGlSurface.get()) {
            jvmArgs += "-Djellyscope.desktop.forceMpvOpenGlSurface=true"
        }

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-desktop-release.pro"))
            // Keep shrinking enabled, but avoid release-only optimizer rewrites
            // around Room/SQLite, JNA/libmpv, Skiko, Ktor, Coil, and provider code.
            optimize.set(false)
        }

        nativeDistributions {
            packageName = "JellyScope"
            packageVersion = desktopPackageVersion.get()
            targetFormats(TargetFormat.Dmg)
            appResourcesRootDir.set(preparedDesktopAppResourcesDir)
            macOS {
                bundleID = "com.jellyscope.desktop"
                minimumSystemVersion = "13.0"
                iconFile.set(project.file("icons/jellyscope.icns"))
                infoPlist {
                    extraKeysRawXml =
                        """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>com.jellyscope.desktop.details</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>jellyscope</string>
                                </array>
                            </dict>
                        </array>
                        """.trimIndent()
                }
                signing {
                    sign.set(macosSigningConfigured)
                    identity.set(macosSigningIdentity.takeIf { macosSigningConfigured })
                    entitlementsFile.set(project.file("entitlements.plist"))
                    runtimeEntitlementsFile.set(project.file("entitlements.plist"))
                }
                if (macosAppPasswordNotarizationConfigured) {
                    notarization {
                        appleID.set(macosAppleId)
                        password.set(macosAppSpecificPassword)
                        teamID.set(macosTeamId)
                    }
                }
            }
            windows {
                iconFile.set(project.file("icons/jellyscope.ico"))
            }
            linux {
                iconFile.set(project.file("icons/jellyscope.png"))
            }
        }
    }
}

dependencies {
    implementation(project(":shared-ui"))
    implementation(project(":shared-core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.koin.core)
    // Supplies Dispatchers.Main on the AWT/Swing event thread (Compose Desktop);
    // without it viewModelScope has no Main dispatcher on plain JVM.
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
}

val desktopJvmRuntimeLicenseInventoryFile =
    rootProject.layout.projectDirectory.file("distribution/DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv")
val verifyDesktopJvmRuntimeLicenseInventory =
    tasks.register("verifyDesktopJvmRuntimeLicenseInventory") {
        group = "verification"
        description = "Verifies every resolved desktop JVM runtime component against the reviewed inventory."
        inputs.file(desktopJvmRuntimeLicenseInventoryFile)

        doLast {
            if (!isAppleSiliconMacDesktopBuild) {
                throw GradleException(
                    "The reviewed desktop JVM runtime inventory applies only to macOS arm64.",
                )
            }
            val inventoryLines = desktopJvmRuntimeLicenseInventoryFile.asFile.readLines()
            val expectedHeader =
                "family\tgradle-group\taccepted-components\tpublished-license\tupstream-source"
            if (inventoryLines.firstOrNull() != expectedHeader) {
                throw GradleException("Desktop JVM runtime inventory has an invalid header.")
            }

            val reviewedComponentsByGroup = mutableMapOf<String, Set<String>>()
            inventoryLines.drop(1).forEachIndexed { index, line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                val columns = line.split('\t')
                if (columns.size != 5 || columns.any(String::isBlank)) {
                    throw GradleException("Invalid desktop JVM runtime inventory row ${index + 2}.")
                }
                val group = columns[1]
                val components =
                    columns[2]
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .toSet()
                if (components.isEmpty()) {
                    throw GradleException(
                        "Desktop JVM runtime inventory row ${index + 2} has no accepted component.",
                    )
                }
                if (reviewedComponentsByGroup.put(group, components) != null) {
                    throw GradleException("Desktop JVM runtime inventory repeats Gradle group $group.")
                }
            }

            val resolvedComponents =
                configurations
                    .getByName("runtimeClasspath")
                    .incoming
                    .resolutionResult
                    .allComponents
                    .mapNotNull { component ->
                        (component.id as? ModuleComponentIdentifier)?.let { id ->
                            Triple(id.group, id.module, id.version)
                        }
                    }.toSet()
            if (resolvedComponents.isEmpty()) {
                throw GradleException("Desktop runtime classpath resolved no external components.")
            }
            val unreviewed =
                resolvedComponents
                    .filter { (group, module, version) ->
                        val reviewed = reviewedComponentsByGroup[group]
                        reviewed == null || "$module:$version" !in reviewed
                    }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            if (unreviewed.isNotEmpty()) {
                val details =
                    unreviewed.joinToString(separator = "\n") { (group, module, version) ->
                        val accepted = reviewedComponentsByGroup[group]
                        if (accepted == null) {
                            "- $group:$module:$version (family is absent from the reviewed inventory)"
                        } else {
                            "- $group:$module:$version " +
                                "(accepted components: ${accepted.sorted().joinToString()})"
                        }
                    }
                throw GradleException("Unreviewed desktop JVM runtime components:\n$details")
            }

            logger.lifecycle(
                "Verified ${resolvedComponents.size} desktop JVM runtime components across " +
                    "${resolvedComponents.map { it.first }.toSet().size} reviewed families.",
            )
        }
    }

val macosReleaseDmgOutputDir = layout.buildDirectory.dir("compose/binaries/main-release/dmg")
val stageMacosReleaseSourceArtifacts =
    tasks.register<Exec>("stageMacosReleaseSourceArtifacts") {
        group = "distribution"
        description = "Stages the HEAD JellyScope and pinned VLC sources beside the macOS release DMG."
        onlyIf { isAppleSiliconMacDesktopBuild }
        inputs.files(
            rootProject.layout.projectDirectory.file("scripts/prepare-macos-release-sources.sh"),
            rootProject.layout.projectDirectory.file("scripts/macos-source-bundle/manifest-macos-arm64.txt"),
        )
        outputs.upToDateWhen { false }
        doFirst {
            val outputDir = macosReleaseDmgOutputDir.get().asFile
            val dmgFiles = outputDir.listFiles()?.filter { file -> file.isFile && file.extension == "dmg" }.orEmpty()
            if (dmgFiles.isEmpty()) {
                throw GradleException("No macOS release DMG was found under ${outputDir.relativeTo(projectDir)}.")
            }
            commandLine(
                rootProject.layout.projectDirectory
                    .file("scripts/prepare-macos-release-sources.sh")
                    .asFile
                    .absolutePath,
                outputDir.absolutePath,
            )
        }
    }

val notarizeReleaseDmgWithApiKey =
    tasks.register<Exec>("notarizeReleaseDmgWithApiKey") {
        group = "distribution"
        description = "Submits and staples the signed macOS DMG with App Store Connect API credentials."
        onlyIf { macosSigningConfigured && macosApiKeyNotarizationConfigured }
        dependsOn("packageReleaseDistributionForCurrentOS")

        var packageFile: File? = null
        doFirst {
            packageFile =
                layout.buildDirectory
                    .dir("compose/binaries")
                    .get()
                    .asFile
                    .walkTopDown()
                    .firstOrNull { file -> file.isFile && file.extension.equals("dmg", ignoreCase = true) }
                    ?: throw GradleException("No macOS DMG was found for API-key notarization.")
            val dmg = packageFile
            if (dmg == null) {
                throw GradleException("No macOS DMG was found for API-key notarization.")
            }
            val configuredPrivateKey = File(macosNotaryPrivateKeyPath)
            val privateKey =
                if (configuredPrivateKey.isAbsolute) {
                    configuredPrivateKey
                } else {
                    macosSigningDirectory.resolve(macosNotaryPrivateKeyPath)
                }
            commandLine(
                "xcrun",
                "notarytool",
                "submit",
                dmg.absolutePath,
                "--wait",
                "--key",
                privateKey.absolutePath,
                "--key-id",
                macosNotaryKeyId,
                "--issuer",
                macosNotaryIssuerId,
            )
        }
        doLast {
            val dmg = packageFile
            if (dmg != null) {
                val staple =
                    ProcessBuilder("xcrun", "stapler", "staple", dmg.absolutePath)
                        .inheritIO()
                        .start()
                        .waitFor()
                if (staple != 0) {
                    throw GradleException("Stapling the notarized DMG failed with exit code $staple.")
                }
            }
        }
    }

afterEvaluate {
    tasks.named("hotRun").configure {
        // Compose Hot Reload owns a separate JavaExec/argfile and does not
        // inherit Compose Desktop's application JVM arguments. The deprecated
        // runHot alias resolves to this same launch task. Hot Reload 1.1.1
        // writes both allJvmArgs and jvmArgs to its generated argfile, so these
        // custom values appear twice there even though this task list is unique.
        if (isMacOsDesktopBuild) {
            dependsOn(prepareDesktopMpvBundle)
        }
        val launch = this as JavaForkOptions
        launch.setJvmArgs((launch.jvmArgs.orEmpty() + desktopHotReloadJvmArgs).distinct())
    }
    tasks.named<JavaExec>("run").configure {
        // Compose Desktop registers and finalizes its run task after the build
        // script, so attach development-only flags immediately before execution.
        if (isMacOsDesktopBuild) {
            dependsOn(prepareDesktopMpvBundle)
        }
        doFirst {
            if (isMacOsDesktopBuild) {
                jvmArgs(
                    "-Dcompose.application.resources.dir=" +
                        preparedDesktopAppResourcesPlatformDir.get().asFile.absolutePath,
                )
            }
            // The development run task is the debug desktop entry; release launchers omit this flag.
            jvmArgs("-Djellyscope.debug=true")
            desktopInitialPlaybackItemId
                .get()
                .takeIf { value -> value.isNotBlank() }
                ?.let { itemId ->
                    jvmArgs("-Djellyscope.desktop.initialPlaybackItemId=$itemId")
                }
            desktopInitialDetailItemId
                .get()
                .takeIf { value -> value.isNotBlank() }
                ?.let { itemId ->
                    jvmArgs("-Djellyscope.desktop.initialDetailItemId=$itemId")
                }
            desktopDeepLink
                .get()
                .takeIf { value -> value.isNotBlank() }
                ?.let { deepLink ->
                    jvmArgs("-Djellyscope.desktop.deepLink=$deepLink")
                }
            if (desktopPlaybackProbeLogEnabled.get()) {
                jvmArgs("-Djellyscope.desktop.playbackProbeLog=true")
            }
            if (desktopForceMpvOpenGlSurface.get()) {
                jvmArgs("-Djellyscope.desktop.forceMpvOpenGlSurface=true")
            }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("notarize") && name != "notarizeReleaseDmgWithApiKey") {
        onlyIf { macosAppPasswordNotarizationConfigured }
    }
    if (name in desktopPackageTaskNamesRequiringMpv) {
        dependsOn(verifyDesktopMpvBundle)
        dependsOn(signBundledMpvDylibs)
        if (isMacOsDesktopBuild) {
            dependsOn(prepareDesktopVlcBundle)
        }
    }
    if (name == "packageReleaseDmg") {
        dependsOn(rootProject.tasks.named("verifyMacosArm64BinaryLicenseMetadataReadiness"))
        doLast {
            if (macosSigningConfigured) {
                val dmg =
                    macosReleaseDmgOutputDir.get().asFile.resolve(
                        "JellyScope-${desktopPackageVersion.get()}.dmg",
                    )
                if (!dmg.isFile) {
                    throw GradleException("No macOS release DMG was found at ${dmg.relativeTo(projectDir)}.")
                }
                val signing =
                    ProcessBuilder(
                        "codesign",
                        "--force",
                        "--timestamp",
                        "--sign",
                        macosSigningIdentity,
                        dmg.absolutePath,
                    ).inheritIO()
                        .start()
                        .waitFor()
                if (signing != 0) {
                    throw GradleException("Signing the macOS release DMG failed with exit code $signing.")
                }
            }
        }
        finalizedBy(stageMacosReleaseSourceArtifacts)
    }
    if (name == "createDistributable") {
        finalizedBy(verifyPackagedDesktopMpvBundleMain)
        finalizedBy(verifyPackagedDesktopLicenseMetadataMain)
        finalizedBy(verifyPackagedDesktopVlcBundleMain)
    }
    if (name == "createReleaseDistributable") {
        finalizedBy(verifyPackagedDesktopMpvBundleMainRelease)
        finalizedBy(verifyPackagedDesktopLicenseMetadataMainRelease)
        finalizedBy(verifyPackagedDesktopVlcBundleMainRelease)
    }
    if (name == "prepareAppResources") {
        dependsOn(prepareDesktopMpvBundle)
        if (isMacOsDesktopBuild) {
            dependsOn(prepareDesktopVlcBundle)
        }
        // Sign the bundled dylibs (when credentialed) before Compose copies the
        // prepared resources into the app image, so the packaged copies are signed.
        dependsOn(signBundledMpvDylibs)
    }
}

// The minimum set proving the packaged VLC bundle survived the resource copy;
// the bundle script has already validated the full manifest at staging time.
private val bundledVlcRequiredFileNames =
    setOf(
        "libvlc.dylib",
        "libvlccore.dylib",
        "ATTRIBUTION.md",
    )

private val packagedLicenseMetadataFileNames =
    setOf(
        "SOURCE_REVISION.txt",
        "SOURCE_URL.txt",
        "PROJECT_FILES.git-tree",
        "LICENSE",
        "OPEN_SOURCE_NOTICES.md",
        "THIRD_PARTY_COMPONENTS.tsv",
        "DESKTOP_JVM_RUNTIME_LICENSE_INVENTORY.tsv",
        "DESKTOP_JVM_RUNTIME_NOTICES.md",
    )

private val desktopPackageTaskNamesRequiringMpv =
    setOf(
        "createDistributable",
        "createReleaseDistributable",
        "packageDistributionForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
        "packageDmg",
        "packageReleaseDmg",
    )
