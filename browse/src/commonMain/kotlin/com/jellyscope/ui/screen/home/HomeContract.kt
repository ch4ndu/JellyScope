// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

import androidx.compose.runtime.Immutable
import com.jellyscope.ui.component.MediaCardUi

data class HomeUiState(
    val featured: RowState = RowState.Loading,
    val continueWatching: RowState = RowState.Loading,
    val nextUp: RowState = RowState.Loading,
    val recentlyAdded: RowState = RowState.Loading,
    val favorites: RowState = RowState.Loading,
) {
    fun rowState(row: HomeRow): RowState =
        when (row) {
            HomeRow.ContinueWatching -> continueWatching
            HomeRow.NextUp -> nextUp
            HomeRow.RecentlyAdded -> recentlyAdded
            HomeRow.Favorites -> favorites
        }
}

@Immutable
sealed interface RowState {
    @Immutable
    data object Loading : RowState

    @Immutable
    data object Empty : RowState

    @Immutable
    data object Error : RowState

    @Immutable
    data class Content(
        val items: List<MediaCardUi>,
    ) : RowState
}

enum class HomeRow {
    ContinueWatching,
    NextUp,
    RecentlyAdded,
    Favorites,
}

sealed interface HomeIntent {
    data object Refresh : HomeIntent

    data object RefreshSilently : HomeIntent

    data class Retry(
        val row: HomeRow,
    ) : HomeIntent
}
