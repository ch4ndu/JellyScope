// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.OfflinePersonCreditType

enum class TvDownloadSectionKind {
    Completed,
    Active,
    Queued,
    Paused,
    Failed,
}

enum class TvDownloadRowState {
    Completed,
    Downloading,
    Finalizing,
    Queued,
    Paused,
    BlockedByQuota,
    Failed,
}

enum class TvDownloadFailureKind {
    PermissionDenied,
    SizeUnavailable,
    Network,
    ServerUnavailable,
    SourceChanged,
    UnsupportedArtifact,
    QuotaExceeded,
    DeviceStorageLow,
    MissingArtifact,
    ArtifactInUse,
}

data class TvDownloadRow(
    val id: String,
    val attemptGeneration: Long,
    val title: String,
    val itemKind: MediaKind,
    val seriesName: String? = null,
    val seasonLabel: String? = null,
    val episodeLabel: String? = null,
    val secondaryTitle: String? = null,
    val sourceLabel: String? = null,
    val durationMs: Long? = null,
    val chapters: List<TvChapter> = emptyList(),
    val audioTracks: List<TvTrackChoice> = emptyList(),
    val subtitleTracks: List<TvTrackChoice> = emptyList(),
    val backend: TvDownloadBackendInfo? = null,
    val detail: TvDownloadDetail? = null,
    /** Pre-grouped on the presenter worker so native SwiftUI only renders saved people facts. */
    val cast: List<TvDownloadPerson> = emptyList(),
    val crew: List<TvDownloadPerson> = emptyList(),
    val otherPeople: List<TvDownloadPerson> = emptyList(),
    val state: TvDownloadRowState,
    /** Changes after transfer-owned artwork capture so native views can re-read the published file. */
    val presentationBytes: Long = 0L,
    val qualityBitrateBps: Long? = null,
    val physicalBytes: Long,
    val expectedBytes: Long,
    val progressFraction: Double,
    val localResumePositionMs: Long,
    val localWatched: Boolean,
    val failure: TvDownloadFailureKind? = null,
    val canPlay: Boolean = false,
    val canRestart: Boolean = false,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canRetry: Boolean = false,
    val canCancel: Boolean = false,
    val canDelete: Boolean = false,
    val isLeased: Boolean = false,
)

/** Saved, credential-free media facts that native tvOS can render without a server request. */
data class TvDownloadDetail(
    val overview: String? = null,
    val tagline: String? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    val criticRating: Double? = null,
    val productionYear: Int? = null,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val people: List<TvDownloadPerson> = emptyList(),
    val externalProviderIds: TvDownloadExternalProviderIds = TvDownloadExternalProviderIds(),
)

data class TvDownloadPerson(
    val name: String,
    val role: String? = null,
    val creditType: OfflinePersonCreditType = OfflinePersonCreditType.Other,
)

data class TvDownloadExternalProviderIds(
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val tmdbItemType: String? = null,
)

/** Saved media facts for the selected local source, never a local path or remote URL. */
data class TvDownloadBackendInfo(
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val isHdrOrDolbyVision: Boolean = false,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFrameRate: Double? = null,
)

data class TvDownloadSection(
    val kind: TvDownloadSectionKind,
    val rows: List<TvDownloadRow>,
)

data class TvDownloadStorageState(
    val physicalBytes: Long,
    val currentAccountPhysicalBytes: Long,
    val otherAccountsPhysicalBytes: Long,
    val outstandingReservationBytes: Long,
    val projectedCommittedBytes: Long,
    val quotaBytes: Long?,
    val remainingQuotaBytes: Long?,
    val deviceAvailableBytes: Long,
    val safetyReserveBytes: Long,
    val maximumConfigurableQuotaBytes: Long,
    val overAllocation: Boolean,
)

enum class TvDownloadsError {
    LoadFailed,
    CommandRejected,
    QueueWakeRejected,
    QuotaRejected,
    ArtifactInUse,
    StaleConfirmation,
}

data class TvDownloadsState(
    val sections: List<TvDownloadSection> = emptyList(),
    val storage: TvDownloadStorageState? = null,
    val isLoading: Boolean = true,
    val isRefreshingStorage: Boolean = false,
    val inFlightDownloadId: String? = null,
    val isBulkResumeInFlight: Boolean = false,
    val isQueueWakeInFlight: Boolean = false,
    val hasPausedDownloads: Boolean = false,
    val hasQueuedDownloads: Boolean = false,
    val error: TvDownloadsError? = null,
)
