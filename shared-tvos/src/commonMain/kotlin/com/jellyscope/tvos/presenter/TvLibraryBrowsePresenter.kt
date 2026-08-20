// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetLibraryItemsUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvLibraryBrowseState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val items: List<TvMediaCard> = emptyList(),
    val totalCount: Int? = null,
    val endReached: Boolean = false,
    val error: TvErrorKind? = null,
)

/**
 * Paged grid browse for one library, using the server's default sort. Swift
 * calls [loadMoreIfNeeded] as focus approaches the end of loaded items.
 */
class TvLibraryBrowsePresenter(
    private val session: Session,
    private val libraryId: String,
    private val getLibraryItems: GetLibraryItemsUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvLibraryBrowseState())
    val state: StateFlow<TvLibraryBrowseState> = _state.asStateFlow()

    private var pageInFlight = false

    fun watchState(onChange: (TvLibraryBrowseState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        if (pageInFlight) {
            return
        }
        _state.update { TvLibraryBrowseState(isLoading = true) }
        fetchPage(startIndex = 0)
    }

    fun loadMoreIfNeeded(focusedIndex: Int) {
        val current = state.value
        if (
            pageInFlight ||
            current.endReached ||
            current.isLoading ||
            current.items.isEmpty() ||
            focusedIndex < current.items.size - LOAD_MORE_THRESHOLD
        ) {
            return
        }
        _state.update { it.copy(isLoadingMore = true) }
        fetchPage(startIndex = current.items.size)
    }

    private fun fetchPage(startIndex: Int) {
        pageInFlight = true
        scope.launch {
            getLibraryItems(
                LibraryItemsRequest(
                    parentId = libraryId,
                    startIndex = startIndex,
                ),
            ).onSuccess { page ->
                val newCards =
                    withContext(dispatchers.work) {
                        page.items.map { item -> item.toTvMediaCard(session, imageUrlBuilder) }
                    }
                _state.update { current ->
                    val items =
                        if (startIndex == 0) {
                            newCards
                        } else {
                            current.items + newCards
                        }
                    TvLibraryBrowseState(
                        isLoading = false,
                        isLoadingMore = false,
                        items = items,
                        totalCount = page.totalCount,
                        endReached = page.items.isEmpty() || items.size >= page.totalCount,
                    )
                }
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = error.toTvErrorKind(),
                    )
                }
            }
            pageInFlight = false
        }
    }
}

private const val LOAD_MORE_THRESHOLD = 12
