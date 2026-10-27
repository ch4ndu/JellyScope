// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SetLibrarySortAction
import com.jellyscope.core.domain.model.DEFAULT_LIBRARY_PAGE_SIZE
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.PagedItems
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.activeCount
import com.jellyscope.core.domain.model.isAvailableFor
import com.jellyscope.core.domain.usecase.GetLibraryFiltersUseCase
import com.jellyscope.core.domain.usecase.GetLibraryItemsUseCase
import com.jellyscope.core.domain.usecase.GetLibrarySortUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvLibraryBrowsePresenter(
    private val session: Session,
    private val libraryId: String,
    private val collectionType: LibraryCollectionType,
    private val getLibraryItems: GetLibraryItemsUseCase,
    private val getLibraryFilters: GetLibraryFiltersUseCase,
    private val getLibrarySort: GetLibrarySortUseCase,
    private val setLibrarySort: SetLibrarySortAction,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val sortKey = "${session.serverId}:$libraryId"
    private val sortOptions = collectionType.tvSortOptions()
    private val pendingSortWrites = Channel<Pair<LibrarySortBy, LibrarySortOrder>>(Channel.CONFLATED)
    private var filterDefinitions = LibraryFacets().toTvFilterProjection(collectionType, LibraryFilterSelection()).definitions
    private var appliedFilters = LibraryFilterSelection()
    private var draftFilters = appliedFilters
    private var loadedOnce = false
    private var hasResolvedInitialSort = false
    private var initialQueryStarted = false
    private var sortIntentGeneration = 0L
    private var queryGeneration = 0L
    private var pageRequestGeneration = 0L
    private var failedAppendOffset: Int? = null
    private var pageJob: Job? = null
    private var refreshJob: Job? = null
    private var facetJob: Job? = null
    private var facetGeneration = 0L
    private var filterProjectionJob: Job? = null
    private var filterProjectionGeneration = 0L

    private val _state =
        MutableStateFlow(
            TvLibraryBrowseState(
                collectionType = collectionType,
                sortOptions = sortOptions,
                filterGroups = filterDefinitions.toTvFilterGroups(appliedFilters),
            ),
        )
    val state: StateFlow<TvLibraryBrowseState> = _state.asStateFlow()

    init {
        scope.launch(workDispatcher) {
            for ((sortBy, sortOrder) in pendingSortWrites) {
                try {
                    setLibrarySort(sortKey, sortBy, sortOrder)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // A failed write must not prevent a later sort from being saved.
                }
            }
        }
    }

    private fun reduce(change: TvLibraryBrowseChange) {
        _state.update { current ->
            reduceTvLibraryBrowse(
                current,
                change,
                queryGeneration,
                pageRequestGeneration,
                filterProjectionGeneration,
                appliedFilters.activeCount,
            )
        }
    }

    fun accept(intent: TvLibraryBrowseIntent) {
        when (intent) {
            TvLibraryBrowseIntent.Load -> load()
            TvLibraryBrowseIntent.Retry -> retry()
            is TvLibraryBrowseIntent.LoadMoreIfNeeded -> loadMoreIfNeeded(intent.focusedIndex)
            TvLibraryBrowseIntent.RefreshSilently -> refreshSilently()
            is TvLibraryBrowseIntent.SetSort -> setSort(intent.sortBy, intent.sortOrder)
            TvLibraryBrowseIntent.BeginEditingFilters -> beginEditingFilters()
            is TvLibraryBrowseIntent.ToggleDraftFilter -> toggleDraftFilter(intent.group, intent.key)
            TvLibraryBrowseIntent.ResetDraftFilters -> resetDraftFilters()
            TvLibraryBrowseIntent.ApplyFilters -> applyFilters()
            TvLibraryBrowseIntent.CancelFilterEditing -> cancelFilterEditing()
            TvLibraryBrowseIntent.RetryFilters -> retryFilters()
        }
    }

    fun watchState(onChange: (TvLibraryBrowseState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun load() {
        if (loadedOnce) return
        loadedOnce = true
        loadFilters()
        if (hasResolvedInitialSort) return
        scope.launch {
            val restoreGeneration = sortIntentGeneration
            val savedSort =
                withContext(workDispatcher) {
                    try {
                        getLibrarySort(sortKey)?.takeIf { saved -> saved.sortBy.isAvailableFor(collectionType) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                }
            if (restoreGeneration != sortIntentGeneration) return@launch
            hasResolvedInitialSort = true
            reduce(
                TvLibraryBrowseChange.SortRestored(
                    savedSort?.sortBy ?: LibrarySortBy.Name,
                    savedSort?.sortOrder ?: LibrarySortOrder.Ascending,
                ),
            )
            loadFirstPage()
        }
    }

    private fun retry() {
        val appendOffset = failedAppendOffset
        if (_state.value.items.isNotEmpty() && appendOffset != null) {
            reduce(TvLibraryBrowseChange.AppendStarted)
            fetchPage(startIndex = appendOffset, append = true)
        } else {
            loadFirstPage()
        }
    }

    private fun loadMoreIfNeeded(focusedIndex: Int) {
        val current = _state.value
        if (
            pageJob?.isActive == true ||
            failedAppendOffset != null ||
            current.endReached ||
            current.isLoading ||
            current.isLoadingMore ||
            current.items.isEmpty() ||
            focusedIndex < current.items.size - LOAD_MORE_THRESHOLD
        ) {
            return
        }
        reduce(TvLibraryBrowseChange.AppendStarted)
        fetchPage(startIndex = current.consumedOffset, append = true)
    }

    private fun refreshSilently() {
        val snapshot = _state.value
        val windowSize = snapshot.consumedOffset
        if (
            windowSize !in 1..MAX_SILENT_REFRESH_WINDOW ||
            snapshot.isLoading ||
            snapshot.isLoadingMore ||
            failedAppendOffset != null ||
            pageJob?.isActive == true ||
            refreshJob?.isActive == true
        ) {
            return
        }
        val generation = queryGeneration
        val refreshPageGeneration = pageRequestGeneration
        val request = snapshot.request(startIndex = 0, limit = windowSize)
        refreshJob =
            scope.launch {
                val result = loadPageProjection(request, existingItems = emptyList(), append = false)
                if (
                    generation != queryGeneration ||
                    refreshPageGeneration != pageRequestGeneration
                ) {
                    return@launch
                }
                result.onSuccess { page ->
                    reduce(TvLibraryBrowseChange.Refreshed(generation, refreshPageGeneration, page, windowSize))
                }
            }
    }

    private fun setSort(
        sortBy: LibrarySortBy,
        sortOrder: LibrarySortOrder,
    ) {
        if (sortOptions.none { option -> option.sortBy == sortBy }) return
        sortIntentGeneration += 1
        val wasProvisional = !hasResolvedInitialSort
        hasResolvedInitialSort = true
        val current = _state.value
        if (current.sortBy == sortBy && current.sortOrder == sortOrder) {
            if (wasProvisional) {
                pendingSortWrites.trySend(sortBy to sortOrder)
            }
            if (!initialQueryStarted) {
                loadFirstPage()
            }
            return
        }
        pendingSortWrites.trySend(sortBy to sortOrder)
        resetQuery {
            copy(sortBy = sortBy, sortOrder = sortOrder)
        }
    }

    private fun beginEditingFilters() {
        draftFilters = appliedFilters
        publishFilterDraft(isEditing = true)
    }

    private fun toggleDraftFilter(
        group: TvLibraryFilterGroupKind,
        key: String,
    ) {
        if (!_state.value.isEditingFilters) return
        val definition = filterDefinitions.firstOrNull { it.group == group && it.key == key } ?: return
        draftFilters = draftFilters.toggled(definition.value)
        publishFilterDraft(isEditing = true)
    }

    private fun resetDraftFilters() {
        if (!_state.value.isEditingFilters) return
        draftFilters = LibraryFilterSelection()
        publishFilterDraft(isEditing = true)
    }

    private fun applyFilters() {
        if (!_state.value.isEditingFilters) return
        val next = draftFilters
        if (next == appliedFilters) {
            publishFilterDraft(isEditing = false)
            return
        }
        appliedFilters = next
        resetQuery {
            copy(
                isEditingFilters = false,
                appliedFilterCount = appliedFilters.activeCount,
                draftFilterCount = appliedFilters.activeCount,
            )
        }
        publishFilterSelection(isEditing = false)
    }

    private fun cancelFilterEditing() {
        draftFilters = appliedFilters
        publishFilterDraft(isEditing = false)
    }

    private fun retryFilters() {
        loadFilters()
    }

    private fun loadFirstPage() {
        initialQueryStarted = true
        failedAppendOffset = null
        invalidateRequests()
        reduce(TvLibraryBrowseChange.Started)
        fetchPage(startIndex = 0, append = false)
    }

    private fun fetchPage(
        startIndex: Int,
        append: Boolean,
    ) {
        pageJob?.cancel()
        refreshJob?.cancel()
        refreshJob = null
        val requestGeneration = ++pageRequestGeneration
        val generation = queryGeneration
        val snapshot = _state.value
        val existingItems = if (append) snapshot.items else emptyList()
        val request = snapshot.request(startIndex = startIndex, limit = DEFAULT_LIBRARY_PAGE_SIZE)
        pageJob =
            scope.launch {
                val result = loadPageProjection(request, existingItems, append)
                if (generation != queryGeneration || requestGeneration != pageRequestGeneration) return@launch
                result
                    .onSuccess { page ->
                        val continuePaging =
                            append &&
                                page.addedCount == 0 &&
                                page.rawCount == DEFAULT_LIBRARY_PAGE_SIZE
                        failedAppendOffset = null
                        reduce(TvLibraryBrowseChange.Loaded(generation, requestGeneration, page, continuePaging, startIndex))
                        if (
                            continuePaging &&
                            generation == queryGeneration &&
                            requestGeneration == pageRequestGeneration
                        ) {
                            pageJob = null
                            fetchPage(
                                startIndex = startIndex + page.rawCount,
                                append = true,
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        failedAppendOffset = startIndex.takeIf { append }
                        reduce(TvLibraryBrowseChange.Failed(generation, requestGeneration, error.toTvErrorKind()))
                    }
            }
    }

    private suspend fun loadPageProjection(
        request: LibraryItemsRequest,
        existingItems: List<TvMediaCard>,
        append: Boolean,
    ): Result<TvLibraryPageProjection> =
        withContext(workDispatcher) {
            try {
                getLibraryItems(request).map { page -> page.toProjection(existingItems, append) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    private fun loadFilters() {
        facetJob?.cancel()
        val generation = ++facetGeneration
        val selection = currentFilterSelection()
        reduce(TvLibraryBrowseChange.FacetsLoading)
        facetJob =
            scope.launch {
                val result =
                    withContext(workDispatcher) {
                        try {
                            getLibraryFilters(libraryId).map { facets ->
                                facets.toTvFilterProjection(collectionType, selection)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                if (generation != facetGeneration) return@launch
                result
                    .onSuccess { projection ->
                        invalidateFilterProjection()
                        filterDefinitions = projection.definitions
                        val latestSelection = currentFilterSelection()
                        if (latestSelection == selection) {
                            reduce(TvLibraryBrowseChange.FacetsLoaded(projection.groups))
                        } else {
                            reduce(TvLibraryBrowseChange.FacetsReady)
                            publishFilterSelection(isEditing = _state.value.isEditingFilters)
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (generation != facetGeneration) return@onFailure
                        reduce(TvLibraryBrowseChange.FacetsFailed)
                    }
            }
    }

    private fun publishFilterDraft(isEditing: Boolean) {
        publishFilterSelection(isEditing)
    }

    private fun publishFilterSelection(isEditing: Boolean) {
        val selection = if (isEditing) draftFilters else appliedFilters
        val definitions = filterDefinitions
        filterProjectionJob?.cancel()
        val generation = ++filterProjectionGeneration
        reduce(TvLibraryBrowseChange.EditingChanged(isEditing, selection.activeCount))
        filterProjectionJob =
            scope.launch {
                val groups = withContext(workDispatcher) { definitions.toTvFilterGroups(selection) }
                if (generation != filterProjectionGeneration) return@launch
                reduce(TvLibraryBrowseChange.FilterGroupsLoaded(generation, groups))
            }
    }

    private fun resetQuery(transform: TvLibraryBrowseState.() -> TvLibraryBrowseState) {
        initialQueryStarted = true
        invalidateRequests()
        failedAppendOffset = null
        reduce(TvLibraryBrowseChange.QueryReset(transform))
        fetchPage(startIndex = 0, append = false)
    }

    private fun invalidateRequests() {
        queryGeneration += 1
        pageRequestGeneration += 1
        pageJob?.cancel()
        pageJob = null
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun invalidateFilterProjection() {
        filterProjectionGeneration += 1
        filterProjectionJob?.cancel()
        filterProjectionJob = null
    }

    private fun currentFilterSelection(): LibraryFilterSelection = if (_state.value.isEditingFilters) draftFilters else appliedFilters

    private fun TvLibraryBrowseState.request(
        startIndex: Int,
        limit: Int,
    ) = LibraryItemsRequest(
        parentId = libraryId,
        sortBy = sortBy,
        sortOrder = sortOrder,
        filters = appliedFilters,
        startIndex = startIndex,
        limit = limit,
    )

    private fun PagedItems.toProjection(
        existingItems: List<TvMediaCard>,
        append: Boolean,
    ): TvLibraryPageProjection {
        val seen = if (append) existingItems.mapTo(mutableSetOf()) { card -> card.id } else mutableSetOf()
        val cards =
            items
                .map { item -> item.toTvMediaCard(session, imageUrlBuilder) }
                .filter { card -> seen.add(card.id) }
        val projectedItems =
            if (!append) {
                cards
            } else if (cards.isEmpty()) {
                existingItems
            } else {
                buildList(existingItems.size + cards.size) {
                    addAll(existingItems)
                    addAll(cards)
                }
            }
        return TvLibraryPageProjection(
            items = projectedItems,
            addedCount = cards.size,
            rawCount = items.size,
            totalCount = totalCount,
        )
    }
}

internal data class TvLibraryPageProjection(
    val items: List<TvMediaCard>,
    val addedCount: Int,
    val rawCount: Int,
    val totalCount: Int,
)

private fun LibraryCollectionType.tvSortOptions(): List<TvLibrarySortOption> =
    listOf(
        LibrarySortBy.Name,
        LibrarySortBy.DateCreated,
        LibrarySortBy.PremiereDate,
        LibrarySortBy.CommunityRating,
        LibrarySortBy.Runtime,
        LibrarySortBy.VideoBitRate,
        LibrarySortBy.DateLastContentAdded,
    ).filter { sortBy -> sortBy.isAvailableFor(this) }
        .map { sortBy -> TvLibrarySortOption(sortBy) }

private const val LOAD_MORE_THRESHOLD = 12
private const val MAX_SILENT_REFRESH_WINDOW = 200
