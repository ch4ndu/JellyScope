// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.ui.component.MediaCardUi

/** Internal transitions produced by intent handling and asynchronous work. */
internal sealed interface LibraryBrowseChange {
    data class SortRestored(
        val sortBy: LibrarySortBy,
        val sortOrder: LibrarySortOrder,
    ) : LibraryBrowseChange

    data object RetryingPage : LibraryBrowseChange

    data class WindowRefreshed(
        val generation: Long,
        val items: List<MediaCardUi>,
        val startIndex: Int,
        val hasMore: Boolean,
        val receivedCount: Int = items.size,
    ) : LibraryBrowseChange

    data class SortSelected(
        val sortBy: LibrarySortBy,
        val sortOrder: LibrarySortOrder,
    ) : LibraryBrowseChange

    data class FiltersSelected(
        val filters: LibraryFilterSelection,
    ) : LibraryBrowseChange

    data object ShuffleStarted : LibraryBrowseChange

    data object ShuffleFinished : LibraryBrowseChange

    data class PageStarted(
        val reset: Boolean,
    ) : LibraryBrowseChange

    data class PageLoaded(
        val generation: Long,
        val reset: Boolean,
        val items: List<MediaCardUi>,
        val startIndex: Int,
        val hasMore: Boolean,
        val receivedCount: Int = items.size,
        val continueLoading: Boolean = false,
    ) : LibraryBrowseChange

    data class PageFailed(
        val generation: Long,
    ) : LibraryBrowseChange

    data class FacetsLoaded(
        val facets: LibraryFacets,
        val options: LibraryFilterOptions,
    ) : LibraryBrowseChange
}

/** Pure transitions; the ViewModel supplies the current request generation for admission. */
internal fun reduceLibraryBrowse(
    current: LibraryBrowseUiState,
    change: LibraryBrowseChange,
    currentGeneration: Long,
): LibraryBrowseUiState =
    when (change) {
        is LibraryBrowseChange.SortRestored -> {
            current.copy(sortBy = change.sortBy, sortOrder = change.sortOrder)
        }
        LibraryBrowseChange.RetryingPage -> {
            current.copy(error = false)
        }
        is LibraryBrowseChange.WindowRefreshed -> {
            if (currentGeneration != change.generation) {
                current
            } else {
                val items = change.items.distinctBy { it.id }
                current.copy(
                    items = items,
                    totalCount = items.size,
                    startIndex = change.startIndex,
                    consumedOffset = change.startIndex + change.receivedCount,
                    hasMore = change.hasMore,
                    isLoading = false,
                    isLoadingMore = false,
                    error = current.error,
                )
            }
        }
        is LibraryBrowseChange.SortSelected -> {
            current.copy(
                sortBy = change.sortBy,
                sortOrder = change.sortOrder,
                items = emptyList(),
                totalCount = 0,
                startIndex = 0,
                consumedOffset = 0,
                hasMore = false,
                isLoading = true,
                isLoadingMore = false,
                error = false,
            )
        }
        is LibraryBrowseChange.FiltersSelected -> {
            current.copy(
                filters = change.filters,
                items = emptyList(),
                totalCount = 0,
                startIndex = 0,
                consumedOffset = 0,
                hasMore = false,
                isLoading = true,
                isLoadingMore = false,
                error = false,
            )
        }
        LibraryBrowseChange.ShuffleStarted -> {
            current.copy(isPreparingShuffle = true)
        }
        LibraryBrowseChange.ShuffleFinished -> {
            current.copy(isPreparingShuffle = false)
        }
        is LibraryBrowseChange.PageStarted -> {
            current.copy(
                isLoading = change.reset || current.items.isEmpty(),
                isLoadingMore = !change.reset && current.items.isNotEmpty(),
                error = false,
            )
        }
        is LibraryBrowseChange.PageLoaded -> {
            if (currentGeneration != change.generation) {
                current
            } else {
                val nextItems =
                    if (change.reset) {
                        change.items.distinctBy { it.id }
                    } else {
                        val ids = current.items.mapTo(mutableSetOf()) { it.id }
                        val additions = change.items.filter { ids.add(it.id) }
                        if (additions.isEmpty()) current.items else current.items + additions
                    }
                current.copy(
                    items = nextItems,
                    // The exact server total is skipped for speed; surface the
                    // loaded count and page by whether the last page was full.
                    totalCount = nextItems.size,
                    startIndex = change.startIndex,
                    consumedOffset = change.startIndex + change.receivedCount,
                    hasMore = change.hasMore,
                    isLoading = change.continueLoading && nextItems.isEmpty(),
                    isLoadingMore = change.continueLoading && nextItems.isNotEmpty(),
                    error = false,
                )
            }
        }
        is LibraryBrowseChange.PageFailed -> {
            if (currentGeneration != change.generation) {
                current
            } else {
                current.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = true,
                )
            }
        }
        is LibraryBrowseChange.FacetsLoaded -> {
            current.copy(
                facets = change.facets,
                filterOptions = change.options,
            )
        }
    }
