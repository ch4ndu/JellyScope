// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.collection

import com.jellyscope.ui.component.MediaCardUi

data class CollectionUiState(
    val collectionId: String,
    val items: List<MediaCardUi> = emptyList(),
    val totalCount: Int = 0,
    val startIndex: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: Boolean = false,
    val consumedOffset: Int = 0,
)

sealed interface CollectionIntent {
    data object Retry : CollectionIntent

    data object LoadMore : CollectionIntent
}
