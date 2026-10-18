// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidMpvRuntimeAvailabilityTest {
    @Test
    fun bundledRequiresBothLibrariesOnOneShippedSupportedAbi() {
        val calls = mutableListOf<String>()
        val result =
            AndroidMpvRuntimeAvailability.inspectLibraryPaths(
                findLibrary = { name ->
                    calls += name
                    when (name) {
                        "mpv" -> "/data/app/base.apk!/lib/arm64/libmpv.so"
                        "player" -> "/data/app/base.apk!/lib/arm64-v8a/libplayer.so"
                        else -> null
                    }
                },
                supportedAbis = setOf("arm64-v8a"),
            )

        assertEquals(AndroidMpvRuntimeAvailability.Bundled("arm64-v8a"), result)
        assertEquals(listOf("mpv", "player"), calls)
    }

    @Test
    fun missingLibrariesReturnTypedReasonsWithoutNativeWork() {
        assertEquals(
            AndroidMpvRuntimeAvailability.Unavailable(AndroidMpvUnavailableReason.MpvLibraryMissing),
            AndroidMpvRuntimeAvailability.inspectLibraryPaths(
                findLibrary = { null },
                supportedAbis = setOf("arm64-v8a"),
            ),
        )
        assertEquals(
            AndroidMpvRuntimeAvailability.Unavailable(AndroidMpvUnavailableReason.PlayerLibraryMissing),
            AndroidMpvRuntimeAvailability.inspectLibraryPaths(
                findLibrary = { name -> if (name == "mpv") "/lib/arm64-v8a/libmpv.so" else null },
                supportedAbis = setOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun rejectsMismatchedUnsupportedAndUnshippedAbis() {
        val mismatched =
            AndroidMpvRuntimeAvailability.inspectLibraryPaths(
                findLibrary = { name ->
                    if (name == "mpv") "/lib/arm64-v8a/libmpv.so" else "/lib/x86_64/libplayer.so"
                },
                supportedAbis = setOf("arm64-v8a", "x86_64"),
            )
        val unsupported =
            AndroidMpvRuntimeAvailability.inspectLibraryPaths(
                findLibrary = { "/lib/x86_64/lib$it.so" },
                supportedAbis = setOf("arm64-v8a"),
            )
        val unshipped =
            AndroidMpvRuntimeAvailability.inspectLibraryPaths(
                findLibrary = { "/lib/x86/lib$it.so" },
                supportedAbis = setOf("x86"),
            )

        assertEquals(
            AndroidMpvRuntimeAvailability.Unavailable(AndroidMpvUnavailableReason.LibraryAbiMismatch),
            mismatched,
        )
        assertEquals(
            AndroidMpvRuntimeAvailability.Unavailable(AndroidMpvUnavailableReason.DeviceAbiUnsupported),
            unsupported,
        )
        assertEquals(
            AndroidMpvRuntimeAvailability.Unavailable(AndroidMpvUnavailableReason.AbiNotShipped),
            unshipped,
        )
    }

    @Test
    fun classLoaderGuardDoesNotAttemptAnyLibraryLookup() {
        assertEquals(
            AndroidMpvRuntimeAvailability.Unavailable(AndroidMpvUnavailableReason.ClassLoaderUnavailable),
            AndroidMpvRuntimeAvailability.inspect(
                classLoader = null,
                supportedAbis = setOf("arm64-v8a"),
            ),
        )
    }

    @Test
    fun osApiGuardRefusesBelowMinimumBeforeAnyClassLoaderWork() {
        assertEquals(
            AndroidMpvRuntimeAvailability.Unavailable(AndroidMpvUnavailableReason.OsApiBelowMinimum),
            AndroidMpvRuntimeAvailability.inspect(
                classLoader = null,
                supportedAbis = setOf("arm64-v8a"),
                osApiLevel = ANDROID_MPV_MIN_OS_API - 1,
            ),
        )
        assertEquals(
            AndroidMpvRuntimeAvailability.Unavailable(AndroidMpvUnavailableReason.ClassLoaderUnavailable),
            AndroidMpvRuntimeAvailability.inspect(
                classLoader = null,
                supportedAbis = setOf("arm64-v8a"),
                osApiLevel = ANDROID_MPV_MIN_OS_API,
            ),
        )
    }
}
