// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import android.content.Context
import android.os.Build
import dalvik.system.BaseDexClassLoader

/**
 * Cheap process-scoped truth about whether the bundled mpv JNI payload is
 * present for the running application ABI.
 *
 * This check deliberately stops at class-loader lookup. It never references
 * the vendor wrapper, loads a native library, or creates an engine; requested
 * mpv construction owns those later checks.
 */
internal sealed interface AndroidMpvRuntimeAvailability {
    data class Bundled(
        val abi: String,
    ) : AndroidMpvRuntimeAvailability

    data class Unavailable(
        val reason: AndroidMpvUnavailableReason,
    ) : AndroidMpvRuntimeAvailability

    companion object {
        @Volatile
        private var cachedAvailability: AndroidMpvRuntimeAvailability? = null

        @Synchronized
        fun check(context: Context): AndroidMpvRuntimeAvailability =
            cachedAvailability
                ?: inspect(
                    classLoader = context.applicationContext.classLoader,
                    supportedAbis = Build.SUPPORTED_ABIS.toSet(),
                    shippedAbis = ANDROID_MPV_SHIPPED_ABIS,
                    osApiLevel = Build.VERSION.SDK_INT,
                ).also { availability -> cachedAvailability = availability }

        /** Isolated seam for host tests; it retains no path or exception text. */
        internal fun inspect(
            classLoader: ClassLoader?,
            supportedAbis: Set<String>,
            shippedAbis: Set<String> = ANDROID_MPV_SHIPPED_ABIS,
            osApiLevel: Int = ANDROID_MPV_MIN_OS_API,
        ): AndroidMpvRuntimeAvailability {
            if (osApiLevel < ANDROID_MPV_MIN_OS_API) {
                return AndroidMpvRuntimeAvailability.Unavailable(
                    AndroidMpvUnavailableReason.OsApiBelowMinimum,
                )
            }
            val dexClassLoader =
                classLoader as? BaseDexClassLoader
                    ?: return AndroidMpvRuntimeAvailability.Unavailable(
                        AndroidMpvUnavailableReason.ClassLoaderUnavailable,
                    )
            return inspectLibraryPaths(
                findLibrary = { name -> runCatching { dexClassLoader.findLibrary(name) }.getOrNull() },
                supportedAbis = supportedAbis,
                shippedAbis = shippedAbis,
            )
        }

        /** Isolated path-only seam used by the host tests. */
        internal fun inspectLibraryPaths(
            findLibrary: (String) -> String?,
            supportedAbis: Set<String>,
            shippedAbis: Set<String> = ANDROID_MPV_SHIPPED_ABIS,
        ): AndroidMpvRuntimeAvailability {
            val mpvPath =
                findLibrary("mpv")
                    ?: return AndroidMpvRuntimeAvailability.Unavailable(
                        AndroidMpvUnavailableReason.MpvLibraryMissing,
                    )
            val playerPath =
                findLibrary("player")
                    ?: return AndroidMpvRuntimeAvailability.Unavailable(
                        AndroidMpvUnavailableReason.PlayerLibraryMissing,
                    )
            val mpvAbi =
                abiInPath(mpvPath)
                    ?: return AndroidMpvRuntimeAvailability.Unavailable(
                        AndroidMpvUnavailableReason.LibraryAbiUnknown,
                    )
            val playerAbi =
                abiInPath(playerPath)
                    ?: return AndroidMpvRuntimeAvailability.Unavailable(
                        AndroidMpvUnavailableReason.LibraryAbiUnknown,
                    )
            if (mpvAbi != playerAbi) {
                return AndroidMpvRuntimeAvailability.Unavailable(
                    AndroidMpvUnavailableReason.LibraryAbiMismatch,
                )
            }
            if (mpvAbi !in shippedAbis) {
                return AndroidMpvRuntimeAvailability.Unavailable(
                    AndroidMpvUnavailableReason.AbiNotShipped,
                )
            }
            if (mpvAbi !in supportedAbis) {
                return AndroidMpvRuntimeAvailability.Unavailable(
                    AndroidMpvUnavailableReason.DeviceAbiUnsupported,
                )
            }
            return AndroidMpvRuntimeAvailability.Bundled(mpvAbi)
        }

        private fun abiInPath(path: String): String? {
            val segments = path.replace('\\', '/').split('/')
            return segments.firstNotNullOfOrNull { segment -> segment.toCanonicalAbi() }
        }

        private fun String.toCanonicalAbi(): String? =
            when (this) {
                "arm64",
                "arm64-v8a",
                -> "arm64-v8a"
                "armeabi",
                "armeabi-v7a",
                -> "armeabi-v7a"
                "x86" -> "x86"
                "x86_64" -> "x86_64"
                else -> null
            }
    }
}

internal enum class AndroidMpvUnavailableReason {
    OsApiBelowMinimum,
    ClassLoaderUnavailable,
    MpvLibraryMissing,
    PlayerLibraryMissing,
    LibraryAbiUnknown,
    LibraryAbiMismatch,
    AbiNotShipped,
    DeviceAbiUnsupported,
}

internal val ANDROID_MPV_SHIPPED_ABIS =
    setOf("arm64-v8a", "armeabi-v7a", "x86_64")

/**
 * The pinned libmpv-android payload declares minSdk 26 (its AImageReader hwdec
 * interop is an API-26 feature). The apps install down to API 25 for Fire OS 6
 * devices, where mpv construction is refused and playback stays on ExoPlayer.
 */
internal const val ANDROID_MPV_MIN_OS_API = 26
