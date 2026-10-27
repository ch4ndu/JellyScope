// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

internal sealed interface HomeChange {
    data object Reset : HomeChange

    data class RowUpdated(
        val row: HomeRow,
        val state: RowState,
        val generation: Int,
    ) : HomeChange
}

internal fun reduceHome(
    current: HomeUiState,
    change: HomeChange,
    generations: Map<HomeRow, Int>,
): HomeUiState =
    when (change) {
        HomeChange.Reset -> HomeUiState()
        is HomeChange.RowUpdated ->
            if (generations[change.row] != change.generation || current.rowState(change.row) == change.state) {
                current
            } else {
                when (change.row) {
                    HomeRow.ContinueWatching -> current.copy(continueWatching = change.state)
                    HomeRow.NextUp -> current.copy(nextUp = change.state)
                    HomeRow.RecentlyAdded -> current.copy(recentlyAdded = change.state, featured = change.state.toFeaturedState())
                    HomeRow.Favorites -> current.copy(favorites = change.state)
                }
            }
    }

private fun RowState.toFeaturedState(): RowState =
    when (this) {
        is RowState.Content -> RowState.Content(items.take(HOME_FEATURED_ITEM_LIMIT))
        RowState.Empty -> RowState.Empty
        RowState.Error -> RowState.Error
        RowState.Loading -> RowState.Loading
    }

private const val HOME_FEATURED_ITEM_LIMIT = 5
