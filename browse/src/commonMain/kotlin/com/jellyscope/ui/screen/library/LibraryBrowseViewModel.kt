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

    // Shuffle admission and completion are Main-confined; only the fetch runs off Main.
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
            reduce(
                LibraryBrowseChange.SortRestored(
                    savedSort?.sortBy ?: LibrarySortBy.Name,
                    savedSort?.sortOrder ?: LibrarySortOrder.Ascending,
                ),
            )
            loadFirstPage()
        }
    }

    fun accept(intent: LibraryBrowseIntent) {
        when (intent) {
            LibraryBrowseIntent.Retry -> retry()
            LibraryBrowseIntent.Refresh -> refreshSilently()
            LibraryBrowseIntent.LoadMore -> loadMore()
            is LibraryBrowseIntent.SetSort -> setSort(intent.sortBy, intent.sortOrder)
            is LibraryBrowseIntent.SetFilters -> setFilters(intent.filters)
            LibraryBrowseIntent.ShuffleAll -> shuffleAll()
        }
    }

    private fun reduce(change: LibraryBrowseChange) {
        // Paginator work runs off Main. Re-read admission on every atomic-update retry.
        _state.update { current -> reduceLibraryBrowse(current, change, pageGeneration.load()) }
    }

    private fun retry() {
        val current = _state.value
        if (current.error && current.consumedOffset > 0) {
            invalidatePageRequests()
            reduce(LibraryBrowseChange.RetryingPage)
            paginator.more()
            return
        }
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
    private fun refreshSilently() {
        val snapshot = _state.value
        if (snapshot.error || snapshot.isLoading || snapshot.isLoadingMore || silentRefreshJob?.isActive == true) return
        val windowSize = snapshot.consumedOffset.coerceAtLeast(pageSize)
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
                    reduce(
                        LibraryBrowseChange.WindowRefreshed(
                            generation,
                            pageItems,
                            page.startIndex,
                            page.receivedCount >= windowSize,
                            page.receivedCount,
                        ),
                    )
                    if (generation == pageGeneration.load() && pageItems.isEmpty() && page.receivedCount >= windowSize) {
                        // There is no grid to request more while this refreshed window has no displayable cards.
                        paginator.more()
                    }
                }
            }
    }

    private fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || current.error || !current.hasMore) {
            return
        }
        invalidatePageRequests()
        paginator.more()
    }

    private fun setSort(
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) {
        if (!sortBy.isAvailableFor(collectionType)) {
            return
        }
        sortIntentGeneration += 1
        invalidatePageRequests()
        reduce(LibraryBrowseChange.SortSelected(sortBy, sortOrder))
        pendingSortWrites.trySend(sortBy to sortOrder)
        loadFirstPage()
    }

    private fun setFilters(filters: LibraryFilterSelection) {
        cancelShuffle()
        invalidatePageRequests()
        reduce(LibraryBrowseChange.FiltersSelected(filters))
        loadFirstPage()
    }

    private fun shuffleAll() {
        if (collectionType != LibraryCollectionType.Movies || shuffleJob?.isActive == true) {
            return
        }
        val generation = ++shuffleGeneration
        val filters = _state.value.filters
        reduce(LibraryBrowseChange.ShuffleStarted)
        shuffleJob =
            viewModelScope.launch {
                try {
                    val result =
                        withContext(workDispatcher) {
                            getLibraryShuffleQueueUseCase(
                                LibraryShuffleRequest(
                                    parentId = parentId,
                                    filters = filters,
                                ),
                            )
                        }
                    result.fold(
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
                        reduce(LibraryBrowseChange.ShuffleFinished)
                    }
                }
            }
    }

    private fun cancelShuffle() {
        shuffleGeneration += 1
        shuffleJob?.cancel()
        shuffleJob = null
        reduce(LibraryBrowseChange.ShuffleFinished)
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
        var startIndex = if (reset) 0 else snapshot.consumedOffset
        var replace = reset
        var emptyPages = 0
        reduce(LibraryBrowseChange.PageStarted(reset))
        while (generation == pageGeneration.load()) {
            val result =
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
            if (generation != pageGeneration.load()) return
            val page =
                result.getOrElse {
                    reduce(LibraryBrowseChange.PageFailed(generation))
                    return
                }
            val pageItems = page.items.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
            val existingIds = if (replace) emptySet() else _state.value.items.mapTo(mutableSetOf()) { it.id }
            val addsCards = pageItems.any { it.id !in existingIds }
            val hasMore = page.receivedCount >= pageSize
            val continueLoading = hasMore && !addsCards
            reduce(
                LibraryBrowseChange.PageLoaded(
                    generation,
                    replace,
                    pageItems,
                    page.startIndex,
                    hasMore,
                    page.receivedCount,
                    continueLoading,
                ),
            )
            if (!continueLoading) return
            // Avoid stranding an empty/duplicate-only page, but bound automatic
            // traversal if a server keeps repeating or returning unmappable rows.
            if (++emptyPages >= 3) {
                reduce(LibraryBrowseChange.PageFailed(generation))
                return
            }
            startIndex = page.startIndex + page.receivedCount
            replace = false
        }
    }

    private fun loadFacets() {
        viewModelScope.launch(workDispatcher) {
            getLibraryFiltersUseCase(parentId).onSuccess { facets ->
                val filterOptions = facets.toFilterOptions()
                reduce(LibraryBrowseChange.FacetsLoaded(facets, filterOptions))
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
