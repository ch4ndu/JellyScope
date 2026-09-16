// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadArtifactKind
import com.jellyscope.core.domain.model.DownloadBusinessKey
import com.jellyscope.core.domain.model.DownloadFailure
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineArtworkReference
import com.jellyscope.core.domain.model.OfflineArtworkRole
import com.jellyscope.core.domain.model.OfflineChapterSnapshot
import com.jellyscope.core.domain.model.OfflineDetailSnapshot
import com.jellyscope.core.domain.model.OfflineExternalProviderIds
import com.jellyscope.core.domain.model.OfflineMediaSnapshot
import com.jellyscope.core.domain.model.OfflinePersonCreditType
import com.jellyscope.core.domain.model.OfflinePersonSnapshot
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.model.OfflineTrackSnapshot
import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadEncodingTest {
    @Test
    fun failureEncodingIsClosedAndVersioned() {
        val expected =
            mapOf(
                DownloadFailure.PermissionDenied to "permission-denied-v1",
                DownloadFailure.SizeUnavailable to "size-unavailable-v1",
                DownloadFailure.Network to "network-v1",
                DownloadFailure.ServerUnavailable to "server-unavailable-v1",
                DownloadFailure.SourceChanged to "source-changed-v1",
                DownloadFailure.UnsupportedArtifact to "unsupported-artifact-v1",
                DownloadFailure.QuotaExceeded to "quota-exceeded-v1",
                DownloadFailure.DeviceStorageLow to "device-storage-low-v1",
                DownloadFailure.MissingArtifact to "missing-artifact-v1",
                DownloadFailure.ArtifactInUse to "artifact-in-use-v1",
            )

        expected.forEach { (failure, encoding) ->
            assertEquals(encoding, DownloadFailureCodec.encode(failure))
            assertEquals(failure, DownloadFailureCodec.decode(encoding))
        }
        assertNull(DownloadFailureCodec.decode("future-failure-v2"))
    }

    @Test
    fun snapshotEncodingIsVersionedCredentialFreeAndRoundTrips() {
        val snapshot =
            OfflineMediaSnapshot(
                title = "Episode title",
                itemKind = MediaKind.Episode,
                seriesName = "Series",
                seasonLabel = "Season 1",
                episodeLabel = "Episode 2",
                durationMs = 90_000L,
                chapters = listOf(OfflineChapterSnapshot("Opening", 0L)),
                sourcePresentation = "1080p H.264",
                embeddedTracks =
                    listOf(
                        OfflineTrackSnapshot(
                            kind = OfflineTrackKind.Audio,
                            streamIndex = 1,
                            codec = "aac",
                            language = "eng",
                            label = "Stereo",
                            isDefault = true,
                            isExternal = false,
                        ),
                    ),
                selectedAudioTrack =
                    OfflineTrackSnapshot(
                        kind = OfflineTrackKind.Audio,
                        streamIndex = 1,
                        codec = "aac",
                        language = "eng",
                        label = "Stereo",
                        isDefault = true,
                        isExternal = false,
                    ),
                backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
            )

        val encoded = DownloadSnapshotCodec.encode(snapshot)

        assertTrue(encoded.startsWith("offline-snapshot-v1:"))
        assertTrue(encoded.contains("\"itemKind\":\"episode-v1\""))
        assertTrue("http" !in encoded.lowercase())
        assertEquals(snapshot, DownloadSnapshotCodec.decode(encoded))
        assertNull(DownloadSnapshotCodec.decode("offline-snapshot-v2:{}"))

        val literalLegacy =
            DownloadSnapshotCodec.decode(
                "offline-snapshot-v1:{\"formatVersion\":1,\"title\":\"Legacy\",\"itemKind\":\"movie-v1\",\"seriesName\":null,\"seasonLabel\":null,\"episodeLabel\":null,\"durationMs\":null,\"chapters\":[],\"sourcePresentation\":null,\"embeddedTracks\":[],\"selectedAudioTrack\":null,\"selectedSubtitleTrack\":null,\"container\":\"mkv\",\"videoCodec\":\"h264\",\"audioCodec\":\"aac\",\"hdrOrDolbyVision\":false}",
            )
        assertEquals("Legacy", literalLegacy?.title)
        assertNull(literalLegacy?.detail)
        assertEquals(emptyList(), literalLegacy?.artworkReferences)
        assertEquals(false, literalLegacy?.presentationCaptureEligible)

        val enrichedSnapshot =
            snapshot.copy(
                detail =
                    OfflineDetailSnapshot(
                        overview = "Saved overview",
                        communityRating = 8.4,
                        criticRating = 91.0,
                        genres = listOf("Drama"),
                        people = listOf(OfflinePersonSnapshot("Performer", "Lead", OfflinePersonCreditType.Cast)),
                        externalProviderIds = OfflineExternalProviderIds(imdbId = "tt1234567", tmdbId = "42", tmdbItemType = "movie"),
                    ),
                artworkReferences = listOf(OfflineArtworkReference(OfflineArtworkRole.Poster, "series-id", "image-tag")),
                presentationCaptureEligible = true,
            )
        assertEquals(enrichedSnapshot, DownloadSnapshotCodec.decode(DownloadSnapshotCodec.encode(enrichedSnapshot)))
    }

    @Test
    fun removalArtifactKeysUseOneFixedRelativeEncoding() {
        val keys = listOf(DownloadArtifactKey("artifact_one"), DownloadArtifactKey("artifact-two"))

        assertEquals("artifact-keys-v1:artifact_one,artifact-two", DownloadArtifactKeyCodec.encode(keys))
        assertEquals(keys, DownloadArtifactKeyCodec.decode(DownloadArtifactKeyCodec.encode(keys)))
        assertEquals(emptyList(), DownloadArtifactKeyCodec.decode(DownloadArtifactKeyCodec.EMPTY_ENCODING))
        assertNull(DownloadArtifactKeyCodec.decode("artifact-keys-v2:artifact_one"))
    }

    @Test
    fun serverTextSidecarEncodingRetainsStreamIndexAcrossEntityRoundTrip() {
        val request =
            DownloadRequest(
                downloadId = DownloadId("download_server_sidecar"),
                businessKey = DownloadBusinessKey(AccountIdentity("server", "user"), "item", "source"),
                quality = DownloadQuality.Original,
                artifactKind = DownloadArtifactKind.OriginalFile,
                selectedAudioStreamIndex = null,
                subtitleSelection = DownloadSubtitleSelection.ExternalServerTextSidecar(streamIndex = 7),
                admissionEstimateBytes = 1L,
                initialReservationBytes = 1L,
                artifactKey = DownloadArtifactKey("artifact_server_sidecar"),
                snapshot =
                    OfflineMediaSnapshot(
                        title = "Movie",
                        itemKind = MediaKind.Movie,
                        backendSource = BackendSourceDescriptor("mkv", "h264", "aac", false),
                    ),
                createdAtEpochMs = 1L,
            )

        val decoded = request.toEntity(fifoSequence = 1L).toModelOrNull()
        assertEquals(request, decoded?.request)
    }
}
