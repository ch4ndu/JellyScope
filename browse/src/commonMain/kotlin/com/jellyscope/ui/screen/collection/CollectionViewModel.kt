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
        if (current.error && current.items.isNotEmpty()) {
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
        val startIndex =
            if (reset) {
                0
            } else {
                _state.value.items.size
            }
        reduce(CollectionChange.LoadStarted(reset))

        val result =
            try {
                getCollectionItemsUseCase(
                    collectionId = collectionId,
                    startIndex = startIndex,
                    limit = DEFAULT_DISCOVERY_PAGE_SIZE,
                )
            } catch (exception: CancellationException) {
                throw exception
            }

        // Map DTOs -> cards once, outside update{}: the CAS lambda can re-run
        // under contention, so it must stay cheap and side-effect free (A2).
        result.fold(
            onSuccess = { page ->
                val pageItems = page.items.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
                reduce(CollectionChange.PageLoaded(reset, pageItems, page.totalCount, page.startIndex))
            },
            onFailure = {
                reduce(CollectionChange.LoadFailed)
            },
        )
    }
}
