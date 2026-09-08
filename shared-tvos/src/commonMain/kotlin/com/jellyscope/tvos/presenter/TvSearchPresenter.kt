// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.AddRecentSearchAction
import com.jellyscope.core.domain.action.ClearRecentSearchesAction
import com.jellyscope.core.domain.model.FindMediaKind
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.core.domain.model.parseFindYear
import com.jellyscope.core.domain.usecase.FindPersonsUseCase
import com.jellyscope.core.domain.usecase.GetRecentSearchesUseCase
import com.jellyscope.core.domain.usecase.SearchLibraryUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TvSearchPresenter(
    private val session: Session,
    private val searchLibrary: SearchLibraryUseCase,
    private val findPersons: FindPersonsUseCase,
    private val getRecentSearches: GetRecentSearchesUseCase,
    private val addRecentSearch: AddRecentSearchAction,
    private val clearRecentSearches: ClearRecentSearchesAction,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvSearchState())
    val state: StateFlow<TvSearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var suggestionsRefreshJob: Job? = null
    private var generation = 0L
    private var suggestionsGeneration = 0L
    private var recentsGeneration = 0L
    private val recentsMutationMutex = Mutex()

    init {
        scope.launch { refreshRecents(keepExistingOnFailure = false) }
    }

    fun watchState(onChange: (TvSearchState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun setQuery(query: String) {
        val normalized = query.trim()
        val current = state.value
        if (normalized == current.query && current.selectedPerson == null) return

        invalidateSuggestions()
        _state.update { state ->
            state.copy(
                query = normalized,
                selectedPerson = null,
                personSuggestions = emptyList(),
                error = null,
            )
        }
        scheduleSearch()
    }

    fun submit(query: String) {
        val normalized = query.trim()
        val current = state.value
        val preservesPerson = current.selectedPerson != null && normalized == current.query
        invalidateSuggestions()
        _state.update { state ->
            state.copy(
                query = normalized,
                selectedPerson = state.selectedPerson.takeIf { preservesPerson },
                personSuggestions = emptyList(),
                error = null,
            )
        }
        if (!state.value.hasActiveQuery) {
            publishIdle()
            return
        }
        executeSearch(recentLabel = state.value.recentLabel())
    }

    fun retry() {
        if (!state.value.hasActiveQuery) return
        executeSearch(recentLabel = null)
    }

    fun toggleGenre(genre: TvSearchGenre) {
        _state.update { current ->
            val genres =
                if (genre in current.selectedGenres) {
                    current.selectedGenres - genre
                } else {
                    current.selectedGenres + genre
                }
            current.copy(selectedGenres = genres, error = null)
        }
        scheduleSearch()
    }

    fun setRuntimeBucket(bucket: RuntimeBucket) {
        if (state.value.runtimeBucket == bucket) return
        _state.update { current -> current.copy(runtimeBucket = bucket, error = null) }
        scheduleSearch()
    }

    fun setWatchedFilter(filter: WatchedFilter) {
        if (state.value.watchedFilter == filter) return
        _state.update { current -> current.copy(watchedFilter = filter, error = null) }
        scheduleSearch()
    }

    fun selectPerson(person: TvSearchPerson) {
        invalidateSuggestions()
        _state.update { current ->
            current.copy(
                query = person.name,
                selectedPerson = person,
                personSuggestions = emptyList(),
                error = null,
            )
        }
        executeSearch(recentLabel = person.name)
    }

    fun clearPerson() {
        if (state.value.selectedPerson == null) return
        invalidateSuggestions()
        _state.update { current ->
            current.copy(
                query = "",
                selectedPerson = null,
                personSuggestions = emptyList(),
                error = null,
            )
        }
        if (state.value.hasActiveQuery) {
            executeSearch(recentLabel = null)
        } else {
            publishIdle()
        }
    }

    fun selectResultCategory(category: TvSearchResultCategory) {
        _state.update { current -> current.copy(selectedResultCategory = category) }
    }

    fun clearRecents() {
        recentsGeneration += 1
        scope.launch {
            try {
                recentsMutationMutex.withLock {
                    withContext(workDispatcher) {
                        clearRecentSearches(session.serverId, session.userId)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                searchLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "recent-searches",
                        event = "clear-failed",
                        operation = DiagnosticOperation.ClearRecentSearches,
                        throwable = exception,
                    )
                }
                return@launch
            }
            recentsGeneration += 1
            _state.update { current -> current.copy(recentSearches = emptyList()) }
        }
    }

    fun refresh() {
        scope.launch { refreshRecents(keepExistingOnFailure = true) }
        refreshPersonSuggestions()
    }

    override fun close() {
        searchJob?.cancel()
        suggestionsRefreshJob?.cancel()
        super.close()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        generation += 1
        if (!state.value.hasActiveQuery) {
            publishIdle()
            return
        }
        val request = state.value.toRequest()
        val currentGeneration = generation
        _state.update { current -> current.copy(isSearching = true, error = null) }
        searchJob =
            scope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                runSearch(request, currentGeneration)
            }
    }

    private fun executeSearch(recentLabel: String?) {
        searchJob?.cancel()
        generation += 1
        val request = state.value.toRequest()
        val currentGeneration = generation
        _state.update { current -> current.copy(isSearching = true, error = null) }
        searchJob =
            scope.launch {
                persistRecent(recentLabel)
                runSearch(request, currentGeneration)
            }
    }

    private suspend fun runSearch(
        request: TvSearchRequest,
        currentGeneration: Long,
    ) {
        val execution =
            try {
                withContext(dispatchers.work) {
                    val personResult =
                        if (request.queryText.isNotBlank() && request.person == null) {
                            findPersons(request.queryText)
                        } else {
                            Result.success<List<Person>>(emptyList())
                        }
                    val suggestions =
                        personResult
                            .getOrElse { failure ->
                                if (failure is CancellationException) throw failure
                                searchLogger.w {
                                    formatSafeFailureDiagnostic(
                                        stage = "person-suggestions",
                                        event = "failed",
                                        operation = DiagnosticOperation.FindPersons,
                                        throwable = failure,
                                    )
                                }
                                emptyList()
                            }.map { person -> person.toTvSearchPerson(session, imageUrlBuilder) }
                    SearchExecution(
                        results = searchLibrary(request.toFindQuery()),
                        suggestions = suggestions,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                SearchExecution(Result.failure(exception), emptyList())
            }
        if (!accepts(request, currentGeneration)) return

        execution.results.fold(
            onSuccess = { results -> publishResults(request, currentGeneration, results, execution.suggestions) },
            onFailure = { failure ->
                if (failure is CancellationException) throw failure
                searchLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "search",
                        event = "failed",
                        operation = DiagnosticOperation.Search,
                        throwable = failure,
                    )
                }
                if (accepts(request, currentGeneration)) {
                    _state.update { current ->
                        current.copy(
                            isSearching = false,
                            personSuggestions = execution.suggestions,
                            error = TvErrorKind.Network,
                        )
                    }
                }
            },
        )
    }

    private suspend fun publishResults(
        request: TvSearchRequest,
        currentGeneration: Long,
        results: FindResults,
        suggestions: List<TvSearchPerson>,
    ) {
        val cards =
            withContext(dispatchers.work) {
                SearchCards(
                    movies = results.movies.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                    shows = results.shows.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                    episodes = results.episodes.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                )
            }
        if (!accepts(request, currentGeneration)) return
        _state.update { current ->
            current.copy(
                isSearching = false,
                movies = cards.movies,
                shows = cards.shows,
                episodes = cards.episodes,
                personSuggestions = suggestions,
                error = null,
            )
        }
    }

    private fun accepts(
        request: TvSearchRequest,
        currentGeneration: Long,
    ): Boolean = generation == currentGeneration && state.value.toRequest() == request

    private fun publishIdle() {
        searchJob?.cancel()
        generation += 1
        _state.update { current ->
            current.copy(
                isSearching = false,
                movies = emptyList(),
                shows = emptyList(),
                episodes = emptyList(),
                personSuggestions = emptyList(),
                selectedResultCategory = TvSearchResultCategory.All,
                error = null,
            )
        }
    }

    private suspend fun persistRecent(label: String?) {
        val recent = label?.trim()?.takeIf { value -> value.isNotBlank() } ?: return
        try {
            recentsMutationMutex.withLock {
                withContext(workDispatcher) {
                    addRecentSearch(session.serverId, session.userId, recent)
                }
            }
            refreshRecents(keepExistingOnFailure = true)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            searchLogger.w {
                formatSafeFailureDiagnostic(
                    stage = "recent-searches",
                    event = "save-failed",
                    operation = DiagnosticOperation.AddRecentSearch,
                    throwable = exception,
                )
            }
        }
    }

    private suspend fun refreshRecents(keepExistingOnFailure: Boolean) {
        recentsGeneration += 1
        val currentGeneration = recentsGeneration
        val recents =
            try {
                withContext(workDispatcher) { getRecentSearches(session.serverId, session.userId) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                searchLogger.w {
                    formatSafeFailureDiagnostic(
                        stage = "recent-searches",
                        event = "load-failed",
                        operation = DiagnosticOperation.GetRecentSearches,
                        throwable = exception,
                    )
                }
                null
            }
        if (currentGeneration == recentsGeneration && recents != null) {
            _state.update { current -> current.copy(recentSearches = recents) }
        } else if (currentGeneration == recentsGeneration && !keepExistingOnFailure) {
            _state.update { current -> current.copy(recentSearches = emptyList()) }
        }
    }

    private fun refreshPersonSuggestions() {
        suggestionsRefreshJob?.cancel()
        val request = state.value.toRequest()
        if (request.queryText.isBlank() || request.person != null) return
        val currentGeneration = ++suggestionsGeneration
        suggestionsRefreshJob =
            scope.launch {
                val suggestions =
                    try {
                        withContext(dispatchers.work) {
                            findPersons(request.queryText).fold(
                                onSuccess = { people ->
                                    people.map { person -> person.toTvSearchPerson(session, imageUrlBuilder) }
                                },
                                onFailure = { failure ->
                                    if (failure is CancellationException) throw failure
                                    null
                                },
                            )
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Throwable) {
                        null
                    } ?: return@launch
                if (currentGeneration == suggestionsGeneration && state.value.toRequest() == request) {
                    _state.update { current -> current.copy(personSuggestions = suggestions) }
                }
            }
    }

    private fun invalidateSuggestions() {
        suggestionsGeneration += 1
        suggestionsRefreshJob?.cancel()
    }
}

private data class TvSearchRequest(
    val queryText: String,
    val selectedGenres: List<TvSearchGenre>,
    val runtimeBucket: RuntimeBucket,
    val watchedFilter: WatchedFilter,
    val person: TvSearchPerson?,
)

private data class SearchExecution(
    val results: Result<FindResults>,
    val suggestions: List<TvSearchPerson>,
)

private data class SearchCards(
    val movies: List<TvMediaCard>,
    val shows: List<TvMediaCard>,
    val episodes: List<TvMediaCard>,
)

private fun TvSearchState.toRequest(): TvSearchRequest =
    TvSearchRequest(
        queryText = query,
        selectedGenres = selectedGenres,
        runtimeBucket = runtimeBucket,
        watchedFilter = watchedFilter,
        person = selectedPerson,
    )

private fun TvSearchRequest.toFindQuery(): FindQuery =
    FindQuery(
        text = if (person == null) queryText else "",
        year = if (person == null) parseFindYear(queryText) else null,
        genreNames = selectedGenres.map(TvSearchGenre::serverName),
        personId = person?.id,
        watchedFilter = watchedFilter,
        runtimeBucket = runtimeBucket,
        mediaKinds = FindMediaKind.entries,
    )

private fun TvSearchState.recentLabel(): String? = selectedPerson?.name ?: query.trim().takeIf { value -> value.isNotBlank() }

private const val SEARCH_DEBOUNCE_MS = 350L

private val searchLogger = diagnosticLogger(DiagnosticTag.TvSearchPresenter)
