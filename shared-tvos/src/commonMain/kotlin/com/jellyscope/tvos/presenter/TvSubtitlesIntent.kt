// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

sealed interface TvSubtitlesIntent {
    data object Load : TvSubtitlesIntent

    data class SetLanguage(
        val language: String,
    ) : TvSubtitlesIntent

    data object Search : TvSubtitlesIntent

    data class Install(
        val fileId: String,
    ) : TvSubtitlesIntent

    data class SelectLocalSubtitle(
        val assetId: String?,
    ) : TvSubtitlesIntent

    data class Delete(
        val assetId: String,
    ) : TvSubtitlesIntent

    data class RetrySync(
        val assetId: String,
    ) : TvSubtitlesIntent
}
