// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.WatchedFilter

data class TvSearchState(
    val query: String = "",
    val selectedGenres: List<TvSearchGenre> = emptyList(),
    val runtimeBucket: RuntimeBucket = RuntimeBucket.Any,
    val watchedFilter: WatchedFilter = WatchedFilter.Any,
    val selectedPerson: TvSearchPerson? = null,
    val personSuggestions: List<TvSearchPerson> = emptyList(),
    val selectedResultCategory: TvSearchResultCategory = TvSearchResultCategory.All,
    val isSearching: Boolean = false,
    val movies: List<TvMediaCard> = emptyList(),
    val shows: List<TvMediaCard> = emptyList(),
    val episodes: List<TvMediaCard> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val error: TvErrorKind? = null,
) {
    val hasActiveQuery: Boolean
        get() =
            query.isNotBlank() ||
                selectedPerson != null ||
                selectedGenres.isNotEmpty() ||
                runtimeBucket != RuntimeBucket.Any ||
                watchedFilter != WatchedFilter.Any

    val hasResults: Boolean
        get() = movies.isNotEmpty() || shows.isNotEmpty() || episodes.isNotEmpty()

    val visibleResultSections: List<TvSearchResultSection>
        get() = resultSections(selectedResultCategory, movies, shows, episodes)

    val hasVisibleResults: Boolean
        get() = visibleResultSections.isNotEmpty()
}
