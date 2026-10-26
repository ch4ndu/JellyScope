// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.ui.component.MediaCardUi

data class LibraryBrowseUiState(
    val parentId: String?,
    val collectionType: LibraryCollectionType = LibraryCollectionType.Other,
    val sortBy: LibrarySortBy = LibrarySortBy.Name,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.Ascending,
    val filters: LibraryFilterSelection = LibraryFilterSelection(),
    val items: List<MediaCardUi> = emptyList(),
    val totalCount: Int = 0,
    val startIndex: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val facets: LibraryFacets = LibraryFacets(),
    val filterOptions: LibraryFilterOptions = LibraryFilterOptions(),
    val error: Boolean = false,
    val isPreparingShuffle: Boolean = false,
)

sealed interface LibraryBrowseEvent {
    data class LaunchShuffle(
        val itemIds: List<String>,
    ) : LibraryBrowseEvent

    data object ShuffleEmpty : LibraryBrowseEvent

    data object ShuffleUnavailable : LibraryBrowseEvent
}

data class LibraryFilterOptions(
    val genres: List<LibraryFilterOption<String>> = emptyList(),
    val years: List<LibraryFilterOption<Int>> = emptyList(),
    val ratings: List<LibraryFilterOption<String>> = emptyList(),
    val studios: List<LibraryFilterOption<String>> = emptyList(),
)

data class LibraryFilterOption<T>(
    val key: String,
    val value: T,
    val label: String,
)

/** Screen inputs; buffered shuffle output remains separate from durable state. */
sealed interface LibraryBrowseIntent {
    data object Retry : LibraryBrowseIntent

    data object Refresh : LibraryBrowseIntent

    data object LoadMore : LibraryBrowseIntent

    data class SetSort(
        val sortBy: LibrarySortBy,
        val sortOrder: LibrarySortOrder,
    ) : LibraryBrowseIntent

    data class SetFilters(
        val filters: LibraryFilterSelection,
    ) : LibraryBrowseIntent

    data object ShuffleAll : LibraryBrowseIntent
}
