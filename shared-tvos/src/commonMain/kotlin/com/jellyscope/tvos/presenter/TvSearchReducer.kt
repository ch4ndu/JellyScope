// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.WatchedFilter

internal sealed interface TvSearchChange {
    data class QueryChanged(
        val normalized: String,
    ) : TvSearchChange

    data class Submitted(
        val normalized: String,
        val preservesPerson: Boolean,
    ) : TvSearchChange

    data class GenreToggled(
        val genre: TvSearchGenre,
    ) : TvSearchChange

    data class RuntimeChanged(
        val bucket: RuntimeBucket,
    ) : TvSearchChange

    data class WatchedChanged(
        val filter: WatchedFilter,
    ) : TvSearchChange

    data class PersonSelected(
        val person: TvSearchPerson,
    ) : TvSearchChange

    data object PersonCleared : TvSearchChange

    data class CategorySelected(
        val category: TvSearchResultCategory,
    ) : TvSearchChange

    data object RecentsCleared : TvSearchChange

    data object SearchStarted : TvSearchChange

    data class SearchFailed(
        val suggestions: List<TvSearchPerson>,
    ) : TvSearchChange

    data class ResultsLoaded(
        val movies: List<TvMediaCard>,
        val shows: List<TvMediaCard>,
        val episodes: List<TvMediaCard>,
        val suggestions: List<TvSearchPerson>,
    ) : TvSearchChange

    data object Idle : TvSearchChange

    data class RecentsLoaded(
        val recents: List<String>,
    ) : TvSearchChange

    data class SuggestionsLoaded(
        val suggestions: List<TvSearchPerson>,
    ) : TvSearchChange
}

internal fun reduceTvSearch(
    current: TvSearchState,
    change: TvSearchChange,
): TvSearchState =
    when (change) {
        is TvSearchChange.QueryChanged ->
            with(change) {
                current.copy(
                    query = normalized,
                    selectedPerson = null,
                    personSuggestions = emptyList(),
                    error = null,
                )
            }
        is TvSearchChange.Submitted ->
            with(change) {
                current.copy(
                    query = normalized,
                    selectedPerson = current.selectedPerson.takeIf { preservesPerson },
                    personSuggestions = emptyList(),
                    error = null,
                )
            }
        is TvSearchChange.GenreToggled ->
            with(change) {
                val genres =
                    if (genre in current.selectedGenres) {
                        current.selectedGenres - genre
                    } else {
                        current.selectedGenres + genre
                    }
                current.copy(selectedGenres = genres, error = null)
            }
        is TvSearchChange.RuntimeChanged ->
            with(change) {
                current.copy(runtimeBucket = bucket, error = null)
            }
        is TvSearchChange.WatchedChanged ->
            with(change) {
                current.copy(watchedFilter = filter, error = null)
            }
        is TvSearchChange.PersonSelected ->
            with(change) {
                current.copy(
                    query = person.name,
                    selectedPerson = person,
                    personSuggestions = emptyList(),
                    error = null,
                )
            }
        TvSearchChange.PersonCleared ->
            run {
                current.copy(
                    query = "",
                    selectedPerson = null,
                    personSuggestions = emptyList(),
                    error = null,
                )
            }
        is TvSearchChange.CategorySelected ->
            with(change) {
                current.copy(selectedResultCategory = category)
            }
        TvSearchChange.RecentsCleared ->
            run {
                current.copy(recentSearches = emptyList())
            }
        TvSearchChange.SearchStarted ->
            run {
                current.copy(isSearching = true, error = null)
            }
        is TvSearchChange.SearchFailed ->
            with(change) {
                current.copy(
                    isSearching = false,
                    personSuggestions = suggestions,
                    error = TvErrorKind.Network,
                )
            }
        is TvSearchChange.ResultsLoaded ->
            with(change) {
                current.copy(
                    isSearching = false,
                    movies = movies,
                    shows = shows,
                    episodes = episodes,
                    personSuggestions = suggestions,
                    error = null,
                )
            }
        TvSearchChange.Idle ->
            run {
                current.copy(
                    isSearching = false,
                    movies = emptyList(),
                    shows = emptyList(),
                    episodes = emptyList(),
                    personSuggestions = emptyList(),
                    selectedResultCategory = TvSearchResultCategory.All,
                    error = null,
                )
            }
        is TvSearchChange.RecentsLoaded ->
            with(change) {
                current.copy(recentSearches = recents)
            }
        is TvSearchChange.SuggestionsLoaded ->
            with(change) {
                current.copy(personSuggestions = suggestions)
            }
    }
