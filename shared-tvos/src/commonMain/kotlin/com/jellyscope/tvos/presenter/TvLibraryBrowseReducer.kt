// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.DEFAULT_LIBRARY_PAGE_SIZE
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder

internal sealed interface TvLibraryBrowseChange {
    data class SortRestored(
        val sortBy: LibrarySortBy,
        val sortOrder: LibrarySortOrder,
    ) : TvLibraryBrowseChange

    data object AppendStarted : TvLibraryBrowseChange

    data class Refreshed(
        val generation: Long,
        val refreshPageGeneration: Long,
        val page: TvLibraryPageProjection,
        val windowSize: Int,
    ) : TvLibraryBrowseChange

    data object Started : TvLibraryBrowseChange

    data class Loaded(
        val generation: Long,
        val requestGeneration: Long,
        val page: TvLibraryPageProjection,
        val continuePaging: Boolean,
        val startIndex: Int,
    ) : TvLibraryBrowseChange

    data class Failed(
        val generation: Long,
        val requestGeneration: Long,
        val error: TvErrorKind,
    ) : TvLibraryBrowseChange

    data object FacetsLoading : TvLibraryBrowseChange

    data class FacetsLoaded(
        val groups: List<TvLibraryFilterGroup>,
    ) : TvLibraryBrowseChange

    data object FacetsReady : TvLibraryBrowseChange

    data object FacetsFailed : TvLibraryBrowseChange

    data class EditingChanged(
        val isEditing: Boolean,
        val draftCount: Int,
    ) : TvLibraryBrowseChange

    data class FilterGroupsLoaded(
        val generation: Long,
        val groups: List<TvLibraryFilterGroup>,
    ) : TvLibraryBrowseChange

    data class QueryReset(
        val transform: TvLibraryBrowseState.() -> TvLibraryBrowseState,
    ) : TvLibraryBrowseChange
}

internal fun reduceTvLibraryBrowse(
    current: TvLibraryBrowseState,
    change: TvLibraryBrowseChange,
    queryGeneration: Long,
    pageRequestGeneration: Long,
    filterProjectionGeneration: Long,
    appliedCount: Int,
): TvLibraryBrowseState =
    when (change) {
        is TvLibraryBrowseChange.SortRestored ->
            with(change) {
                current.copy(
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                )
            }
        TvLibraryBrowseChange.AppendStarted ->
            run {
                current.copy(isLoadingMore = true, error = null)
            }
        is TvLibraryBrowseChange.Refreshed ->
            with(change) {
                if (
                    generation != queryGeneration ||
                    refreshPageGeneration != pageRequestGeneration
                ) {
                    current
                } else {
                    current.copy(
                        items = page.items,
                        totalCount = page.totalCount,
                        consumedOffset = page.rawCount,
                        endReached = page.rawCount < windowSize,
                        error = null,
                    )
                }
            }
        TvLibraryBrowseChange.Started ->
            run {
                current.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    items = emptyList(),
                    totalCount = null,
                    consumedOffset = 0,
                    endReached = false,
                    error = null,
                )
            }
        is TvLibraryBrowseChange.Loaded ->
            with(change) {
                if (generation != queryGeneration || requestGeneration != pageRequestGeneration) {
                    current
                } else {
                    current.copy(
                        isLoading = false,
                        isLoadingMore = continuePaging,
                        items = page.items,
                        totalCount = page.totalCount,
                        consumedOffset = startIndex + page.rawCount,
                        endReached = page.rawCount < DEFAULT_LIBRARY_PAGE_SIZE,
                        error = null,
                    )
                }
            }
        is TvLibraryBrowseChange.Failed ->
            with(change) {
                if (generation != queryGeneration || requestGeneration != pageRequestGeneration) {
                    current
                } else {
                    current.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = error,
                    )
                }
            }
        TvLibraryBrowseChange.FacetsLoading ->
            run {
                current.copy(facetStatus = TvLibraryFacetStatus.Loading)
            }
        is TvLibraryBrowseChange.FacetsLoaded ->
            with(change) {
                current.copy(
                    facetStatus = TvLibraryFacetStatus.Content,
                    filterGroups = groups,
                )
            }
        TvLibraryBrowseChange.FacetsReady ->
            run {
                current.copy(facetStatus = TvLibraryFacetStatus.Content)
            }
        TvLibraryBrowseChange.FacetsFailed ->
            run {
                current.copy(facetStatus = TvLibraryFacetStatus.Error)
            }
        is TvLibraryBrowseChange.EditingChanged ->
            with(change) {
                current.copy(
                    isEditingFilters = isEditing,
                    appliedFilterCount = appliedCount,
                    draftFilterCount = draftCount,
                )
            }
        is TvLibraryBrowseChange.FilterGroupsLoaded ->
            with(change) {
                if (generation == filterProjectionGeneration) {
                    current.copy(filterGroups = groups)
                } else {
                    current
                }
            }
        is TvLibraryBrowseChange.QueryReset ->
            with(change) {
                current.transform().copy(
                    isLoading = true,
                    isLoadingMore = false,
                    items = emptyList(),
                    totalCount = null,
                    consumedOffset = 0,
                    endReached = false,
                    error = null,
                    queryRevision = current.queryRevision + 1,
                )
            }
    }
