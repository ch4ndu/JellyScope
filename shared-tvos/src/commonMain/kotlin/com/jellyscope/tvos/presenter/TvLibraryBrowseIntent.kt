// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder

sealed interface TvLibraryBrowseIntent {
    data object Load : TvLibraryBrowseIntent

    data object Retry : TvLibraryBrowseIntent

    data class LoadMoreIfNeeded(
        val focusedIndex: Int,
    ) : TvLibraryBrowseIntent

    data object RefreshSilently : TvLibraryBrowseIntent

    data class SetSort(
        val sortBy: LibrarySortBy,
        val sortOrder: LibrarySortOrder,
    ) : TvLibraryBrowseIntent

    data object BeginEditingFilters : TvLibraryBrowseIntent

    data class ToggleDraftFilter(
        val group: TvLibraryFilterGroupKind,
        val key: String,
    ) : TvLibraryBrowseIntent

    data object ResetDraftFilters : TvLibraryBrowseIntent

    data object ApplyFilters : TvLibraryBrowseIntent

    data object CancelFilterEditing : TvLibraryBrowseIntent

    data object RetryFilters : TvLibraryBrowseIntent
}
