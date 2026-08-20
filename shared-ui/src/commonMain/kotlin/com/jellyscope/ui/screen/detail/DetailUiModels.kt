// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.DownloadAdmissionDecision
import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.model.DownloadRecord
import com.jellyscope.core.domain.model.DownloadRequest
import com.jellyscope.core.domain.model.FixedDownloadDraft
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaPersonType
import com.jellyscope.core.domain.model.OriginalDownloadDraft
import com.jellyscope.core.domain.model.RelatedGroupKind
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.ui.component.MediaCardUi
import kotlin.time.Duration

sealed interface DetailUiState {
    data object Loading : DetailUiState

    data class Error(
        val retryable: Boolean = true,
    ) : DetailUiState

    data class Content(
        val detail: DetailUi,
    ) : DetailUiState
}

data class DetailUi(
    val itemId: String,
    val itemKind: MediaKind = MediaKind.Other,
    val seriesName: String? = null,
    val runtimeMs: Long? = null,
    val title: String,
    val headerLine: String?,
    val metadataLine: String?,
    val genresLine: String?,
    val officialRating: String?,
    val communityRating: String?,
    val criticRatingText: String?,
    val imdbUrl: String?,
    val tmdbUrl: String?,
    val tagline: String?,
    val overview: String?,
    val directedByLine: String?,
    val studioLine: String?,
    val castAndCrew: List<CastAndCrewUi>,
    val streamBadges: List<String>,
    val mediaInfo: MediaInfoUi?,
    val timeLeftText: String?,
    val playAction: DetailPlayAction,
    val restartAction: DetailPlayAction?,
    val isWatched: Boolean,
    val watchedToggleInFlight: Boolean,
    val isFavorite: Boolean,
    val favoriteToggleInFlight: Boolean,
    val progressFraction: Float?,
    val trackSelection: DetailTrackSelectionUi,
    val versions: List<MediaVersionUi>,
    val relatedGroups: List<RelatedGroupUi>,
    val relatedLoading: Boolean,
    val backdropUrl: String?,
    val posterUrl: String?,
    val logoUrl: String?,
    val trailerUrl: String?,
    val imdbId: String? = null,
    val productionYear: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val subtitleSearchLanguage: String = "en",
    val selectedSourceReleaseBasename: String? = null,
    val selectedMediaSourceId: String? = null,
    val chapters: List<OfflineChapterUi> = emptyList(),
)

data class OfflineChapterUi(
    val name: String,
    val startTicks: Long,
)

sealed interface DetailDownloadState {
    data object Idle : DetailDownloadState

    data object Previewing : DetailDownloadState

    data class BitmapSubtitleConfirmation(
        val streamIndex: Int,
    ) : DetailDownloadState

    data class Ready(
        val draft: OriginalDownloadDraft,
        val request: DownloadRequest,
    ) : DetailDownloadState

    data class QuotaRequired(
        val draft: OriginalDownloadDraft,
        val request: DownloadRequest,
        val maximumQuotaBytes: Long?,
    ) : DetailDownloadState

    data class FixedBurnInConfirmation(
        val quality: DownloadQuality.Fixed,
        val selectedAudioStreamIndex: Int?,
        val subtitleStreamIndex: Int,
        val subtitleLabel: String,
    ) : DetailDownloadState

    data class FixedReady(
        val draft: FixedDownloadDraft,
        val request: DownloadRequest,
    ) : DetailDownloadState

    data class FixedQuotaRequired(
        val draft: FixedDownloadDraft,
        val request: DownloadRequest,
        val maximumQuotaBytes: Long?,
    ) : DetailDownloadState

    data class FixedRejected(
        val decision: DownloadAdmissionDecision,
    ) : DetailDownloadState

    data class Rejected(
        val decision: DownloadAdmissionDecision,
    ) : DetailDownloadState

    data class Created(
        val record: DownloadRecord,
    ) : DetailDownloadState

    data class Existing(
        val record: DownloadRecord,
    ) : DetailDownloadState

    data class SchedulingRejected(
        val record: DownloadRecord,
    ) : DetailDownloadState

    data class EnqueueRejected(
        val decision: DownloadAdmissionDecision,
    ) : DetailDownloadState

    data object RemovalInProgress : DetailDownloadState
}

/**
 * The durable action represented by the detail-screen download entry.
 *
 * A record is deliberately retained for Manage and PlayOffline so the caller can
 * use the same account-qualified navigation/action already used by Downloads.
 */
sealed interface DetailDownloadEntryState {
    data object Add : DetailDownloadEntryState

    data class Manage(
        val record: DownloadRecord,
    ) : DetailDownloadEntryState

    data class PlayOffline(
        val record: DownloadRecord,
    ) : DetailDownloadEntryState
}

// A titled related shelf. `kind` + `label` are resolved to a localized title in
// the UI layer (e.g. "More Like This", "More Action", "More from A24").
data class RelatedGroupUi(
    val kind: RelatedGroupKind,
    val label: String?,
    val items: List<MediaCardUi>,
)

data class DetailPlayAction(
    val label: DetailPlayLabel,
    val startPositionTicks: Long,
    val mediaSourceId: String?,
)

sealed interface DetailPlayLabel {
    data object Start : DetailPlayLabel

    data class Resume(
        val position: Duration,
    ) : DetailPlayLabel

    data object StartOver : DetailPlayLabel
}

data class MediaVersionUi(
    val id: String,
    val name: String,
    val releaseBasename: String? = null,
    val streamBadges: List<String> = emptyList(),
    val mediaInfo: MediaInfoUi? = null,
    val trackSelection: DetailTrackSelectionUi = DetailTrackSelectionUi(),
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    /** Credential-free source facts retained for the Original download draft. */
    val mediaStreams: List<PlaybackMediaStream> = emptyList(),
    val container: String? = null,
    val runtimeMs: Long? = null,
)

data class CastAndCrewUi(
    val id: String,
    val name: String,
    val role: String?,
    val fallbackRoleType: MediaPersonType?,
    val imageUrl: String?,
)
