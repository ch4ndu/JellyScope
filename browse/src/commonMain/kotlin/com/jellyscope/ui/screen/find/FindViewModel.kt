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

    fun accept(intent: FindIntent) {
        when (intent) {
            is FindIntent.QueryChanged -> onQueryTextChanged(intent.text)
            FindIntent.ClearQuery -> clearQuery()
            is FindIntent.ToggleGenre -> toggleGenre(intent.name)
            is FindIntent.SelectRuntime -> selectRuntimeBucket(intent.bucket)
            is FindIntent.SelectWatchedFilter -> selectWatchedFilter(intent.filter)
            is FindIntent.SelectPerson -> selectPerson(intent.person)
            is FindIntent.SelectResultTab -> reduce(FindChange.ResultTabSelected(intent.tab))
            FindIntent.ClearRecentSearches -> clearRecentSearches()
            FindIntent.CommitRecentSearch -> commitRecentSearch()
            FindIntent.Retry -> retry()
            FindIntent.Refresh -> refreshSilently()
            is FindIntent.Submission -> accept(intent)
        }
    }

    fun accept(intent: FindIntent.Submission): FindRequestToken =
        when (intent) {
            FindIntent.SubmitSearch -> submitSearch()
            is FindIntent.SelectRecentSearch -> {
                selectRecentSearchState(intent.search)
                executeSearch(addRecent = intent.search)
            }
        }

    private fun reduce(change: FindChange) {
        _state.update { current -> reduceFind(current, change) }
    }

    private fun onQueryTextChanged(text: String) {
        cancelActiveSearch()
        personSuggestionsRefreshGeneration += 1
        reduce(FindChange.QueryChanged(text))
        scheduleSearch()
    }

    private fun clearQuery() {
        debounceJob?.cancel()
        cancelActiveSearch()
        personSuggestionsRefreshGeneration += 1
        reduce(FindChange.QueryCleared)
        searchIfActive()
    }

    private fun toggleGenre(genreName: String) {
        reduce(FindChange.GenreToggled(genreName))
        searchIfActive()
    }

    private fun selectRuntimeBucket(bucket: RuntimeBucket) {
        reduce(FindChange.RuntimeSelected(bucket))
        searchIfActive()
    }

    private fun selectWatchedFilter(filter: WatchedFilter) {
        reduce(FindChange.WatchedFilterSelected(filter))
        searchIfActive()
    }

    private fun selectPerson(person: PersonUi) {
        debounceJob?.cancel()
        personSuggestionsRefreshGeneration += 1
        reduce(FindChange.PersonSelected(person))
        executeSearch(addRecent = person.name)
    }

    private fun selectRecentSearchState(search: String) {
        debounceJob?.cancel()
        personSuggestionsRefreshGeneration += 1
        reduce(FindChange.RecentSearchSelected(search))
    }

    private fun clearRecentSearches() {
        recentSearchesGeneration += 1
        val clearRecentSearches = clearRecentSearchesAction
        if (clearRecentSearches == null) {
            reduce(FindChange.RecentSearchesCleared)
            return
        }
        viewModelScope.launch {
            try {
                withContext(workDispatcher) {
                    clearRecentSearches(session.serverId, session.userId)
                }
                reduce(FindChange.RecentSearchesCleared)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                // Keep the visible history when persistence could not be cleared.
            }
        }
    }

    private fun retry() {
        debounceJob?.cancel()
        executeSearch(addRecent = state.value.recentLabel())
    }

    private fun submitSearch(): FindRequestToken {
        debounceJob?.cancel()
        return executeSearch(addRecent = null)
    }

    private fun commitRecentSearch() {
        val recentLabel = state.value.recentLabel() ?: return
        recentSearchesGeneration += 1
        viewModelScope.launch {
            val persistedRecentSearches = persistedRecentSearchesAfter(recentLabel)
            reduce(FindChange.RecentSearchCommitted(recentLabel, persistedRecentSearches))
        }
    }

    // Tab re-entry refreshes ancillary search data only. It never cancels or
    // restarts the debounce/search jobs, so an active query and its results
    // remain untouched.
    private fun refreshSilently() {
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
            reduce(FindChange.SearchInactive)
        }
    }

    private fun executeSearch(addRecent: String?): FindRequestToken {
        val queryState = state.value
        val requestToken = FindRequestToken(++requestSequence)
        if (!queryState.hasActiveFindQuery()) {
            cancelActiveSearch()
            reduce(FindChange.EmptySearchSubmitted(requestToken))
            return requestToken
        }
        val query = queryState.toFindQuery()

        searchJob?.cancel()
        reduce(FindChange.SearchStarted(requestToken))
        searchJob =
            viewModelScope.launch {
                val suggestions =
                    if (queryState.queryText.isNotBlank() && queryState.selectedPerson == null) {
                        findPersonsUseCase(queryState.queryText).getOrElse { emptyList() }
                    } else {
                        emptyList()
                    }
                val searchResult = searchLibraryUseCase(query)

                // Project results off Main before publishing the transition.
                val mappedSuggestions = withContext(workDispatcher) { suggestions.map { person -> person.toUi() } }
                searchResult.fold(
                    onSuccess = { results ->
                        val persistedRecentSearches = persistedRecentSearchesAfter(addRecent)
                        val mappedResults = withContext(workDispatcher) { results.toUi() }
                        reduce(
                            FindChange.SearchSucceeded(requestToken, mappedSuggestions, mappedResults, persistedRecentSearches, addRecent),
                        )
                    },
                    onFailure = {
                        reduce(FindChange.SearchFailed(requestToken, mappedSuggestions))
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
                if (generation == recentSearchesGeneration && (searches != null || !keepExistingOnFailure)) {
                    reduce(FindChange.RecentSearchesLoaded(searches ?: emptyList()))
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
                if (generation == personSuggestionsRefreshGeneration) {
                    reduce(FindChange.SuggestionsRefreshed(queryText, suggestions))
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

private const val SEARCH_DEBOUNCE_MILLIS = 400L
