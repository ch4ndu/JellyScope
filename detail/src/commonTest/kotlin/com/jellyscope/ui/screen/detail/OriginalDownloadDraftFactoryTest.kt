// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleSyncState
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OriginalDownloadDraftFactoryTest {
    @Test
    fun externalTextUsesServerSidecarAndNormalizedVttSnapshot() {
        val result =
            buildOriginalDownloadDraft(
                detail = detailFor(versionWithStreams()),
                selectedAudioStreamIndex = 1,
                subtitleSelection = SubtitleSelectionIntent.Track(2),
                accountIdentity = AccountIdentity("server", "user"),
                downloadId = DownloadId("download-test"),
                artifactKey = DownloadArtifactKey("artifact-test"),
                createdAtEpochMs = 1L,
            )

        val draft = assertIs<OriginalDownloadDraftBuildResult.Ready>(result).draft
        assertEquals(DownloadSubtitleSelection.ExternalServerTextSidecar(2), draft.subtitleSelection)
        assertEquals("vtt", draft.snapshot.selectedSubtitleTrack?.codec)
        assertTrue(draft.snapshot.selectedSubtitleTrack?.isExternal == true)
    }

    @Test
    fun nullStreamIndexesUseAudioAndSubtitleOrdinalsIndependently() {
        val version =
            MediaVersionUi(
                id = "source",
                name = "Original",
                mediaStreams =
                    listOf(
                        PlaybackMediaStream(0, "Video", null, null, null, "h264", null, null, 1080, null, false, null, null),
                        PlaybackMediaStream(null, "Audio", "English", null, "en", "aac", null, null, null, true, false, null, null),
                        PlaybackMediaStream(null, "Audio", "French", null, "fr", "aac", null, null, null, false, false, null, null),
                        PlaybackMediaStream(null, "Subtitle", "English", null, "en", "srt", null, null, null, true, false, "Embed", null),
                    ),
                trackSelection =
                    DetailTrackSelectionUi(
                        mediaSourceId = "source",
                        defaultAudioStreamIndex = 0,
                        defaultSubtitleStreamIndex = 0,
                    ),
            )
        val result =
            buildOriginalDownloadDraft(
                detail = detailFor(version),
                selectedAudioStreamIndex = 0,
                subtitleSelection = SubtitleSelectionIntent.Track(0),
                accountIdentity = AccountIdentity("server", "user"),
                downloadId = DownloadId("download-ordinal"),
                artifactKey = DownloadArtifactKey("artifact-ordinal"),
                createdAtEpochMs = 1L,
            )

        val draft = assertIs<OriginalDownloadDraftBuildResult.Ready>(result).draft
        assertEquals("aac", draft.snapshot.selectedAudioTrack?.codec)
        assertEquals(OfflineTrackKind.Subtitle, draft.snapshot.selectedSubtitleTrack?.kind)
        assertEquals("srt", draft.snapshot.selectedSubtitleTrack?.codec)
    }

    @Test
    fun localAssetBecomesNormalizedExternalSnapshotTrack() {
        val asset =
            LocalSubtitleAsset(
                id = "asset-1",
                serverId = "server",
                userId = "user",
                itemId = "item",
                mediaSourceId = "source",
                provider = "OpenSubtitles",
                providerSubtitleId = "subtitle",
                providerFileId = "file",
                language = "en",
                label = "English",
                releaseName = null,
                originalFormat = "srt",
                mimeType = "text/plain",
                fileId = "local-file",
                hearingImpaired = false,
                forced = false,
                trusted = true,
                createdAtEpochMs = 1L,
                lastUsedAtEpochMs = 1L,
                syncState = LocalSubtitleSyncState.Confirmed(3),
            )
        val version =
            versionWithStreams().copy(
                trackSelection =
                    DetailTrackSelectionUi(
                        mediaSourceId = "source",
                        defaultAudioStreamIndex = 1,
                        localSubtitleOptions = listOf(asset),
                    ),
            )
        val result =
            buildOriginalDownloadDraft(
                detail = detailFor(version),
                selectedAudioStreamIndex = 1,
                subtitleSelection = SubtitleSelectionIntent.LocalAsset("asset-1"),
                accountIdentity = AccountIdentity("server", "user"),
                downloadId = DownloadId("download-local"),
                artifactKey = DownloadArtifactKey("artifact-local"),
                createdAtEpochMs = 1L,
            )

        val draft = assertIs<OriginalDownloadDraftBuildResult.Ready>(result).draft
        assertEquals(DownloadSubtitleSelection.ExternalTextSidecar("asset-1"), draft.subtitleSelection)
        assertEquals("vtt", draft.snapshot.selectedSubtitleTrack?.codec)
        assertEquals("English", draft.snapshot.selectedSubtitleTrack?.label)
    }

    @Test
    fun externalBitmapRequiresExplicitContinueChoice() {
        val version =
            versionWithStreams().copy(
                mediaStreams =
                    versionWithStreams().mediaStreams.map { stream ->
                        if (stream.type == "Subtitle") stream.copy(codec = "pgs", isExternal = true) else stream
                    },
            )
        val result =
            buildOriginalDownloadDraft(
                detail = detailFor(version),
                selectedAudioStreamIndex = 1,
                subtitleSelection = SubtitleSelectionIntent.Track(2),
                accountIdentity = AccountIdentity("server", "user"),
                downloadId = DownloadId("download-bitmap"),
                artifactKey = DownloadArtifactKey("artifact-bitmap"),
                createdAtEpochMs = 1L,
            )

        assertIs<OriginalDownloadDraftBuildResult.ExternalBitmap>(result)
    }
}

private fun versionWithStreams(): MediaVersionUi =
    MediaVersionUi(
        id = "source",
        name = "Original",
        mediaStreams =
            listOf(
                PlaybackMediaStream(0, "Video", null, null, null, "h264", null, null, 1080, null, false, null, null),
                PlaybackMediaStream(1, "Audio", "English", null, "en", "aac", null, null, null, true, false, null, null),
                PlaybackMediaStream(2, "Subtitle", "English", null, "en", "srt", null, null, null, true, true, "External", "/subtitle"),
            ),
        trackSelection =
            DetailTrackSelectionUi(
                mediaSourceId = "source",
                defaultAudioStreamIndex = 1,
                defaultSubtitleStreamIndex = 2,
            ),
    )

private fun detailFor(version: MediaVersionUi): DetailUi =
    DetailUi(
        itemId = "item",
        itemKind = MediaKind.Movie,
        title = "Movie",
        headerLine = null,
        metadataLine = null,
        genresLine = null,
        officialRating = null,
        communityRating = null,
        criticRatingText = null,
        imdbUrl = null,
        tmdbUrl = null,
        tagline = null,
        overview = null,
        directedByLine = null,
        studioLine = null,
        castAndCrew = emptyList(),
        streamBadges = emptyList(),
        mediaInfo = null,
        timeLeftText = null,
        playAction = DetailPlayAction(DetailPlayLabel.Start, 0L, version.id),
        restartAction = null,
        isWatched = false,
        watchedToggleInFlight = false,
        isFavorite = false,
        favoriteToggleInFlight = false,
        progressFraction = null,
        trackSelection = version.trackSelection,
        versions = listOf(version),
        relatedGroups = emptyList(),
        relatedLoading = false,
        backdropUrl = null,
        posterUrl = null,
        logoUrl = null,
        trailerUrl = null,
        selectedMediaSourceId = version.id,
    )
