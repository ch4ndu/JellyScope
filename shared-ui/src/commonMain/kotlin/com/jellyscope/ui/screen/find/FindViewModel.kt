// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.action.AddRecentSearchAction
import com.jellyscope.core.domain.action.ClearRecentSearchesAction
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.core.domain.model.parseFindYear
import com.jellyscope.core.domain.usecase.FindPersonsUseCase
import com.jellyscope.core.domain.usecase.GetRecentSearchesUseCase
import com.jellyscope.core.domain.usecase.SearchLibraryUseCase
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.toMediaCardUi
import kotlinx.coroutines.CancellationException
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
import kotlin.jvm.JvmInline

data class FindUiState(
    val queryText: String = "",
    val selectedGenreNames: List<String> = emptyList(),
    val runtimeBucket: RuntimeBucket = RuntimeBucket.Any,
    val watchedFilter: WatchedFilter = WatchedFilter.Any,
    val selectedPerson: PersonUi? = null,
    val personSuggestions: List<PersonUi> = emptyList(),
    val groupedResults: FindResultsUi = FindResultsUi(),
    val selectedResultTab: FindResultTab = FindResultTab.All,
    val isSearching: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    val error: Boolean = false,
    val activeRequestToken: FindRequestToken? = null,
    val completedRequestToken: FindRequestToken? = null,
)

@JvmInline
value class FindRequestToken internal constructor(
    val value: Long,
)

data class FindResultsUi(
    val movies: List<MediaCardUi> = emptyList(),
    val shows: List<MediaCardUi> = emptyList(),
    val episodes: List<MediaCardUi> = emptyList(),
) {
    val isEmpty: Boolean
        get() = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty()
}

data class PersonUi(
    val id: String,
    val name: String,
    val imageUrl: String?,
)

enum class FindResultTab {
    All,
    Movies,
    Shows,
    Episodes,
}

class FindViewModel(
    private val session: Session,
    private val searchLibraryUseCase: SearchLibraryUseCase,
    private val findPersonsUseCase: FindPersonsUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val getRecentSearchesUseCase: GetRecentSearchesUseCase? = null,
    private val addRecentSearchAction: AddRecentSearchAction? = null,
    private val clearRecentSearchesAction: ClearRecentSearchesAction? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(FindUiState())
    val state: StateFlow<FindUiState> = _state.asStateFlow()

    private var debounceJob: Job? = null
    private var searchJob: Job? = null
    private var recentSearchesJob: Job? = null
    private var personSuggestionsRefreshJob: Job? = null
    private var recentSearchesGeneration = 0
    private var personSuggestionsRefreshGeneration = 0
    private var requestSequence = 0L

    init {
        loadRecentSearches()
    }

    fun onQueryTextChanged(text: String) {
        cancelActiveSearch()
        personSuggestionsRefreshGeneration += 1
        _state.update { current ->
            current.copy(
                queryText = text,
                selectedPerson = null,
                isSearching = false,
                error = false,
                activeRequestToken = null,
                completedRequestToken = null,
            )
        }
        scheduleSearch()
    }

    fun clearQuery() {
        debounceJob?.cancel()
        cancelActiveSearch()
        personSuggestionsRefreshGeneration += 1
        _state.update { current ->
            current.copy(
                queryText = "",
                selectedPerson = null,
                personSuggestions = emptyList(),
                groupedResults = FindResultsUi(),
                isSearching = false,
                error = false,
                activeRequestToken = null,
                completedRequestToken = null,
            )
        }
        searchIfActive()
    }

    fun toggleGenre(genreName: String) {
        _state.update { current ->
            val nextGenres =
                if (genreName in current.selectedGenreNames) {
                    current.selectedGenreNames - genreName
                } else {
                    current.selectedGenreNames + genreName
                }
            current.copy(selectedGenreNames = nextGenres, error = false)
        }
        searchIfActive()
    }

    fun selectRuntimeBucket(bucket: RuntimeBucket) {
        _state.update { current ->
            current.copy(runtimeBucket = bucket, error = false)
        }
        searchIfActive()
    }

    fun selectWatchedFilter(filter: WatchedFilter) {
        _state.update { current ->
            current.copy(watchedFilter = filter, error = false)
        }
        searchIfActive()
    }

    fun selectPerson(person: PersonUi) {
        debounceJob?.cancel()
        personSuggestionsRefreshGeneration += 1
        _state.update { current ->
            current.copy(
                selectedPerson = person,
                personSuggestions = emptyList(),
                error = false,
            )
        }
        executeSearch(addRecent = person.name)
    }

    fun selectRecentSearch(search: String) {
        selectRecentSearchState(search)
        executeSearch(addRecent = search)
    }

    fun submitRecentSearch(search: String): FindRequestToken {
        selectRecentSearchState(search)
        return executeSearch(addRecent = search)
    }

    private fun selectRecentSearchState(search: String) {
        debounceJob?.cancel()
        personSuggestionsRefreshGeneration += 1
        _state.update { current ->
            current.copy(
                queryText = search,
                selectedPerson = null,
                error = false,
            )
        }
    }

    fun clearRecentSearches() {
        recentSearchesGeneration += 1
        val clearRecentSearches = clearRecentSearchesAction
        if (clearRecentSearches == null) {
            _state.update { current -> current.copy(recentSearches = emptyList()) }
            return
        }
        viewModelScope.launch {
            try {
                withContext(workDispatcher) {
                    clearRecentSearches(session.serverId, session.userId)
                }
                _state.update { current -> current.copy(recentSearches = emptyList()) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                // Keep the visible history when persistence could not be cleared.
            }
        }
    }

    fun selectResultTab(tab: FindResultTab) {
        _state.update { current -> current.copy(selectedResultTab = tab) }
    }

    fun retry() {
        debounceJob?.cancel()
        executeSearch(addRecent = state.value.recentLabel())
    }

    fun submitSearch(): FindRequestToken {
        debounceJob?.cancel()
        return executeSearch(addRecent = null)
    }

    fun commitRecentSearch() {
        val recentLabel = state.value.recentLabel() ?: return
        recentSearchesGeneration += 1
        viewModelScope.launch {
            val persistedRecentSearches = persistedRecentSearchesAfter(recentLabel)
            _state.update { current ->
                current.copy(
                    recentSearches = persistedRecentSearches ?: current.recentSearches.withRecent(recentLabel),
                )
            }
        }
    }

    // Tab re-entry refreshes ancillary search data only. It never cancels or
    // restarts the debounce/search jobs, so an active query and its results
    // remain untouched.
    fun refreshSilently() {
        loadRecentSearches(keepExistingOnFailure = true)
        refreshPersonSuggestions()
    }

    private fun scheduleSearch() {
        debounceJob?.cancel()
        debounceJob =
            viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MILLIS)
                searchIfActive()
            }
    }

    private fun searchIfActive() {
        if (state.value.hasActiveFindQuery()) {
            executeSearch(addRecent = null)
        } else {
            cancelActiveSearch()
            _state.update { current ->
                current.copy(
                    personSuggestions = emptyList(),
                    groupedResults = FindResultsUi(),
                    isSearching = false,
                    error = false,
                    activeRequestToken = null,
                    completedRequestToken = null,
                )
            }
        }
    }

    private fun executeSearch(addRecent: String?): FindRequestToken {
        val queryState = state.value
        val requestToken = FindRequestToken(++requestSequence)
        if (!queryState.hasActiveFindQuery()) {
            cancelActiveSearch()
            _state.update { current ->
                current.copy(
                    personSuggestions = emptyList(),
                    groupedResults = FindResultsUi(),
                    isSearching = false,
                    error = false,
                    activeRequestToken = null,
                    completedRequestToken = requestToken,
                )
            }
            return requestToken
        }
        val query = queryState.toFindQuery()

        searchJob?.cancel()
        _state.update { current ->
            current.copy(
                isSearching = true,
                error = false,
                activeRequestToken = requestToken,
                completedRequestToken = null,
            )
        }
        searchJob =
            viewModelScope.launch {
                val suggestions =
                    if (queryState.queryText.isNotBlank() && queryState.selectedPerson == null) {
                        findPersonsUseCase(queryState.queryText).getOrElse { emptyList() }
                    } else {
                        emptyList()
                    }
                val searchResult = searchLibraryUseCase(query)

                // A1/A2: project DTO->UI off the main thread and before update{}.
                val mappedSuggestions = withContext(workDispatcher) { suggestions.map { person -> person.toUi() } }
                searchResult.fold(
                    onSuccess = { results ->
                        val persistedRecentSearches = persistedRecentSearchesAfter(addRecent)
                        val mappedResults = withContext(workDispatcher) { results.toUi() }
                        _state.update { current ->
                            if (current.activeRequestToken != requestToken) {
                                current
                            } else {
                                current.copy(
                                    personSuggestions = mappedSuggestions,
                                    groupedResults = mappedResults,
                                    isSearching = false,
                                    recentSearches =
                                        persistedRecentSearches ?: current.recentSearches.withRecent(addRecent),
                                    error = false,
                                    activeRequestToken = null,
                                    completedRequestToken = requestToken,
                                )
                            }
                        }
                    },
                    onFailure = {
                        _state.update { current ->
                            if (current.activeRequestToken != requestToken) {
                                current
                            } else {
                                current.copy(
                                    personSuggestions = mappedSuggestions,
                                    isSearching = false,
                                    error = true,
                                    activeRequestToken = null,
                                    completedRequestToken = requestToken,
                                )
                            }
                        }
                    },
                )
            }
        return requestToken
    }

    private fun cancelActiveSearch() {
        searchJob?.cancel()
        searchJob = null
    }

    private fun loadRecentSearches(keepExistingOnFailure: Boolean = false) {
        val getRecentSearches = getRecentSearchesUseCase ?: return
        if (recentSearchesJob?.isActive == true) return
        val generation = ++recentSearchesGeneration
        recentSearchesJob =
            viewModelScope.launch {
                val searches =
                    try {
                        withContext(workDispatcher) {
                            getRecentSearches(session.serverId, session.userId)
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Throwable) {
                        null
                    }
                if (searches != null) {
                    _state.update { current ->
                        if (generation == recentSearchesGeneration) {
                            current.copy(recentSearches = searches)
                        } else {
                            current
                        }
                    }
                } else if (!keepExistingOnFailure) {
                    _state.update { current ->
                        if (generation == recentSearchesGeneration) {
                            current.copy(recentSearches = emptyList())
                        } else {
                            current
                        }
                    }
                }
            }
    }

    private fun refreshPersonSuggestions() {
        val queryText = _state.value.queryText.takeIf { text -> text.isNotBlank() } ?: return
        if (_state.value.selectedPerson != null || personSuggestionsRefreshJob?.isActive == true) return
        val generation = ++personSuggestionsRefreshGeneration
        personSuggestionsRefreshJob =
            viewModelScope.launch {
                // Silent-refresh invariant: a failed refresh keeps the cached
                // suggestions visible instead of blanking them.
                val suggestions =
                    withContext(workDispatcher) {
                        findPersonsUseCase(queryText).getOrNull()?.map { person -> person.toUi() }
                    } ?: return@launch
                _state.update { current ->
                    if (
                        generation == personSuggestionsRefreshGeneration &&
                        current.queryText == queryText &&
                        current.selectedPerson == null
                    ) {
                        current.copy(personSuggestions = suggestions)
                    } else {
                        current
                    }
                }
            }
    }

    private suspend fun persistedRecentSearchesAfter(search: String?): List<String>? {
        val label = search?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val addRecentSearch = addRecentSearchAction ?: return null
        val getRecentSearches = getRecentSearchesUseCase ?: return null
        return try {
            withContext(workDispatcher) {
                addRecentSearch(
                    serverId = session.serverId,
                    userId = session.userId,
                    query = label,
                )
                getRecentSearches(session.serverId, session.userId)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            null
        }
    }

    private fun FindUiState.hasActiveFindQuery(): Boolean =
        queryText.isNotBlank() ||
            selectedPerson != null ||
            selectedGenreNames.isNotEmpty() ||
            runtimeBucket != RuntimeBucket.Any ||
            watchedFilter != WatchedFilter.Any

    private fun FindUiState.toFindQuery(): FindQuery =
        FindQuery(
            text = if (selectedPerson == null) queryText else "",
            year = if (selectedPerson == null) parseFindYear(queryText) else null,
            genreNames = selectedGenreNames,
            personId = selectedPerson?.id,
            watchedFilter = watchedFilter,
            runtimeBucket = runtimeBucket,
        )

    private fun FindUiState.recentLabel(): String? =
        selectedPerson?.name
            ?: queryText.trim().takeIf { it.isNotBlank() }

    private fun FindResults.toUi(): FindResultsUi =
        FindResultsUi(
            movies = movies.map { item -> item.toMediaCardUi(session, imageUrlBuilder) },
            shows = shows.map { item -> item.toMediaCardUi(session, imageUrlBuilder) },
            episodes = episodes.map { item -> item.toMediaCardUi(session, imageUrlBuilder) },
        )

    private fun Person.toUi(): PersonUi =
        PersonUi(
            id = id,
            name = name,
            imageUrl =
                imageRefs.primaryTag?.let { tag ->
                    imageUrlBuilder.build(
                        serverUrl = session.serverUrl,
                        itemId = id,
                        type = JellyfinImageType.Primary,
                        tag = tag,
                        maxWidth = 120,
                    )
                },
        )
}

private fun List<String>.withRecent(search: String?): List<String> {
    val label = search?.trim()?.takeIf { it.isNotBlank() } ?: return this
    return (listOf(label) + filterNot { existing -> existing.equals(label, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)
}

private const val SEARCH_DEBOUNCE_MILLIS = 400L
private const val MAX_RECENT_SEARCHES = 8
