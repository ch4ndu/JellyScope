// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import com.jellyscope.ui.component.MediaCardUi

sealed interface SeriesUiState {
    data object Loading : SeriesUiState

    data class Error(
        val retryable: Boolean = true,
    ) : SeriesUiState

    data class Content(
        val content: SeriesContentUi,
    ) : SeriesUiState
}

data class SeriesContentUi(
    val series: SeriesHeaderUi,
    val seasons: List<SeasonUi>,
    val selectedSeasonId: String?,
    val episodesState: SeasonEpisodesUiState,
    val focusedEpisode: EpisodeUi?,
    val nextUpEpisode: EpisodeUi?,
    val seriesPlayEpisode: EpisodeUi?,
    val related: List<MediaCardUi>,
)

data class SeriesHeaderUi(
    val itemId: String,
    val title: String,
    val metadataLine: String?,
    val genresLine: String?,
    val officialRating: String?,
    val communityRating: String?,
    val criticRatingText: String?,
    val overview: String?,
    val castAndCrew: List<CastAndCrewUi>,
    val isFavorite: Boolean,
    val favoriteToggleInFlight: Boolean,
    val backdropUrl: String?,
    val posterUrl: String?,
)

data class SeasonUi(
    val id: String,
    val title: String,
    val indexNumber: Int?,
    val unplayedCount: Int?,
    val posterUrl: String?,
)

sealed interface SeasonEpisodesUiState {
    data object Loading : SeasonEpisodesUiState

    data object Empty : SeasonEpisodesUiState

    data object Error : SeasonEpisodesUiState

    data class Content(
        val episodes: List<EpisodeUi>,
    ) : SeasonEpisodesUiState
}

data class EpisodeUi(
    val itemId: String,
    val title: String,
    val seriesTitle: String,
    val episodeLabel: String?,
    val episodeBadge: String?,
    val metadataLine: String?,
    val overview: String?,
    val imageUrl: String?,
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
    val versions: List<MediaVersionUi> = emptyList(),
    val selectedMediaSourceId: String? = null,
)
