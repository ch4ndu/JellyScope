// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetContinueWatchingUseCase
import com.jellyscope.core.domain.usecase.GetFavoritesUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetRecentlyAddedUseCase
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.toMediaCardUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

class HomeViewModel(
    private val session: Session,
    private val getContinueWatchingUseCase: GetContinueWatchingUseCase,
    private val getNextUpUseCase: GetNextUpUseCase,
    private val getRecentlyAddedUseCase: GetRecentlyAddedUseCase,
    private val getFavoritesUseCase: GetFavoritesUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private val rowGenerations = HomeRow.entries.associateWith { 0 }.toMutableMap()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = HomeUiState()
            HomeRow.entries
                .map { row ->
                    val generation = nextRowGeneration(row)
                    async { loadRow(row, keepContentOnError = false, generation = generation) }
                }.awaitAll()
        }
    }

    // Fired on every return to Home: the cached rows stay on screen while
    // fresh data loads, a row only changes once its new data arrives, and a
    // failed refresh never clobbers content that is already visible. Rows
    // still Loading are skipped — the initial load owns them.
    fun refreshSilently() {
        val snapshot = _state.value
        viewModelScope.launch {
            HomeRow.entries
                .filter { row -> snapshot.rowState(row) != RowState.Loading }
                .map { row ->
                    val generation = nextRowGeneration(row)
                    async { loadRow(row, keepContentOnError = true, generation = generation) }
                }.awaitAll()
        }
    }

    fun retry(row: HomeRow) {
        val generation = nextRowGeneration(row)
        updateRow(row, RowState.Loading, generation)
        viewModelScope.launch {
            loadRow(row, keepContentOnError = false, generation = generation)
        }
    }

    private suspend fun loadRow(
        row: HomeRow,
        keepContentOnError: Boolean,
        generation: Int,
    ) {
        when (row) {
            HomeRow.ContinueWatching -> loadMedia(row, keepContentOnError, generation) { getContinueWatchingUseCase() }
            HomeRow.NextUp -> loadMedia(row, keepContentOnError, generation) { getNextUpUseCase() }
            HomeRow.RecentlyAdded -> loadMedia(row, keepContentOnError, generation) { getRecentlyAddedUseCase() }
            HomeRow.Favorites -> loadMedia(row, keepContentOnError, generation) { getFavoritesUseCase() }
        }
    }

    private suspend fun loadMedia(
        row: HomeRow,
        keepContentOnError: Boolean = false,
        generation: Int,
        loader: suspend () -> Result<List<MediaItem>>,
    ) {
        loader().fold(
            onSuccess = { items ->
                // A1: project DTO->UI off the main thread (matches Grid/Library/etc.).
                val rowState =
                    withContext(workDispatcher) {
                        items.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }.toRowState()
                    }
                updateRow(row, rowState, generation)
            },
            onFailure = {
                if (!keepContentOnError) {
                    updateRow(row, RowState.Error, generation)
                }
            },
        )
    }

    private fun nextRowGeneration(row: HomeRow): Int {
        val next = (rowGenerations[row] ?: 0) + 1
        rowGenerations[row] = next
        return next
    }

    private fun updateRow(
        row: HomeRow,
        rowState: RowState,
        generation: Int,
    ) {
        _state.update { current ->
            if (rowGenerations[row] != generation) {
                return@update current
            }
            // Unchanged data keeps the same state instance so a silent
            // refresh that finds nothing new recomposes nothing.
            if (current.rowState(row) == rowState) {
                current
            } else {
                when (row) {
                    HomeRow.ContinueWatching -> current.copy(continueWatching = rowState)
                    HomeRow.NextUp -> current.copy(nextUp = rowState)
                    HomeRow.RecentlyAdded ->
                        current.copy(
                            recentlyAdded = rowState,
                            featured = rowState.toFeaturedState(),
                        )
                    HomeRow.Favorites -> current.copy(favorites = rowState)
                }
            }
        }
    }

    private fun List<MediaCardUi>.toRowState(): RowState =
        if (isEmpty()) {
            RowState.Empty
        } else {
            RowState.Content(this)
        }

    private fun RowState.toFeaturedState(): RowState =
        when (this) {
            is RowState.Content -> RowState.Content(items.take(HOME_FEATURED_ITEM_LIMIT))
            RowState.Empty -> RowState.Empty
            RowState.Error -> RowState.Error
            RowState.Loading -> RowState.Loading
        }
}

private const val HOME_FEATURED_ITEM_LIMIT = 5
