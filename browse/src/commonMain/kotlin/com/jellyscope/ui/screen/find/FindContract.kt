// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.ui.component.MediaCardUi
import kotlin.jvm.JvmInline

data class FindUiState(
    val queryText: String = "",
    val selectedGenreNames: List<String> = emptyList(),
    val runtimeBucket: RuntimeBucket = RuntimeBucket.Any,
    val watchedFilter: WatchedFilter = WatchedFilter.Any,
    val selectedPerson: PersonUi? = null,
    val personSuggestions: List<PersonUi> = emptyList(),
    val groupedResults: FindResultsUi = FindResultsUi(),
    val selectedResultTab: FindResultTab = FindResultTab.All,
    val isSearching: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val error: Boolean = false,
    val activeRequestToken: FindRequestToken? = null,
    val completedRequestToken: FindRequestToken? = null,
)

@JvmInline
value class FindRequestToken internal constructor(
    val value: Long,
)

data class FindResultsUi(
    val movies: List<MediaCardUi> = emptyList(),
    val shows: List<MediaCardUi> = emptyList(),
    val episodes: List<MediaCardUi> = emptyList(),
) {
    val isEmpty: Boolean
        get() = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty()
}

data class PersonUi(
    val id: String,
    val name: String,
    val imageUrl: String?,
)

enum class FindResultTab {
    All,
    Movies,
    Shows,
    Episodes,
}

/** Inputs from the shared and Android TV Find screens; effects remain ViewModel-owned. */
sealed interface FindIntent {
    data class QueryChanged(
        val text: String,
    ) : FindIntent

    data object ClearQuery : FindIntent

    data class ToggleGenre(
        val name: String,
    ) : FindIntent

    data class SelectRuntime(
        val bucket: RuntimeBucket,
    ) : FindIntent

    data class SelectWatchedFilter(
        val filter: WatchedFilter,
    ) : FindIntent

    data class SelectPerson(
        val person: PersonUi,
    ) : FindIntent

    data class SelectResultTab(
        val tab: FindResultTab,
    ) : FindIntent

    data object ClearRecentSearches : FindIntent

    data object CommitRecentSearch : FindIntent

    data object Retry : FindIntent

    data object Refresh : FindIntent

    /** Submission returns a request token synchronously for TV's local focus handoff. */
    sealed interface Submission : FindIntent

    data object SubmitSearch : Submission

    data class SelectRecentSearch(
        val search: String,
    ) : Submission
}
