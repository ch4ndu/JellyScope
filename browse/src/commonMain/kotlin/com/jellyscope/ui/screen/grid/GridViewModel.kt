// SPDX-License-Identifier: MPL-2.0

@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.jellyscope.ui.screen.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.coroutines.platformIoDispatcher
import com.jellyscope.core.domain.action.SetGridSortAction
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetGridSortUseCase
import com.jellyscope.core.domain.usecase.GetRibbonItemsUseCase
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.toMediaCardUi
import com.jellyscope.ui.screen.home.HomeRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicLong

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

class GridViewModel(
    private val session: Session,
    private val row: HomeRow,
    private val getRibbonItemsUseCase: GetRibbonItemsUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val getGridSortUseCase: GetGridSortUseCase,
    private val setGridSortAction: SetGridSortAction,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = platformIoDispatcher(),
) : ViewModel() {
    private val _state = MutableStateFlow<GridUiState>(GridUiState.Loading)
    val state: StateFlow<GridUiState> = _state.asStateFlow()

    // Persisted per server + row so the chosen sort survives app relaunch.
    private val sortKey = "${session.serverId}:${row.name}"

    // Two counters, deliberately. `generation` is bumped by BOTH setSort() and
    // load() and guards the state commit, because load() replaces the items a
    // sort derives from. `sortIntent` is bumped ONLY by setSort() and guards the
    // persisted value, because that records the user's choice and a reload does
    // not invalidate it — guarding the write on `generation` let any reload
    // racing a sort silently drop it.
    private val generation = AtomicLong(0L)
    private val sortIntent = AtomicLong(0L)

    // Serializes the persistence write. Without it the intent check and the write
    // are a check-then-act across dispatchers: an older coroutine can pass the
    // check, lose the thread to a newer sort that persists, then resume and
    // overwrite it with the older value.
    private val sortPersistenceMutex = Mutex()

    private var serverItems: List<MediaItem> = emptyList()
    private var currentSort =
        getGridSortUseCase(sortKey)
            ?.let { name -> GridSort.entries.firstOrNull { sort -> sort.name == name } }
            ?: GridSort.Default

    init {
        load()
    }

    fun retry() {
        load()
    }

    fun setSort(sort: GridSort) {
        if (state.value !is GridUiState.Content) {
            return
        }
        val sortGeneration = generation.addAndFetch(1L)
        val intent = sortIntent.addAndFetch(1L)
        currentSort = sort
        viewModelScope.launch(ioDispatcher) {
            sortPersistenceMutex.withLock {
                // Re-checked INSIDE the mutex so the newest intent always writes
                // last; checking outside would let a descheduled older write win.
                if (sortIntent.load() == intent) {
                    setGridSortAction(sortKey, sort.name)
                }
            }
        }
        viewModelScope.launch {
            // Sorting + mapping up to 500 items is CPU work; keep it off the
            // main thread.
            val items =
                withContext(defaultDispatcher) {
                    serverItems.sortedForGrid(sort).map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
                }
            _state.update { current ->
                if (generation.load() != sortGeneration) {
                    current
                } else {
                    // Only transform the CURRENT content, never a stale snapshot.
                    (current as? GridUiState.Content)?.copy(sort = sort, items = items) ?: current
                }
            }
        }
    }

    fun buildShuffleQueue(): List<String> = serverItems.shuffled().map { item -> item.id }

    private fun load() {
        generation.addAndFetch(1L)
        val ribbon = row.toMediaRibbon()
        if (ribbon == null) {
            _state.update { GridUiState.Error(retryable = false) }
            return
        }

        _state.update { GridUiState.Loading }
        viewModelScope.launch {
            getRibbonItemsUseCase(ribbon = ribbon).fold(
                onSuccess = { items ->
                    serverItems = items
                    val cards =
                        withContext(defaultDispatcher) {
                            items.sortedForGrid(currentSort).map { item ->
                                item.toMediaCardUi(session, imageUrlBuilder)
                            }
                        }
                    _state.update {
                        GridUiState.Content(
                            items = cards,
                            sort = currentSort,
                            row = row,
                        )
                    }
                },
                onFailure = {
                    _state.update { GridUiState.Error() }
                },
            )
        }
    }
}

internal fun HomeRow.toMediaRibbon(): MediaRibbon? =
    when (this) {
        HomeRow.ContinueWatching -> MediaRibbon.ContinueWatching
        HomeRow.Favorites -> MediaRibbon.Favorites
        HomeRow.NextUp -> MediaRibbon.NextUp
        HomeRow.RecentlyAdded -> MediaRibbon.RecentlyAdded
    }

internal fun List<MediaItem>.sortedForGrid(sort: GridSort): List<MediaItem> =
    when (sort) {
        GridSort.Default -> this
        GridSort.Title ->
            sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { item -> item.name },
            )
        GridSort.DateAdded -> sortedWith(nullsLastDescending { item -> item.dateCreated })
        GridSort.ReleaseDate -> sortedWith(nullsLastDescending { item -> item.releaseYear() })
        GridSort.Runtime -> sortedWith(nullsLastDescending { item -> item.runtime })
    }

private fun MediaItem.releaseYear(): Int? =
    premiereDate
        ?.toString()
        ?.take(4)
        ?.toIntOrNull()
        ?: productionYear

private fun <T : Comparable<T>> nullsLastDescending(selector: (MediaItem) -> T?): Comparator<MediaItem> =
    Comparator { left, right ->
        val leftValue = selector(left)
        val rightValue = selector(right)
        when {
            leftValue == null && rightValue == null -> 0
            leftValue == null -> 1
            rightValue == null -> -1
            else -> rightValue.compareTo(leftValue)
        }
    }
