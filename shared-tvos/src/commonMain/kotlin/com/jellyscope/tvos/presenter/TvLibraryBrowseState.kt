// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder

enum class TvLibraryFacetStatus {
    Loading,
    Content,
    Error,
}

data class TvLibrarySortOption(
    val sortBy: LibrarySortBy,
)

data class TvLibraryBrowseState(
    val collectionType: LibraryCollectionType,
    val sortOptions: List<TvLibrarySortOption>,
    val sortBy: LibrarySortBy = LibrarySortBy.Name,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.Ascending,
    val filterGroups: List<TvLibraryFilterGroup> = emptyList(),
    val appliedFilterCount: Int = 0,
    val draftFilterCount: Int = 0,
    val isEditingFilters: Boolean = false,
    val facetStatus: TvLibraryFacetStatus = TvLibraryFacetStatus.Loading,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val items: List<TvMediaCard> = emptyList(),
    val totalCount: Int? = null,
    val consumedOffset: Int = 0,
    val endReached: Boolean = false,
    val error: TvErrorKind? = null,
    val queryRevision: Long = 0L,
)
