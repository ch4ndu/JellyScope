// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DownloadArtifactKey
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflineTrackKind
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedDownloadDraftFactoryTest {
    @Test
    fun reliableSourceHidesRungsThatDoNotReduceIt() {
        val choices = fixedDownloadQualityChoices(version(videoBitrate = 20_000_000L, videoHeight = 1080))

        assertTrue(choices.all { choice -> choice.sourceBitrateKnown })
        assertTrue(choices.none { choice -> choice.rung.maxBitrateBps == 20_000_000L })
        assertTrue(choices.none { choice -> choice.rung.maxBitrateBps > 20_000_000L })
        assertTrue(choices.any { choice -> choice.rung.maxBitrateBps == 12_000_000L })
    }

    @Test
    fun missingSourceFactsKeepsFullUpToLadder() {
        val choices = fixedDownloadQualityChoices(version(videoBitrate = null, videoHeight = null))

        assertEquals(8, choices.size)
        assertTrue(choices.all { choice -> !choice.sourceBitrateKnown })
    }

    @Test
    fun fixedDraftPreservesSelectedAudioAndOffSubtitle() {
        val result =
            buildFixedDownloadDraft(
                detail = detailFor(version()),
                quality = DownloadQuality.Fixed(8_000_000L),
                selectedAudioStreamIndex = 1,
                subtitleSelection = SubtitleSelectionIntent.Off,
                accountIdentity = AccountIdentity("server", "user"),
                downloadId = DownloadId("download-fixed"),
                artifactKey = DownloadArtifactKey("artifact-fixed"),
                createdAtEpochMs = 1L,
                burnInConfirmed = false,
            )

        val draft = assertIs<FixedDownloadDraftBuildResult.Ready>(result).draft
        assertEquals(1, draft.selectedAudioStreamIndex)
        assertEquals(com.jellyscope.core.domain.model.DownloadSubtitleSelection.Off, draft.subtitleSelection)
        assertEquals(8_000_000L, draft.quality.maxBitrateBps)
        assertEquals(listOf(OfflineTrackKind.Audio), draft.snapshot.embeddedTracks.map { track -> track.kind })
        assertEquals("aac", draft.snapshot.selectedAudioTrack?.codec)
        assertEquals(1, draft.snapshot.selectedAudioTrack?.streamIndex)
        assertEquals("hls", draft.snapshot.backendSource.container)
        assertEquals("h264", draft.snapshot.backendSource.videoCodec)
        assertEquals("aac", draft.snapshot.backendSource.audioCodec)
        assertEquals(false, draft.snapshot.backendSource.isHdrOrDolbyVision)
        assertNull(draft.snapshot.selectedSubtitleTrack)
    }

    @Test
    fun embeddedTextRequiresExplicitBurnInAndThenBuilds() {
        val detail = detailFor(version())
        val pending =
            buildFixedDownloadDraft(
                detail = detail,
                quality = DownloadQuality.Fixed(4_000_000L),
                selectedAudioStreamIndex = 1,
                subtitleSelection = SubtitleSelectionIntent.Track(2),
                accountIdentity = AccountIdentity("server", "user"),
                downloadId = DownloadId("download-burnin"),
                artifactKey = DownloadArtifactKey("artifact-burnin"),
                createdAtEpochMs = 1L,
                burnInConfirmed = false,
            )
        assertIs<FixedDownloadDraftBuildResult.NeedsBurnIn>(pending)

        val ready =
            buildFixedDownloadDraft(
                detail = detail,
                quality = DownloadQuality.Fixed(4_000_000L),
                selectedAudioStreamIndex = 1,
                subtitleSelection = SubtitleSelectionIntent.Track(2),
                accountIdentity = AccountIdentity("server", "user"),
                downloadId = DownloadId("download-burnin-ready"),
                artifactKey = DownloadArtifactKey("artifact-burnin-ready"),
                createdAtEpochMs = 1L,
                burnInConfirmed = true,
            )
        assertIs<FixedDownloadDraftBuildResult.Ready>(ready)
    }

    @Test
    fun externalSubtitleCannotBeSilentlySelectedForFixedDownload() {
        val result =
            buildFixedDownloadDraft(
                detail = detailFor(version(externalSubtitle = true)),
                quality = DownloadQuality.Fixed(4_000_000L),
                selectedAudioStreamIndex = 1,
                subtitleSelection = SubtitleSelectionIntent.Track(2),
                accountIdentity = AccountIdentity("server", "user"),
                downloadId = DownloadId("download-external"),
                artifactKey = DownloadArtifactKey("artifact-external"),
                createdAtEpochMs = 1L,
                burnInConfirmed = true,
            )

        assertIs<FixedDownloadDraftBuildResult.UnsupportedSubtitle>(result)
    }
}

private fun version(
    videoBitrate: Long? = 20_000_000L,
    videoHeight: Int? = 1080,
    externalSubtitle: Boolean = false,
): MediaVersionUi =
    MediaVersionUi(
        id = "source",
        name = "Version",
        runtimeMs = 60_000L,
        mediaStreams =
            listOf(
                PlaybackMediaStream(
                    index = 0,
                    type = "Video",
                    displayTitle = null,
                    title = null,
                    language = null,
                    codec = "h264",
                    channelLayout = null,
                    bitRate = videoBitrate,
                    height = videoHeight,
                    isDefault = true,
                    isExternal = false,
                    deliveryMethod = null,
                    deliveryUrl = null,
                ),
                PlaybackMediaStream(
                    index = 1,
                    type = "Audio",
                    displayTitle = "English",
                    title = null,
                    language = "en",
                    codec = "aac",
                    channelLayout = null,
                    bitRate = null,
                    height = null,
                    isDefault = true,
                    isExternal = false,
                    deliveryMethod = null,
                    deliveryUrl = null,
                ),
                PlaybackMediaStream(
                    index = 2,
                    type = "Subtitle",
                    displayTitle = "English",
                    title = null,
                    language = "en",
                    codec = "srt",
                    channelLayout = null,
                    bitRate = null,
                    height = null,
                    isDefault = true,
                    isExternal = externalSubtitle,
                    deliveryMethod = if (externalSubtitle) "External" else "Embed",
                    deliveryUrl = null,
                ),
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
