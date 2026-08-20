// SPDX-License-Identifier: MPL-2.0

@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.jellyscope.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.SetLibrarySortAction
import com.jellyscope.core.domain.model.DEFAULT_LIBRARY_PAGE_SIZE
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibraryShuffleRequest
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.isAvailableFor
import com.jellyscope.core.domain.usecase.GetLibraryFiltersUseCase
import com.jellyscope.core.domain.usecase.GetLibraryItemsUseCase
import com.jellyscope.core.domain.usecase.GetLibraryShuffleQueueUseCase
import com.jellyscope.core.domain.usecase.GetLibrarySortUseCase
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.toMediaCardUi
import com.jellyscope.ui.paging.Paginator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicLong

data class LibraryBrowseUiState(
    val parentId: String?,
    val collectionType: LibraryCollectionType = LibraryCollectionType.Other,
    val sortBy: LibrarySortBy = LibrarySortBy.Name,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.Ascending,
    val filters: LibraryFilterSelection = LibraryFilterSelection(),
    val items: List<MediaCardUi> = emptyList(),
    val totalCount: Int = 0,
    val startIndex: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val facets: LibraryFacets = LibraryFacets(),
    val filterOptions: LibraryFilterOptions = LibraryFilterOptions(),
    val error: Boolean = false,
    val isPreparingShuffle: Boolean = false,
)

sealed interface LibraryBrowseEvent {
    data class LaunchShuffle(
        val itemIds: List<String>,
    ) : LibraryBrowseEvent

    data object ShuffleEmpty : LibraryBrowseEvent

    data object ShuffleUnavailable : LibraryBrowseEvent
}

data class LibraryFilterOptions(
    val genres: List<LibraryFilterOption<String>> = emptyList(),
    val years: List<LibraryFilterOption<Int>> = emptyList(),
    val ratings: List<LibraryFilterOption<String>> = emptyList(),
    val studios: List<LibraryFilterOption<String>> = emptyList(),
)

data class LibraryFilterOption<T>(
    val key: String,
    val value: T,
    val label: String,
)

class LibraryBrowseViewModel(
    private val session: Session,
    private val parentId: String?,
    private val collectionType: LibraryCollectionType,
    private val getLibraryItemsUseCase: GetLibraryItemsUseCase,
    private val getLibraryShuffleQueueUseCase: GetLibraryShuffleQueueUseCase,
    private val getLibraryFiltersUseCase: GetLibraryFiltersUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val getLibrarySortUseCase: GetLibrarySortUseCase,
    private val setLibrarySortAction: SetLibrarySortAction,
    initialFilters: LibraryFilterSelection = LibraryFilterSelection(),
    private val pageSize: Int = DEFAULT_LIBRARY_PAGE_SIZE,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val sortKey = "${session.serverId}:${parentId ?: "__root__"}"
    private val eventsChannel = Channel<LibraryBrowseEvent>(Channel.BUFFERED)
    private val pendingSortWrites = Channel<Pair<LibrarySortBy, LibrarySortOrder>>(Channel.CONFLATED)
    val events: Flow<LibraryBrowseEvent> = eventsChannel.receiveAsFlow()
    private var shuffleJob: Job? = null
    private var shuffleGeneration = 0L
    private var silentRefreshJob: Job? = null
    private var sortIntentGeneration = 0L
    private val pageGeneration = AtomicLong(0L)

    private val _state =
        MutableStateFlow(
            LibraryBrowseUiState(
                parentId = parentId,
                collectionType = collectionType,
                filters = initialFilters,
            ),
        )
    val state: StateFlow<LibraryBrowseUiState> = _state.asStateFlow()
    private val paginator = Paginator(viewModelScope, workDispatcher, ::loadPageLocked)

    init {
        viewModelScope.launch(workDispatcher) {
            for ((sortBy, sortOrder) in pendingSortWrites) {
                try {
                    setLibrarySortAction(sortKey, sortBy, sortOrder)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Throwable) {
                    // A failed preference write must not stop later selections from being saved.
                }
            }
        }
        loadFacets()
        viewModelScope.launch {
            val restoreGeneration = sortIntentGeneration
            val savedSort =
                withContext(workDispatcher) {
                    getLibrarySortUseCase(sortKey)?.takeIf { saved -> saved.sortBy.isAvailableFor(collectionType) }
                }
            if (restoreGeneration != sortIntentGeneration) return@launch
            _state.update { current ->
                current.copy(
                    sortBy = savedSort?.sortBy ?: LibrarySortBy.Name,
                    sortOrder = savedSort?.sortOrder ?: LibrarySortOrder.Ascending,
                )
            }
            loadFirstPage()
        }
    }

    fun retry() {
        invalidatePageRequests()
        loadFirstPage()
        loadFacets()
    }

    // Re-entry refresh re-fetches the already-loaded window (all pages the
    // user has scrolled in, capped) in one request and replaces it after a
    // successful fetch, so watched/resume state freshens without collapsing a
    // deep-scrolled grid back to page one. Existing cards stay visible
    // throughout, failures leave them intact, and a later regular load
    // invalidates an older refresh result. Beyond the cap the refresh is
    // skipped: briefly stale userData beats a giant re-fetch.
    fun refreshSilently() {
        val snapshot = _state.value
        if (snapshot.isLoading || snapshot.isLoadingMore || silentRefreshJob?.isActive == true) return
        val windowSize = snapshot.items.size.coerceAtLeast(pageSize)
        if (windowSize > MAX_SILENT_REFRESH_WINDOW) return
        val generation = pageGeneration.addAndFetch(1L)
        silentRefreshJob =
            viewModelScope.launch {
                val result =
                    withContext(workDispatcher) {
                        try {
                            getLibraryItemsUseCase(
                                LibraryItemsRequest(
                                    parentId = parentId,
                                    sortBy = snapshot.sortBy,
                                    sortOrder = snapshot.sortOrder,
                                    filters = snapshot.filters,
                                    startIndex = 0,
                                    limit = windowSize,
                                ),
                            ).map { page ->
                                page to page.items.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        }
                    }
                result.onSuccess { (page, pageItems) ->
                    _state.update { current ->
                        if (pageGeneration.load() != generation) {
                            current
                        } else {
                            current.copy(
                                items = pageItems,
                                totalCount = pageItems.size,
                                startIndex = page.startIndex,
                                hasMore = page.items.size >= windowSize,
                                isLoading = false,
                                isLoadingMore = false,
                                error = false,
                            )
                        }
                    }
                }
            }
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore) {
            return
        }
        invalidatePageRequests()
        paginator.more()
    }

    fun setSort(
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) {
        if (!sortBy.isAvailableFor(collectionType)) {
            return
        }
        sortIntentGeneration += 1
        invalidatePageRequests()
        _state.update { current ->
            current.copy(
                sortBy = sortBy,
                sortOrder = sortOrder,
                items = emptyList(),
                totalCount = 0,
                startIndex = 0,
                hasMore = false,
                isLoading = true,
                isLoadingMore = false,
                error = false,
            )
        }
        pendingSortWrites.trySend(sortBy to sortOrder)
        loadFirstPage()
    }

    fun setFilters(filters: LibraryFilterSelection) {
        cancelShuffle()
        invalidatePageRequests()
        _state.update { current ->
            current.copy(
                filters = filters,
                items = emptyList(),
                totalCount = 0,
                startIndex = 0,
                hasMore = false,
                isLoading = true,
                isLoadingMore = false,
                error = false,
            )
        }
        loadFirstPage()
    }

    fun shuffleAll() {
        if (collectionType != LibraryCollectionType.Movies || shuffleJob?.isActive == true) {
            return
        }
        val generation = ++shuffleGeneration
        val filters = _state.value.filters
        _state.update { current -> current.copy(isPreparingShuffle = true) }
        shuffleJob =
            viewModelScope.launch(workDispatcher) {
                try {
                    getLibraryShuffleQueueUseCase(
                        LibraryShuffleRequest(
                            parentId = parentId,
                            filters = filters,
                        ),
                    ).fold(
                        onSuccess = { itemIds ->
                            if (generation != shuffleGeneration) return@fold
                            if (itemIds.isEmpty()) {
                                eventsChannel.send(LibraryBrowseEvent.ShuffleEmpty)
                            } else {
                                eventsChannel.send(LibraryBrowseEvent.LaunchShuffle(itemIds))
                            }
                        },
                        onFailure = {
                            if (generation == shuffleGeneration) {
                                eventsChannel.send(LibraryBrowseEvent.ShuffleUnavailable)
                            }
                        },
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } finally {
                    if (generation == shuffleGeneration) {
                        _state.update { current -> current.copy(isPreparingShuffle = false) }
                    }
                }
            }
    }

    private fun cancelShuffle() {
        shuffleGeneration += 1
        shuffleJob?.cancel()
        shuffleJob = null
        _state.update { current -> current.copy(isPreparingShuffle = false) }
    }

    private fun loadFirstPage() {
        paginator.first()
    }

    private fun invalidatePageRequests() {
        pageGeneration.addAndFetch(1L)
    }

    private suspend fun loadPageLocked(reset: Boolean) {
        val generation = pageGeneration.addAndFetch(1L)
        val snapshot = _state.value
        val startIndex =
            if (reset) {
                0
            } else {
                snapshot.items.size
            }
        _state.update { current ->
            current.copy(
                isLoading = reset,
                isLoadingMore = !reset,
                error = false,
            )
        }

        val result =
            try {
                getLibraryItemsUseCase(
                    LibraryItemsRequest(
                        parentId = parentId,
                        sortBy = snapshot.sortBy,
                        sortOrder = snapshot.sortOrder,
                        filters = snapshot.filters,
                        startIndex = startIndex,
                        limit = pageSize,
                    ),
                )
            } catch (exception: CancellationException) {
                throw exception
            }

        // Map DTOs -> cards once, outside update{}; the state reducer stays cheap.
        result.fold(
            onSuccess = { page ->
                val pageItems = page.items.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
                _state.update { current ->
                    if (pageGeneration.load() != generation) {
                        current
                    } else {
                        val nextItems = if (reset) pageItems else current.items + pageItems
                        current.copy(
                            items = nextItems,
                            // The exact server total is skipped for speed; surface the
                            // loaded count and page by whether the last page was full.
                            totalCount = nextItems.size,
                            startIndex = page.startIndex,
                            hasMore = page.items.size >= pageSize,
                            isLoading = false,
                            isLoadingMore = false,
                            error = false,
                        )
                    }
                }
            },
            onFailure = {
                _state.update { current ->
                    if (pageGeneration.load() != generation) {
                        current
                    } else {
                        current.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = true,
                        )
                    }
                }
            },
        )
    }

    private fun loadFacets() {
        viewModelScope.launch(workDispatcher) {
            getLibraryFiltersUseCase(parentId).onSuccess { facets ->
                val filterOptions = facets.toFilterOptions()
                _state.update { current ->
                    current.copy(
                        facets = facets,
                        filterOptions = filterOptions,
                    )
                }
            }
        }
    }
}

// Silent re-entry refreshes re-fetch the loaded window in one request; past
// this many items the refresh is skipped rather than hammering the server.
private const val MAX_SILENT_REFRESH_WINDOW = 200

private fun LibraryFacets.toFilterOptions(): LibraryFilterOptions =
    LibraryFilterOptions(
        genres =
            genres.map { facet ->
                val id = facet.id ?: facet.name
                LibraryFilterOption(
                    key = "genre-$id",
                    value = id,
                    label = facet.name,
                )
            },
        years =
            years.sortedDescending().map { year ->
                LibraryFilterOption(
                    key = "year-$year",
                    value = year,
                    label = year.toString(),
                )
            },
        ratings =
            officialRatings.map { rating ->
                LibraryFilterOption(
                    key = "rating-$rating",
                    value = rating,
                    label = rating,
                )
            },
        studios =
            studios.mapNotNull { studio ->
                studio.id?.let { id ->
                    LibraryFilterOption(
                        key = "studio-$id",
                        value = id,
                        label = studio.name,
                    )
                }
            },
    )
