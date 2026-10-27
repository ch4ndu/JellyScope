// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

sealed interface SeriesIntent {
    data object Retry : SeriesIntent

    data object RetryEpisodes : SeriesIntent

    data object Refresh : SeriesIntent

    data class SelectSeason(
        val seasonId: String,
    ) : SeriesIntent

    data class FocusEpisode(
        val itemId: String,
    ) : SeriesIntent

    data class SelectEpisodeMediaVersion(
        val itemId: String,
        val mediaSourceId: String,
    ) : SeriesIntent

    data object ToggleFocusedEpisodeWatched : SeriesIntent

    data object ToggleSeriesFavorite : SeriesIntent

    data object ToggleFavorite : SeriesIntent

    data object ToggleFocusedEpisodeFavorite : SeriesIntent
}
