// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal sealed interface TvSeriesChange {
    data object LoadStarted : TvSeriesChange

    data class SeasonsLoaded(
        val isCurrent: Boolean,
        val seasonsSucceeded: Boolean,
        val nextUpSucceeded: Boolean,
        val selectionChanged: Boolean,
        val seasons: List<TvSeason>,
        val seasonsError: TvErrorKind?,
        val nextUpError: TvErrorKind?,
        val nextUpCard: TvMediaCard?,
        val nextUpSeasonId: String?,
    ) : TvSeriesChange

    data class ActionFailed(
        val error: TvErrorKind,
    ) : TvSeriesChange

    data class EpisodesStarted(
        val seasonId: String,
    ) : TvSeriesChange

    data class EpisodesLoaded(
        val isCurrent: Boolean,
        val seasonId: String,
        val cards: List<TvMediaCard>,
    ) : TvSeriesChange

    data class EpisodesFailed(
        val isCurrent: Boolean,
        val seasonId: String,
        val error: TvErrorKind,
    ) : TvSeriesChange

    data class EpisodeChanged(
        val itemId: String,
        val transform: (TvMediaCard) -> TvMediaCard,
    ) : TvSeriesChange
}

internal fun reduceTvSeries(
    current: TvItemDetailState,
    change: TvSeriesChange,
): TvItemDetailState =
    when (change) {
        TvSeriesChange.LoadStarted ->
            run {
                current.copy(
                    seasonsLoading = true,
                    seasonsError = null,
                    nextUpLoading = true,
                    nextUpError = null,
                )
            }
        is TvSeriesChange.SeasonsLoaded ->
            with(change) {
                if (!isCurrent) {
                    current
                } else {
                    current.copy(
                        seasons =
                            if (seasonsSucceeded && !selectionChanged) seasons else current.seasons,
                        seasonsLoading = false,
                        seasonsError = seasonsError,
                        nextUpLoading = false,
                        nextUpError = nextUpError,
                        nextUpEpisodeId =
                            if (nextUpSucceeded) {
                                nextUpCard?.id.takeIf { nextUpSeasonId != null }
                            } else {
                                current.nextUpEpisodeId
                            },
                        nextUpEpisode =
                            if (nextUpSucceeded) nextUpCard else current.nextUpEpisode,
                    )
                }
            }
        is TvSeriesChange.ActionFailed ->
            with(change) {
                current.copy(actionError = error)
            }
        is TvSeriesChange.EpisodesStarted ->
            with(change) {
                current.copy(
                    selectedSeasonId = seasonId,
                    episodesLoading = true,
                    episodesError = null,
                )
            }
        is TvSeriesChange.EpisodesLoaded ->
            with(change) {
                if (isCurrent && current.selectedSeasonId == seasonId) {
                    current.copy(
                        episodes = cards,
                        episodesLoading = false,
                        episodesError = null,
                    )
                } else {
                    current
                }
            }
        is TvSeriesChange.EpisodesFailed ->
            with(change) {
                if (isCurrent && current.selectedSeasonId == seasonId) {
                    current.copy(
                        episodesLoading = false,
                        episodesError = error,
                    )
                } else {
                    current
                }
            }
        is TvSeriesChange.EpisodeChanged ->
            with(change) {
                current.copy(
                    episodes = current.episodes.map { episode -> if (episode.id == itemId) transform(episode) else episode },
                    nextUpEpisode = current.nextUpEpisode?.let { episode -> if (episode.id == itemId) transform(episode) else episode },
                    actionError = null,
                )
            }
    }
