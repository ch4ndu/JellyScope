// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

internal sealed interface TvPersonChange {
    data object AppendStarted : TvPersonChange

    data object HeaderStarted : TvPersonChange

    data class HeaderLoaded(
        val header: TvPersonHeader,
    ) : TvPersonChange

    data class HeaderFailed(
        val error: TvErrorKind,
    ) : TvPersonChange

    data object Started : TvPersonChange

    data class Loaded(
        val generation: Long,
        val page: TvPersonPageProjection,
        val continuePaging: Boolean,
        val startIndex: Int,
    ) : TvPersonChange

    data class Failed(
        val generation: Long,
        val error: TvErrorKind,
    ) : TvPersonChange
}

internal fun reduceTvPerson(
    current: TvPersonState,
    change: TvPersonChange,
    pageGeneration: Long,
): TvPersonState =
    when (change) {
        TvPersonChange.AppendStarted ->
            run {
                current.copy(
                    isLoading = current.movies.isEmpty() && current.shows.isEmpty(),
                    isLoadingMore = current.movies.isNotEmpty() || current.shows.isNotEmpty(),
                    error = null,
                )
            }
        TvPersonChange.HeaderStarted ->
            run {
                current.copy(headerLoading = true, headerError = null)
            }
        is TvPersonChange.HeaderLoaded ->
            with(change) {
                current.copy(headerLoading = false, header = header, headerError = null)
            }
        is TvPersonChange.HeaderFailed ->
            with(change) {
                current.copy(headerLoading = false, headerError = error)
            }
        TvPersonChange.Started ->
            run {
                current.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    movies = emptyList(),
                    shows = emptyList(),
                    totalCount = null,
                    consumedOffset = 0,
                    endReached = false,
                    error = null,
                )
            }
        is TvPersonChange.Loaded ->
            with(change) {
                if (generation == pageGeneration) {
                    current.copy(
                        isLoading = continuePaging && page.movies.isEmpty() && page.shows.isEmpty(),
                        isLoadingMore = continuePaging && (page.movies.isNotEmpty() || page.shows.isNotEmpty()),
                        movies = page.movies,
                        shows = page.shows,
                        totalCount = page.totalCount,
                        consumedOffset = startIndex + page.rawCount,
                        endReached = page.rawCount == 0 || startIndex + page.rawCount >= page.totalCount,
                        error = null,
                    )
                } else {
                    current
                }
            }
        is TvPersonChange.Failed ->
            with(change) {
                if (generation == pageGeneration) {
                    current.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = error,
                    )
                } else {
                    current
                }
            }
    }
