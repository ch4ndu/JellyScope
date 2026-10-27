// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.grid

import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.home.HomeRow

sealed interface GridUiState {
    data object Loading : GridUiState

    data class Error(
        val retryable: Boolean = true,
    ) : GridUiState

    data class Content(
        val items: List<MediaCardUi>,
        val sort: GridSort,
        val row: HomeRow,
    ) : GridUiState
}

enum class GridSort {
    Default,
    Title,
    DateAdded,
    ReleaseDate,
    Runtime,
}

sealed interface GridIntent {
    data object Retry : GridIntent

    data class SetSort(
        val sort: GridSort,
    ) : GridIntent

    data object BuildShuffleQueue : GridIntent
}
