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

    fun watchState(onChange: (TvLibraryBrowseState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
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
        val appendOffset = failedAppendOffset
        if (_state.value.items.isNotEmpty() && appendOffset != null) {
            _state.update { current -> current.copy(isLoadingMore = true, error = null) }
            fetchPage(startIndex = appendOffset, append = true)
        } else {
            loadFirstPage()
        }
    }

    fun loadMoreIfNeeded(focusedIndex: Int) {
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
        _state.update { it.copy(isLoadingMore = true, error = null) }
        fetchPage(startIndex = current.consumedOffset, append = true)
    }

    fun refreshSilently() {
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
                    _state.update { current ->
                        if (
                            generation != queryGeneration ||
                            refreshPageGeneration != pageRequestGeneration
                        ) {
                            current
                        } else {
                            current.copy(
                                items = page.items,
                                totalCount = page.totalCount,
                                consumedOffset = page.rawCount,
                                endReached = page.rawCount < windowSize,
                                error = null,
                            )
                        }
                    }
                }
            }
    }

    fun setSort(
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

    fun beginEditingFilters() {
        draftFilters = appliedFilters
        publishFilterDraft(isEditing = true)
    }

    fun toggleDraftFilter(
        group: TvLibraryFilterGroupKind,
        key: String,
    ) {
        if (!_state.value.isEditingFilters) return
        val definition = filterDefinitions.firstOrNull { it.group == group && it.key == key } ?: return
        draftFilters = draftFilters.toggled(definition.value)
        publishFilterDraft(isEditing = true)
    }

    fun resetDraftFilters() {
        if (!_state.value.isEditingFilters) return
        draftFilters = LibraryFilterSelection()
        publishFilterDraft(isEditing = true)
    }

    fun applyFilters() {
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

    fun cancelFilterEditing() {
        draftFilters = appliedFilters
        publishFilterDraft(isEditing = false)
    }

    fun retryFilters() {
        loadFilters()
    }

    private fun loadFirstPage() {
        initialQueryStarted = true
        failedAppendOffset = null
        invalidateRequests()
        _state.update { current ->
            current.copy(
                isLoading = true,
                isLoadingMore = false,
                items = emptyList(),
                totalCount = null,
                consumedOffset = 0,
                endReached = false,
                error = null,
            )
        }
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
                        _state.update { current ->
                            if (generation != queryGeneration || requestGeneration != pageRequestGeneration) {
                                current
                            } else {
                                current.copy(
                                    isLoading = false,
                                    isLoadingMore = continuePaging,
                                    items = page.items,
                                    totalCount = page.totalCount,
                                    consumedOffset = startIndex + page.rawCount,
                                    endReached = page.rawCount < DEFAULT_LIBRARY_PAGE_SIZE,
                                    error = null,
                                )
                            }
                        }
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
                        _state.update { current ->
                            if (generation != queryGeneration || requestGeneration != pageRequestGeneration) {
                                current
                            } else {
                                current.copy(
                                    isLoading = false,
                                    isLoadingMore = false,
                                    error = error.toTvErrorKind(),
                                )
                            }
                        }
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
        _state.update { current -> current.copy(facetStatus = TvLibraryFacetStatus.Loading) }
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
                            _state.update { current ->
                                current.copy(
                                    facetStatus = TvLibraryFacetStatus.Content,
                                    filterGroups = projection.groups,
                                )
                            }
                        } else {
                            _state.update { current -> current.copy(facetStatus = TvLibraryFacetStatus.Content) }
                            publishFilterSelection(isEditing = _state.value.isEditingFilters)
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (generation != facetGeneration) return@onFailure
                        _state.update { current -> current.copy(facetStatus = TvLibraryFacetStatus.Error) }
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
        _state.update { current ->
            current.copy(
                isEditingFilters = isEditing,
                appliedFilterCount = appliedFilters.activeCount,
                draftFilterCount = selection.activeCount,
            )
        }
        filterProjectionJob =
            scope.launch {
                val groups = withContext(workDispatcher) { definitions.toTvFilterGroups(selection) }
                if (generation != filterProjectionGeneration) return@launch
                _state.update { current ->
                    if (generation == filterProjectionGeneration) {
                        current.copy(filterGroups = groups)
                    } else {
                        current
                    }
                }
            }
    }

    private fun resetQuery(transform: TvLibraryBrowseState.() -> TvLibraryBrowseState) {
        initialQueryStarted = true
        invalidateRequests()
        failedAppendOffset = null
        _state.update { current ->
            current.transform().copy(
                isLoading = true,
                isLoadingMore = false,
                items = emptyList(),
                totalCount = null,
                consumedOffset = 0,
                endReached = false,
                error = null,
                queryRevision = current.queryRevision + 1,
            )
        }
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

private data class TvLibraryPageProjection(
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
