// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.collection

import com.jellyscope.ui.component.MediaCardUi

internal sealed interface CollectionChange {
    data object RetryPage : CollectionChange

    data class LoadStarted(
        val reset: Boolean,
    ) : CollectionChange

    data class PageLoaded(
        val reset: Boolean,
        val items: List<MediaCardUi>,
        val totalCount: Int,
        val startIndex: Int,
    ) : CollectionChange

    data object LoadFailed : CollectionChange
}

internal fun reduceCollection(
    current: CollectionUiState,
    change: CollectionChange,
): CollectionUiState =
    when (change) {
        CollectionChange.RetryPage -> current.copy(error = false)
        is CollectionChange.LoadStarted -> current.copy(isLoading = change.reset, isLoadingMore = !change.reset, error = false)
        is CollectionChange.PageLoaded -> {
            val items = if (change.reset) change.items else current.items + change.items
            current.copy(
                items = items,
                totalCount = change.totalCount,
                startIndex = change.startIndex,
                hasMore = items.size < change.totalCount,
                isLoading = false,
                isLoadingMore = false,
                error = false,
            )
        }
        CollectionChange.LoadFailed -> current.copy(isLoading = false, isLoadingMore = false, error = true)
    }
