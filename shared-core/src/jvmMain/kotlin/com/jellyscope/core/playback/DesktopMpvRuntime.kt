// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.model.DesktopMpvPresentation
import com.jellyscope.core.domain.model.DesktopMpvPresentationAvailability
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Both the native library graph and its presentation, fixed for the process. */
internal class DesktopMpvActiveRuntime(
    val mode: DesktopMpvPresentation,
    val availability: DesktopMpvPresentationAvailability,
    /** Absolute isolated Metal library; null keeps the existing Standard loader. */
    val metalLibrary: File?,
) {
    fun presentationPreference(requested: MpvPresentationPreference): MpvPresentationPreference {
        if (mode == DesktopMpvPresentation.Metal) return MpvPresentationPreference.MacOsNativeMetal
        require(requested != MpvPresentationPreference.MacOsNativeMetal) { "Metal presentation requires the Metal runtime" }
        return requested
    }
}

/**
 * Launch-frozen owner shared by the production constructor and native loader.
 * The startup store initializes it before any player or native probe; the JNA
 * singleton never reloads, so a later saved choice applies only after restart.
 */
internal object DesktopMpvRuntime {
    @Volatile
    private var frozen: DesktopMpvActiveRuntime? = null

    /** The first caller freezes the runtime; later calls return it unchanged. */
    fun initialize(saved: DesktopMpvPresentation): DesktopMpvActiveRuntime =
        synchronized(this) {
            frozen ?: resolve(saved).also { runtime -> frozen = runtime }
        }

    fun active(): DesktopMpvActiveRuntime = frozen ?: error("Desktop mpv runtime was used before startup initialization")

    private fun resolve(saved: DesktopMpvPresentation): DesktopMpvActiveRuntime {
        val flag = System.getProperty(METAL_EXPERIMENT_PROPERTY)
        val directory = System.getProperty(METAL_RUNTIME_DIR_PROPERTY)
        val forcedOpenGl = System.getProperty(FORCE_OPEN_GL_PROPERTY)?.toBooleanStrictOrNull() == true
        // A development override forces this process only and is never persisted.
        if (flag != null || directory != null) {
            return DesktopMpvActiveRuntime(
                mode = DesktopMpvPresentation.Metal,
                availability = DesktopMpvPresentationAvailability.LaunchOverride,
                metalLibrary = explicitMetalLibrary(flag, directory, forcedOpenGl),
            )
        }
        if (!isMacOsArm64() || macOsMajorVersion() < 15) return standard(DesktopMpvPresentationAvailability.Unsupported)
        if (forcedOpenGl) return standard(DesktopMpvPresentationAvailability.LaunchOverride)
        val packaged = packagedMetalLibrary() ?: return standard(DesktopMpvPresentationAvailability.MetalUnavailable)
        return if (saved == DesktopMpvPresentation.Metal) {
            DesktopMpvActiveRuntime(
                mode = DesktopMpvPresentation.Metal,
                availability = DesktopMpvPresentationAvailability.Selectable,
                metalLibrary = packaged,
            )
        } else {
            standard(DesktopMpvPresentationAvailability.Selectable)
        }
    }

    private fun standard(availability: DesktopMpvPresentationAvailability) =
        DesktopMpvActiveRuntime(
            mode = DesktopMpvPresentation.Standard,
            availability = availability,
            metalLibrary = null,
        )

    private fun explicitMetalLibrary(
        flag: String?,
        directory: String?,
        forcedOpenGl: Boolean,
    ): File {
        require(flag == "true" && !directory.isNullOrBlank()) { "Metal experiment requires both explicit launch inputs" }
        require(System.getProperty(DEBUG_PROPERTY) == "true") { "Metal experiment requires a development launch" }
        require(isMacOsArm64()) { "Metal experiment requires macOS arm64" }
        require(macOsMajorVersion() >= 15) { "Metal experiment requires macOS 15 or newer" }
        require(!forcedOpenGl) { "Metal experiment conflicts with forced OpenGL presentation" }
        val candidate = File(directory)
        require(candidate.isAbsolute && candidate.isDirectory && candidate.resolve(LIBMPV).isFile) {
            "Metal experiment runtime directory is invalid or missing"
        }
        return candidate.canonicalFile.resolve(LIBMPV)
    }

    // Only the app's own resources are searched; never a system or package-manager path.
    private fun packagedMetalLibrary(): File? {
        val resources =
            System
                .getProperty(RESOURCES_DIR_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isAbsolute)
                ?: return null
        val directory = resources.resolve(METAL_DIRECTORY)
        if (Files.isSymbolicLink(directory.toPath())) return null
        val complete =
            listOf(LIBMPV, LIBDAV1D).all { name ->
                Files.isRegularFile(directory.resolve(name).toPath(), LinkOption.NOFOLLOW_LINKS)
            }
        return directory.resolve(LIBMPV).takeIf { complete }
    }

    private fun isMacOsArm64(): Boolean =
        System.getProperty("os.name", "").contains("mac", ignoreCase = true) &&
            System.getProperty("os.arch") in setOf("aarch64", "arm64")

    private fun macOsMajorVersion(): Int =
        System
            .getProperty("os.version")
            .orEmpty()
            .substringBefore('.')
            .toIntOrNull() ?: 0
}

private const val METAL_EXPERIMENT_PROPERTY = "jellyscope.desktop.mpvMetalExperiment"
private const val METAL_RUNTIME_DIR_PROPERTY = "jellyscope.desktop.mpvMetalRuntimeDir"
private const val FORCE_OPEN_GL_PROPERTY = "jellyscope.desktop.forceMpvOpenGlSurface"
private const val DEBUG_PROPERTY = "jellyscope.debug"
private const val RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
private const val METAL_DIRECTORY = "mpv-metal"
private const val LIBMPV = "libmpv.2.dylib"
private const val LIBDAV1D = "libdav1d.7.dylib"
