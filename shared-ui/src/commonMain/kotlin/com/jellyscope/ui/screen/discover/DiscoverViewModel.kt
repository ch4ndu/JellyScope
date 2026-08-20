// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryFacet
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaSuggestions
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetCollectionsUseCase
import com.jellyscope.core.domain.usecase.GetGenresUseCase
import com.jellyscope.core.domain.usecase.GetStudiosUseCase
import com.jellyscope.core.domain.usecase.GetSuggestionsUseCase
import com.jellyscope.core.domain.usecase.GetUpcomingEpisodesUseCase
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.toMediaCardUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

class DiscoverViewModel(
    private val session: Session,
    private val getGenresUseCase: GetGenresUseCase,
    private val getStudiosUseCase: GetStudiosUseCase,
    private val getCollectionsUseCase: GetCollectionsUseCase,
    private val getSuggestionsUseCase: GetSuggestionsUseCase,
    private val getUpcomingEpisodesUseCase: GetUpcomingEpisodesUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val parentId: String? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    private var genresJob: Job? = null
    private var studiosJob: Job? = null
    private var collectionsJob: Job? = null
    private var suggestionsJob: Job? = null
    private var upcomingJob: Job? = null

    init {
        retry()
    }

    fun retry() {
        retryGenres()
        retryStudios()
        retryCollections()
        retrySuggestions()
        retryUpcoming()
    }

    // Cached sections stay visible while the fresh request runs. Loading
    // sections are left to their initial request, and an in-flight section is
    // never restarted by repeated tab selection.
    fun refreshSilently() {
        refreshGenresSilently()
        refreshStudiosSilently()
        refreshCollectionsSilently()
        refreshSuggestionsSilently()
        refreshUpcomingSilently()
    }

    fun retryGenres() {
        loadGenres(keepContentOnError = false)
    }

    fun retryStudios() {
        loadStudios(keepContentOnError = false)
    }

    fun retryCollections() {
        loadCollections(keepContentOnError = false)
    }

    fun retrySuggestions() {
        loadSuggestions(keepContentOnError = false)
    }

    fun retryUpcoming() {
        loadUpcoming(keepContentOnError = false)
    }

    private fun refreshGenresSilently() {
        if (_state.value.genres !is DiscoverListState.Loading) loadGenres(keepContentOnError = true)
    }

    private fun refreshStudiosSilently() {
        if (_state.value.studios !is DiscoverListState.Loading) loadStudios(keepContentOnError = true)
    }

    private fun refreshCollectionsSilently() {
        if (_state.value.collections !is DiscoverListState.Loading) loadCollections(keepContentOnError = true)
    }

    private fun refreshSuggestionsSilently() {
        if (_state.value.suggestions !is DiscoverSuggestionsState.Loading) loadSuggestions(keepContentOnError = true)
    }

    private fun refreshUpcomingSilently() {
        if (_state.value.upcoming !is DiscoverListState.Loading) loadUpcoming(keepContentOnError = true)
    }

    private fun loadGenres(keepContentOnError: Boolean) {
        if (genresJob?.isActive == true) return
        if (!keepContentOnError) {
            _state.update { current -> current.copy(genres = DiscoverListState.Loading) }
        }
        genresJob =
            viewModelScope.launch {
                val (result, mapped) =
                    withContext(workDispatcher) {
                        val result = fetchWithRetry { getGenresUseCase(parentId) }
                        result to
                            result.fold(
                                onSuccess = { facets -> facets.toFacetState() },
                                onFailure = { DiscoverListState.Error() },
                            )
                    }
                if (result.isSuccess || !keepContentOnError) {
                    _state.update { current -> current.copy(genres = mapped) }
                }
            }
    }

    private fun loadStudios(keepContentOnError: Boolean) {
        if (studiosJob?.isActive == true) return
        if (!keepContentOnError) {
            _state.update { current -> current.copy(studios = DiscoverListState.Loading) }
        }
        studiosJob =
            viewModelScope.launch {
                val (result, mapped) =
                    withContext(workDispatcher) {
                        val result = fetchWithRetry { getStudiosUseCase(parentId) }
                        result to
                            result.fold(
                                onSuccess = { facets -> facets.toFacetState() },
                                onFailure = { DiscoverListState.Error() },
                            )
                    }
                if (result.isSuccess || !keepContentOnError) {
                    _state.update { current -> current.copy(studios = mapped) }
                }
            }
    }

    private fun loadCollections(keepContentOnError: Boolean) {
        if (collectionsJob?.isActive == true) return
        if (!keepContentOnError) {
            _state.update { current -> current.copy(collections = DiscoverListState.Loading) }
        }
        collectionsJob =
            viewModelScope.launch {
                val (result, mapped) =
                    withContext(workDispatcher) {
                        val result = fetchWithRetry { getCollectionsUseCase(parentId) }
                        result to
                            result.fold(
                                onSuccess = { items -> items.toMediaCardState() },
                                onFailure = { DiscoverListState.Error() },
                            )
                    }
                if (result.isSuccess || !keepContentOnError) {
                    _state.update { current -> current.copy(collections = mapped) }
                }
            }
    }

    private fun loadSuggestions(keepContentOnError: Boolean) {
        if (suggestionsJob?.isActive == true) return
        if (!keepContentOnError) {
            _state.update { current -> current.copy(suggestions = DiscoverSuggestionsState.Loading) }
        }
        suggestionsJob =
            viewModelScope.launch {
                val (result, mapped) =
                    withContext(workDispatcher) {
                        val result = fetchWithRetry { getSuggestionsUseCase() }
                        result to
                            result.fold(
                                onSuccess = { suggestions -> suggestions.toUiState() },
                                onFailure = { DiscoverSuggestionsState.Error() },
                            )
                    }
                if (result.isSuccess || !keepContentOnError) {
                    _state.update { current -> current.copy(suggestions = mapped) }
                }
            }
    }

    private fun loadUpcoming(keepContentOnError: Boolean) {
        if (upcomingJob?.isActive == true) return
        if (!keepContentOnError) {
            _state.update { current -> current.copy(upcoming = DiscoverListState.Loading) }
        }
        upcomingJob =
            viewModelScope.launch {
                val (result, mapped) =
                    withContext(workDispatcher) {
                        val result = fetchWithRetry { getUpcomingEpisodesUseCase() }
                        result to
                            result.fold(
                                onSuccess = { items -> items.toMediaCardState() },
                                onFailure = { DiscoverListState.Error() },
                            )
                    }
                if (result.isSuccess || !keepContentOnError) {
                    _state.update { current -> current.copy(upcoming = mapped) }
                }
            }
    }

    // Discover fans out several requests at once on entry; a single transient
    // failure (a slow/blipped request) would otherwise strand the section on an
    // error the user must manually retry. Retry once after a short delay so a
    // blip self-heals; a persistent failure still surfaces the retryable error.
    private suspend fun <T> fetchWithRetry(block: suspend () -> Result<T>): Result<T> {
        val first = block()
        if (first.isSuccess) {
            return first
        }
        delay(DISCOVER_RETRY_DELAY_MS)
        return block()
    }

    private fun List<LibraryFacet>.toFacetState(): DiscoverListState<DiscoverFacetUi> {
        val items = map { facet -> facet.toUi() }
        return if (items.isEmpty()) {
            DiscoverListState.Empty
        } else {
            DiscoverListState.Content(items)
        }
    }

    private fun LibraryFacet.toUi(): DiscoverFacetUi =
        DiscoverFacetUi(
            id = id,
            name = name,
            imageUrl =
                id?.let { itemId ->
                    imageRefs.primaryTag?.let { tag ->
                        imageUrlBuilder.build(
                            serverUrl = session.serverUrl,
                            itemId = itemId,
                            type = JellyfinImageType.Primary,
                            tag = tag,
                            maxWidth = FACET_IMAGE_MAX_WIDTH,
                        )
                    }
                },
        )

    private fun List<MediaItem>.toMediaCardState(): DiscoverListState<MediaCardUi> {
        val items = map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
        return if (items.isEmpty()) {
            DiscoverListState.Empty
        } else {
            DiscoverListState.Content(items)
        }
    }

    private fun MediaSuggestions.toUiState(): DiscoverSuggestionsState {
        val seed = seedItem?.toMediaCardUi(session, imageUrlBuilder)
        val cards = items.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
        return if (seed == null && cards.isEmpty()) {
            DiscoverSuggestionsState.Empty
        } else {
            DiscoverSuggestionsState.Content(
                seedItem = seed,
                items = cards,
            )
        }
    }
}

private const val FACET_IMAGE_MAX_WIDTH = 300
private const val DISCOVER_RETRY_DELAY_MS = 1200L
