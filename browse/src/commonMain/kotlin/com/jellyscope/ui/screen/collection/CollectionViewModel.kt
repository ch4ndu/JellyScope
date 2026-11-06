// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.model.DEFAULT_DISCOVERY_PAGE_SIZE
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetCollectionItemsUseCase
import com.jellyscope.ui.component.toMediaCardUi
import com.jellyscope.ui.paging.Paginator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CollectionViewModel(
    private val session: Session,
    private val collectionId: String,
    private val getCollectionItemsUseCase: GetCollectionItemsUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(CollectionUiState(collectionId = collectionId))
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()
    private val paginator = Paginator(viewModelScope, workDispatcher, ::loadPageLocked)

    private fun reduce(change: CollectionChange) {
        _state.update { current -> reduceCollection(current, change) }
    }

    fun accept(intent: CollectionIntent) {
        when (intent) {
            CollectionIntent.Retry -> retry()
            CollectionIntent.LoadMore -> loadMore()
        }
    }

    init {
        paginator.first()
    }

    private fun retry() {
        val current = _state.value
        if (current.error) {
            reduce(CollectionChange.RetryPage)
            paginator.more()
            return
        }
        paginator.first()
    }

    private fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || current.error || !current.hasMore) {
            return
        }
        paginator.more()
    }

    private suspend fun loadPageLocked(reset: Boolean) {
        var startIndex = if (reset) 0 else _state.value.consumedOffset
        var replace = reset
        var emptyPages = 0
        reduce(CollectionChange.LoadStarted(reset))
        while (true) {
            val result =
                getCollectionItemsUseCase(
                    collectionId = collectionId,
                    startIndex = startIndex,
                    limit = DEFAULT_DISCOVERY_PAGE_SIZE,
                )
            kotlin.coroutines.coroutineContext.ensureActive()
            val page =
                result.getOrElse {
                    if (it is CancellationException) throw it
                    reduce(CollectionChange.LoadFailed)
                    return
                }
            val nextOffset = page.startIndex + page.receivedCount
            if (page.startIndex != startIndex ||
                page.receivedCount < 0 ||
                nextOffset < startIndex ||
                (page.receivedCount > 0 && nextOffset == startIndex)
            ) {
                reduce(CollectionChange.LoadFailed)
                return
            }
            val pageItems = page.items.map { it.toMediaCardUi(session, imageUrlBuilder) }
            val existingIds = if (replace) emptySet() else _state.value.items.mapTo(mutableSetOf()) { it.id }
            val hasMore = page.receivedCount > 0 && nextOffset < page.totalCount
            val continueLoading = hasMore && pageItems.none { it.id !in existingIds }
            reduce(
                CollectionChange.PageLoaded(
                    reset = replace,
                    items = pageItems,
                    totalCount = page.totalCount,
                    startIndex = page.startIndex,
                    receivedCount = page.receivedCount,
                    hasMore = hasMore,
                    continueLoading = continueLoading,
                ),
            )
            if (!continueLoading) return
            if (++emptyPages >= 3) {
                reduce(CollectionChange.LoadFailed)
                return
            }
            startIndex = nextOffset
            replace = false
        }
    }
}
