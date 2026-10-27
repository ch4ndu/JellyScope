// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvDownloadRequestIntent {
    data object RetryLoad : TvDownloadRequestIntent

    data class SelectQuality(
        val qualityId: String,
    ) : TvDownloadRequestIntent

    data class SetFixedSubtitleOff(
        val off: Boolean,
    ) : TvDownloadRequestIntent

    data object Preview : TvDownloadRequestIntent

    data object ConfirmOriginalWithoutSubtitle : TvDownloadRequestIntent

    data object ConfirmFixedBurnIn : TvDownloadRequestIntent

    data object DismissConfirmation : TvDownloadRequestIntent

    data object Enqueue : TvDownloadRequestIntent
}
