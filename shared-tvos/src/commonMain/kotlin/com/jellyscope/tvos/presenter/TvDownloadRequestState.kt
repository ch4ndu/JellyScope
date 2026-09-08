// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.Session

data class TvDownloadRequest(
    val session: Session,
    val selection: TvDetailPlaybackSelection,
)

enum class TvDownloadRequestStage {
    Loading,
    Choosing,
    Previewing,
    ConfirmOriginalWithoutSubtitle,
    ConfirmFixedBurnIn,
    Ready,
    Submitting,
    Finished,
    Failed,
}

enum class TvDownloadQualityKind {
    Original,
    Fixed,
}

data class TvDownloadQualityChoice(
    val id: String,
    val kind: TvDownloadQualityKind,
    val maxBitrateBps: Long? = null,
    val height: Int? = null,
    val usesUpToLabel: Boolean = false,
)

enum class TvFixedSubtitleRequirement {
    None,
    BurnInConfirmation,
    OffRequired,
}

data class TvDownloadRequestSummary(
    val title: String,
    val sourceLabel: String,
    val audioLabel: String?,
    val subtitleLabel: String?,
)

enum class TvDownloadRequestError {
    PermissionDenied,
    QuotaUnconfigured,
    QuotaExceeded,
    DeviceStorageLow,
    SizeUnavailable,
    SourceChanged,
    NetworkUnavailable,
    UnsupportedArtifact,
    PlaybackUnsupported,
    SubtitleUnavailable,
    RemovalInProgress,
    Unknown,
}

enum class TvDownloadRequestOutcome {
    Queued,
    Existing,
    SchedulingDelayed,
}

data class TvDownloadRequestState(
    val stage: TvDownloadRequestStage = TvDownloadRequestStage.Loading,
    val summary: TvDownloadRequestSummary? = null,
    val qualityChoices: List<TvDownloadQualityChoice> = emptyList(),
    val selectedQualityId: String = TV_DOWNLOAD_ORIGINAL_QUALITY_ID,
    val fixedSubtitleRequirement: TvFixedSubtitleRequirement = TvFixedSubtitleRequirement.None,
    val fixedSubtitleOff: Boolean = false,
    val estimatedBytes: Long? = null,
    val warningSubtitleLabel: String? = null,
    val error: TvDownloadRequestError? = null,
    val outcome: TvDownloadRequestOutcome? = null,
)

const val TV_DOWNLOAD_ORIGINAL_QUALITY_ID = "original"
