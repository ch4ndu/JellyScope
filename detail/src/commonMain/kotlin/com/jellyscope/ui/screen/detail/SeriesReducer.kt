// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

/** Episode/header transforms are existing pure copies; cache updates stay ViewModel-owned. */
internal sealed interface SeriesChange {
    data object Loading : SeriesChange

    data class EpisodesUpdated(
        val seasonId: String,
        val episodesState: SeasonEpisodesUiState,
    ) : SeriesChange

    data class SourceProjected(
        val selectedEpisode: EpisodeUi,
    ) : SeriesChange

    data class Loaded(
        val nextState: SeriesUiState,
    ) : SeriesChange

    data class EpisodeUpdated(
        val episodeId: String,
        val transform: (EpisodeUi) -> EpisodeUi,
    ) : SeriesChange

    data class NextUpRestored(
        val episodeId: String,
    ) : SeriesChange

    data class HeaderUpdated(
        val seriesId: String,
        val transform: (SeriesHeaderUi) -> SeriesHeaderUi,
    ) : SeriesChange
}

internal fun reduceSeries(
    current: SeriesUiState,
    change: SeriesChange,
): SeriesUiState =
    when (change) {
        SeriesChange.Loading -> SeriesUiState.Loading
        is SeriesChange.EpisodesUpdated ->
            (current as? SeriesUiState.Content)?.let {
                it.copy(content = it.content.withEpisodesState(change.seasonId, change.episodesState))
            } ?: current
        is SeriesChange.SourceProjected ->
            with(change) {
                val currentContent = current as? SeriesUiState.Content ?: return@with current
                val content = currentContent.content
                val episodesState =
                    (content.episodesState as? SeasonEpisodesUiState.Content)?.let { episodes ->
                        SeasonEpisodesUiState.Content(
                            episodes.episodes.map { episode ->
                                if (episode.itemId == selectedEpisode.itemId) {
                                    episode.withSourceProjectionFrom(selectedEpisode)
                                } else {
                                    episode
                                }
                            },
                        )
                    } ?: content.episodesState
                currentContent.copy(
                    content =
                        content.copy(
                            episodesState = episodesState,
                            focusedEpisode =
                                content.focusedEpisode?.let { episode ->
                                    if (episode.itemId == selectedEpisode.itemId) {
                                        episode.withSourceProjectionFrom(selectedEpisode)
                                    } else {
                                        episode
                                    }
                                },
                            nextUpEpisode =
                                content.nextUpEpisode?.let { episode ->
                                    if (episode.itemId == selectedEpisode.itemId) {
                                        episode.withSourceProjectionFrom(selectedEpisode)
                                    } else {
                                        episode
                                    }
                                },
                            seriesPlayEpisode =
                                content.seriesPlayEpisode?.let { episode ->
                                    if (episode.itemId == selectedEpisode.itemId) {
                                        episode.withSourceProjectionFrom(selectedEpisode)
                                    } else {
                                        episode
                                    }
                                },
                        ),
                )
            }
        is SeriesChange.Loaded ->
            with(change) {
                nextState
            }
        is SeriesChange.EpisodeUpdated ->
            with(change) {
                val currentContent = current as? SeriesUiState.Content ?: return@with current
                val content = currentContent.content
                val updateEpisode = { episode: EpisodeUi ->
                    if (episode.itemId == episodeId) transform(episode) else episode
                }
                currentContent.copy(
                    content =
                        content.copy(
                            episodesState =
                                (content.episodesState as? SeasonEpisodesUiState.Content)?.let { episodes ->
                                    SeasonEpisodesUiState.Content(episodes.episodes.map(updateEpisode))
                                } ?: content.episodesState,
                            focusedEpisode = content.focusedEpisode?.let(updateEpisode),
                            nextUpEpisode = content.nextUpEpisode?.let(updateEpisode),
                            seriesPlayEpisode = content.seriesPlayEpisode?.let(updateEpisode),
                        ),
                )
            }
        is SeriesChange.NextUpRestored ->
            with(change) {
                val currentContent = current as? SeriesUiState.Content ?: return@with current
                val content = currentContent.content
                val episode =
                    (content.episodesState as? SeasonEpisodesUiState.Content)
                        ?.episodes
                        ?.firstOrNull { candidate -> candidate.itemId == episodeId }
                        ?: return@with current
                if (episode.isWatched) {
                    current
                } else {
                    currentContent.copy(
                        content =
                            content.copy(
                                nextUpEpisode = episode,
                                seriesPlayEpisode = episode,
                            ),
                    )
                }
            }
        is SeriesChange.HeaderUpdated ->
            with(change) {
                val currentContent = current as? SeriesUiState.Content ?: return@with current
                val content = currentContent.content
                if (content.series.itemId != seriesId) {
                    current
                } else {
                    currentContent.copy(content = content.copy(series = transform(content.series)))
                }
            }
    }

private fun SeriesContentUi.withEpisodesState(
    seasonId: String,
    episodesState: SeasonEpisodesUiState,
): SeriesContentUi {
    val episodes = (episodesState as? SeasonEpisodesUiState.Content)?.episodes.orEmpty()
    val focused =
        focusedEpisode
            ?.takeIf { selectedSeasonId == seasonId }
            ?.let { previous -> episodes.firstOrNull { episode -> episode.itemId == previous.itemId } }
            ?: episodes.firstOrNull()
    val refreshedNextUp =
        nextUpEpisode
            ?.let { nextUp -> episodes.firstOrNull { episode -> episode.itemId == nextUp.itemId } ?: nextUp }
            ?.takeUnless { episode -> episode.isWatched }
    return copy(
        selectedSeasonId = seasonId,
        episodesState = episodesState,
        focusedEpisode = focused,
        nextUpEpisode = refreshedNextUp,
        seriesPlayEpisode =
            refreshedNextUp
                ?: episodes.nextPlayableEpisode()
                ?: seriesPlayEpisode.takeIf { episodesState is SeasonEpisodesUiState.Loading },
    )
}
