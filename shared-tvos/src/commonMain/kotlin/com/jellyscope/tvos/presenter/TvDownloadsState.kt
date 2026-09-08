// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

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
    val secondaryTitle: String? = null,
    val sourceLabel: String? = null,
    val state: TvDownloadRowState,
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
