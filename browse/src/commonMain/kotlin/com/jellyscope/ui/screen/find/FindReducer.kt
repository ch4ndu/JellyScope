// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.WatchedFilter

internal sealed interface FindChange {
    data class QueryChanged(
        val text: String,
    ) : FindChange

    data object QueryCleared : FindChange

    data class GenreToggled(
        val genreName: String,
    ) : FindChange

    data class RuntimeSelected(
        val bucket: RuntimeBucket,
    ) : FindChange

    data class WatchedFilterSelected(
        val filter: WatchedFilter,
    ) : FindChange

    data class PersonSelected(
        val person: PersonUi,
    ) : FindChange

    data class RecentSearchSelected(
        val search: String,
    ) : FindChange

    data object RecentSearchesCleared : FindChange

    data class ResultTabSelected(
        val tab: FindResultTab,
    ) : FindChange

    data class RecentSearchCommitted(
        val label: String,
        val persisted: List<String>?,
    ) : FindChange

    data object SearchInactive : FindChange

    data class EmptySearchSubmitted(
        val token: FindRequestToken,
    ) : FindChange

    data class SearchStarted(
        val token: FindRequestToken,
    ) : FindChange

    data class SearchSucceeded(
        val token: FindRequestToken,
        val suggestions: List<PersonUi>,
        val results: FindResultsUi,
        val persistedRecents: List<String>?,
        val recentLabel: String?,
    ) : FindChange

    data class SearchFailed(
        val token: FindRequestToken,
        val suggestions: List<PersonUi>,
    ) : FindChange

    data class RecentSearchesLoaded(
        val searches: List<String>,
    ) : FindChange

    data class SuggestionsRefreshed(
        val queryText: String,
        val suggestions: List<PersonUi>,
    ) : FindChange
}

/** Pure state transitions: no jobs, API calls, persistence, or UI focus operations. */
internal fun reduceFind(
    current: FindUiState,
    change: FindChange,
): FindUiState =
    when (change) {
        is FindChange.QueryChanged -> {
            current.copy(
                queryText = change.text,
                selectedPerson = null,
                isSearching = false,
                error = false,
                activeRequestToken = null,
                completedRequestToken = null,
            )
        }
        FindChange.QueryCleared -> {
            current.copy(
                queryText = "",
                selectedPerson = null,
                personSuggestions = emptyList(),
                groupedResults = FindResultsUi(),
                isSearching = false,
                error = false,
                activeRequestToken = null,
                completedRequestToken = null,
            )
        }
        is FindChange.GenreToggled -> {
            val nextGenres =
                if (change.genreName in current.selectedGenreNames) {
                    current.selectedGenreNames - change.genreName
                } else {
                    current.selectedGenreNames + change.genreName
                }
            current.copy(selectedGenreNames = nextGenres, error = false)
        }
        is FindChange.RuntimeSelected -> {
            current.copy(runtimeBucket = change.bucket, error = false)
        }
        is FindChange.WatchedFilterSelected -> {
            current.copy(watchedFilter = change.filter, error = false)
        }
        is FindChange.PersonSelected -> {
            current.copy(
                selectedPerson = change.person,
                personSuggestions = emptyList(),
                error = false,
            )
        }
        is FindChange.RecentSearchSelected -> {
            current.copy(
                queryText = change.search,
                selectedPerson = null,
                error = false,
            )
        }
        FindChange.RecentSearchesCleared -> {
            current.copy(recentSearches = emptyList())
        }
        is FindChange.ResultTabSelected -> {
            current.copy(selectedResultTab = change.tab)
        }
        is FindChange.RecentSearchCommitted -> {
            current.copy(
                recentSearches = change.persisted ?: current.recentSearches.withRecent(change.label),
            )
        }
        FindChange.SearchInactive -> {
            current.copy(
                personSuggestions = emptyList(),
                groupedResults = FindResultsUi(),
                isSearching = false,
                error = false,
                activeRequestToken = null,
                completedRequestToken = null,
            )
        }
        is FindChange.EmptySearchSubmitted -> {
            current.copy(
                personSuggestions = emptyList(),
                groupedResults = FindResultsUi(),
                isSearching = false,
                error = false,
                activeRequestToken = null,
                completedRequestToken = change.token,
            )
        }
        is FindChange.SearchStarted -> {
            current.copy(
                isSearching = true,
                error = false,
                activeRequestToken = change.token,
                completedRequestToken = null,
            )
        }
        is FindChange.SearchSucceeded -> {
            if (current.activeRequestToken != change.token) {
                current
            } else {
                current.copy(
                    personSuggestions = change.suggestions,
                    groupedResults = change.results,
                    isSearching = false,
                    recentSearches =
                        change.persistedRecents ?: current.recentSearches.withRecent(change.recentLabel),
                    error = false,
                    activeRequestToken = null,
                    completedRequestToken = change.token,
                )
            }
        }
        is FindChange.SearchFailed -> {
            if (current.activeRequestToken != change.token) {
                current
            } else {
                current.copy(
                    personSuggestions = change.suggestions,
                    isSearching = false,
                    error = true,
                    activeRequestToken = null,
                    completedRequestToken = change.token,
                )
            }
        }
        is FindChange.RecentSearchesLoaded -> {
            current.copy(recentSearches = change.searches)
        }
        is FindChange.SuggestionsRefreshed -> {
            if (current.queryText == change.queryText && current.selectedPerson == null) {
                current.copy(personSuggestions = change.suggestions)
            } else {
                current
            }
        }
    }

private fun List<String>.withRecent(search: String?): List<String> {
    val label = search?.trim()?.takeIf { it.isNotBlank() } ?: return this
    return (listOf(label) + filterNot { existing -> existing.equals(label, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)
}

private const val MAX_RECENT_SEARCHES = 8
