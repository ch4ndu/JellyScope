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
        val receivedCount: Int,
        val hasMore: Boolean,
        val continueLoading: Boolean,
    ) : CollectionChange

    data object LoadFailed : CollectionChange
}

internal fun reduceCollection(
    current: CollectionUiState,
    change: CollectionChange,
): CollectionUiState =
    when (change) {
        CollectionChange.RetryPage -> current.copy(error = false)
        is CollectionChange.LoadStarted ->
            current.copy(
                isLoading = change.reset || current.items.isEmpty(),
                isLoadingMore = !change.reset && current.items.isNotEmpty(),
                error = false,
            )
        is CollectionChange.PageLoaded -> {
            val items = (if (change.reset) change.items else current.items + change.items).distinctBy { it.id }
            current.copy(
                items = items,
                totalCount = change.totalCount,
                startIndex = change.startIndex,
                consumedOffset = change.startIndex + change.receivedCount,
                hasMore = change.hasMore,
                isLoading = change.continueLoading && items.isEmpty(),
                isLoadingMore = change.continueLoading && items.isNotEmpty(),
                error = false,
            )
        }
        CollectionChange.LoadFailed -> current.copy(isLoading = false, isLoadingMore = false, error = true)
    }
