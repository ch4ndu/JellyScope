// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.model.DEFAULT_DISCOVERY_PAGE_SIZE
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetCollectionItemsUseCase
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.toMediaCardUi
import com.jellyscope.ui.paging.Paginator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CollectionUiState(
    val collectionId: String,
    val items: List<MediaCardUi> = emptyList(),
    val totalCount: Int = 0,
    val startIndex: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: Boolean = false,
)

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

    init {
        paginator.first()
    }

    fun retry() {
        paginator.first()
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore) {
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
        _state.update { current ->
            current.copy(
                isLoading = reset,
                isLoadingMore = !reset,
                error = false,
            )
        }

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
                _state.update { current ->
                    val nextItems = if (reset) pageItems else current.items + pageItems
                    current.copy(
                        items = nextItems,
                        totalCount = page.totalCount,
                        startIndex = page.startIndex,
                        hasMore = nextItems.size < page.totalCount,
                        isLoading = false,
                        isLoadingMore = false,
                        error = false,
                    )
                }
            },
            onFailure = {
                _state.update { current ->
                    current.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = true,
                    )
                }
            },
        )
    }
}
