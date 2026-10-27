// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.grid

import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.home.HomeRow

internal sealed interface GridChange {
    data object Loading : GridChange

    data class Failed(
        val retryable: Boolean = true,
    ) : GridChange

    data class Loaded(
        val items: List<MediaCardUi>,
        val sort: GridSort,
        val row: HomeRow,
    ) : GridChange

    data class Sorted(
        val sort: GridSort,
        val items: List<MediaCardUi>,
        val generation: Long,
    ) : GridChange
}

internal fun reduceGrid(
    current: GridUiState,
    change: GridChange,
    generation: Long,
): GridUiState =
    when (change) {
        GridChange.Loading -> GridUiState.Loading
        is GridChange.Failed -> GridUiState.Error(change.retryable)
        is GridChange.Loaded -> GridUiState.Content(change.items, change.sort, change.row)
        is GridChange.Sorted ->
            if (generation != change.generation) {
                current
            } else {
                (current as? GridUiState.Content)?.copy(sort = change.sort, items = change.items) ?: current
            }
    }
