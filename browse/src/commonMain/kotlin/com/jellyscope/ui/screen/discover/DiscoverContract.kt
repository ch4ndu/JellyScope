// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.discover

import com.jellyscope.ui.component.MediaCardUi

data class DiscoverUiState(
    val genres: DiscoverListState<DiscoverFacetUi> = DiscoverListState.Loading,
    val studios: DiscoverListState<DiscoverFacetUi> = DiscoverListState.Loading,
    val collections: DiscoverListState<MediaCardUi> = DiscoverListState.Loading,
    val suggestions: DiscoverSuggestionsState = DiscoverSuggestionsState.Loading,
    val upcoming: DiscoverListState<MediaCardUi> = DiscoverListState.Loading,
) {
    val collectionMediaState: DiscoverMediaItemsState
        get() = collections.toMediaItemsState()

    val suggestionMediaState: DiscoverMediaItemsState
        get() = suggestions.toMediaItemsState()

    val upcomingMediaState: DiscoverMediaItemsState
        get() = upcoming.toMediaItemsState()
}

sealed interface DiscoverListState<out T> {
    data object Loading : DiscoverListState<Nothing>

    data class Error(
        val retryable: Boolean = true,
    ) : DiscoverListState<Nothing>

    data object Empty : DiscoverListState<Nothing>

    data class Content<T>(
        val items: List<T>,
    ) : DiscoverListState<T>
}

sealed interface DiscoverSuggestionsState {
    data object Loading : DiscoverSuggestionsState

    data class Error(
        val retryable: Boolean = true,
    ) : DiscoverSuggestionsState

    data object Empty : DiscoverSuggestionsState

    data class Content(
        val seedItem: MediaCardUi?,
        val items: List<MediaCardUi>,
    ) : DiscoverSuggestionsState
}

sealed interface DiscoverMediaItemsState {
    data object Loading : DiscoverMediaItemsState

    data class Error(
        val retryable: Boolean,
    ) : DiscoverMediaItemsState

    data object Empty : DiscoverMediaItemsState

    data class Content(
        val items: List<MediaCardUi>,
    ) : DiscoverMediaItemsState
}

private fun DiscoverListState<MediaCardUi>.toMediaItemsState(): DiscoverMediaItemsState =
    when (this) {
        DiscoverListState.Loading -> DiscoverMediaItemsState.Loading
        DiscoverListState.Empty -> DiscoverMediaItemsState.Empty
        is DiscoverListState.Error -> DiscoverMediaItemsState.Error(retryable)
        is DiscoverListState.Content ->
            if (items.isEmpty()) {
                DiscoverMediaItemsState.Empty
            } else {
                DiscoverMediaItemsState.Content(items)
            }
    }

private fun DiscoverSuggestionsState.toMediaItemsState(): DiscoverMediaItemsState =
    when (this) {
        DiscoverSuggestionsState.Loading -> DiscoverMediaItemsState.Loading
        DiscoverSuggestionsState.Empty -> DiscoverMediaItemsState.Empty
        is DiscoverSuggestionsState.Error -> DiscoverMediaItemsState.Error(retryable)
        is DiscoverSuggestionsState.Content ->
            if (items.isEmpty()) {
                DiscoverMediaItemsState.Empty
            } else {
                DiscoverMediaItemsState.Content(items)
            }
    }

data class DiscoverFacetUi(
    val id: String?,
    val name: String,
    val imageUrl: String?,
)

sealed interface DiscoverIntent {
    data object Retry : DiscoverIntent

    data object RefreshSilently : DiscoverIntent

    data object RetryGenres : DiscoverIntent

    data object RetryStudios : DiscoverIntent

    data object RetryCollections : DiscoverIntent

    data object RetrySuggestions : DiscoverIntent

    data object RetryUpcoming : DiscoverIntent
}
