// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.DownloadArtifactKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflinePlaybackBackendResolutionTest {
    @Test
    fun originalPreservesEverySelectedBackend() {
        PlayerBackend.entries
            .filter { backend -> backend != PlayerBackend.Auto }
            .forEach { backend ->
                val platform =
                    when (backend) {
                        PlayerBackend.AVPlayer,
                        PlayerBackend.VlcKit,
                        -> PlayerBackendPlatform.Apple
                        PlayerBackend.ExoPlayer,
                        -> PlayerBackendPlatform.Android
                        PlayerBackend.Mpv,
                        PlayerBackend.LibVlc,
                        -> PlayerBackendPlatform.Desktop
                        PlayerBackend.Auto -> error("filtered")
                    }
                assertEquals(
                    backend,
                    resolveOfflinePlaybackBackend(platform, DownloadArtifactKind.OriginalFile, backend),
                )
            }
    }

    @Test
    fun appleLocalHlsRequiresVlcKitWithoutChangingOtherPlatforms() {
        assertEquals(
            PlayerBackend.VlcKit,
            resolveOfflinePlaybackBackend(
                PlayerBackendPlatform.Apple,
                DownloadArtifactKind.LocalHlsPackage,
                PlayerBackend.AVPlayer,
            ),
        )
        assertEquals(
            PlayerBackend.ExoPlayer,
            resolveOfflinePlaybackBackend(
                PlayerBackendPlatform.Android,
                DownloadArtifactKind.LocalHlsPackage,
                PlayerBackend.ExoPlayer,
            ),
        )
        assertEquals(
            PlayerBackend.LibVlc,
            resolveOfflinePlaybackBackend(
                PlayerBackendPlatform.Desktop,
                DownloadArtifactKind.LocalHlsPackage,
                PlayerBackend.LibVlc,
            ),
        )
    }

    @Test
    fun originalFilesKeepControllerFallbackWhileAppleLocalHlsRequiresExactVlcKit() {
        assertTrue(
            offlineControllerFallbackAllowed(
                artifactKind = DownloadArtifactKind.OriginalFile,
                resolvedBackend = PlayerBackend.LibVlc,
            ),
        )
        assertTrue(
            offlineControllerFallbackAllowed(
                artifactKind = DownloadArtifactKind.LocalHlsPackage,
                resolvedBackend = PlayerBackend.ExoPlayer,
            ),
        )
        assertTrue(
            offlineControllerFallbackAllowed(
                artifactKind = DownloadArtifactKind.LocalHlsPackage,
                resolvedBackend = PlayerBackend.LibVlc,
            ),
        )
        assertFalse(
            offlineControllerFallbackAllowed(
                artifactKind = DownloadArtifactKind.LocalHlsPackage,
                resolvedBackend = PlayerBackend.VlcKit,
            ),
        )
    }
}
