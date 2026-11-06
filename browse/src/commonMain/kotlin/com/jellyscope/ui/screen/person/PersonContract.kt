// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.person

import com.jellyscope.ui.component.MediaCardUi

sealed interface PersonUiState {
    data object Loading : PersonUiState

    data class Error(
        val retryable: Boolean = true,
    ) : PersonUiState

    data class Content(
        val movies: List<MediaCardUi>,
        val series: List<MediaCardUi>,
        val header: PersonHeaderUi? = null,
        val totalCount: Int = movies.size + series.size,
        val startIndex: Int = 0,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: Boolean = false,
        val consumedOffset: Int = 0,
    ) : PersonUiState
}

data class PersonHeaderUi(
    val id: String,
    val name: String,
    val overview: String?,
    val imageUrl: String?,
    val backdropUrl: String?,
)

sealed interface PersonIntent {
    data object Retry : PersonIntent

    data object LoadMore : PersonIntent

    data object LoadMoreAutomatically : PersonIntent
}
