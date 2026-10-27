// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.core.domain.model.DownloadQuality
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent

sealed interface DetailIntent {
    data object Retry : DetailIntent

    data object Refresh : DetailIntent

    data object ResetOriginalDownloadState : DetailIntent

    data object ResetDownloadState : DetailIntent

    data class PreviewOriginalDownload(
        val selectedAudioStreamIndex: Int?,
        val subtitleSelection: SubtitleSelectionIntent,
    ) : DetailIntent

    data object EnqueueOriginalDownload : DetailIntent

    data class PreviewFixedDownload(
        val quality: DownloadQuality.Fixed,
        val selectedAudioStreamIndex: Int?,
        val subtitleSelection: SubtitleSelectionIntent,
    ) : DetailIntent

    data object ConfirmFixedBurnIn : DetailIntent

    data object CancelFixedBurnIn : DetailIntent

    data object EnqueueFixedDownload : DetailIntent

    data object ToggleWatched : DetailIntent

    data object ToggleFavorite : DetailIntent

    data class DeleteLocalSubtitle(
        val assetId: String,
    ) : DetailIntent

    data class RetryLocalSubtitleSync(
        val assetId: String,
    ) : DetailIntent

    data class SelectSubtitle(
        val streamIndex: Int?,
    ) : DetailIntent

    data class SelectLocalSubtitle(
        val assetId: String,
    ) : DetailIntent

    data class SelectMediaVersion(
        val mediaSourceId: String,
    ) : DetailIntent
}
