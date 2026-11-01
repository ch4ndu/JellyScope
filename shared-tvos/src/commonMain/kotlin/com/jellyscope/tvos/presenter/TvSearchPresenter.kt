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
import com.jellyscope.core.domain.model.accountIdentity
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
    private val recentSearchOwner = addRecentSearch.capture(session.accountIdentity())

    init {
        scope.launch { refreshRecents(keepExistingOnFailure = false) }
    }

    private fun reduce(change: TvSearchChange) {
        _state.update { current -> reduceTvSearch(current, change) }
    }

    fun accept(intent: TvSearchIntent) {
        when (intent) {
            is TvSearchIntent.SetQuery -> setQuery(intent.query)
            is TvSearchIntent.Submit -> submit(intent.query)
            TvSearchIntent.Retry -> retry()
            is TvSearchIntent.ToggleGenre -> toggleGenre(intent.genre)
            is TvSearchIntent.SetRuntimeBucket -> setRuntimeBucket(intent.bucket)
            is TvSearchIntent.SetWatchedFilter -> setWatchedFilter(intent.filter)
            is TvSearchIntent.SelectPerson -> selectPerson(intent.person)
            TvSearchIntent.ClearPerson -> clearPerson()
            is TvSearchIntent.SelectResultCategory -> selectResultCategory(intent.category)
            TvSearchIntent.ClearRecents -> clearRecents()
            TvSearchIntent.Refresh -> refresh()
        }
    }

    fun watchState(onChange: (TvSearchState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun setQuery(query: String) {
        val normalized = query.trim()
        val current = state.value
        if (normalized == current.query && current.selectedPerson == null) return

        invalidateSuggestions()
        reduce(TvSearchChange.QueryChanged(normalized))
        scheduleSearch()
    }

    private fun submit(query: String) {
        val normalized = query.trim()
        val current = state.value
        val preservesPerson = current.selectedPerson != null && normalized == current.query
        invalidateSuggestions()
        reduce(TvSearchChange.Submitted(normalized, preservesPerson))
        if (!state.value.hasActiveQuery) {
            publishIdle()
            return
        }
        executeSearch(recentLabel = state.value.recentLabel())
    }

    private fun retry() {
        if (!state.value.hasActiveQuery) return
        executeSearch(recentLabel = null)
    }

    private fun toggleGenre(genre: TvSearchGenre) {
        reduce(TvSearchChange.GenreToggled(genre))
        scheduleSearch()
    }

    private fun setRuntimeBucket(bucket: RuntimeBucket) {
        if (state.value.runtimeBucket == bucket) return
        reduce(TvSearchChange.RuntimeChanged(bucket))
        scheduleSearch()
    }

    private fun setWatchedFilter(filter: WatchedFilter) {
        if (state.value.watchedFilter == filter) return
        reduce(TvSearchChange.WatchedChanged(filter))
        scheduleSearch()
    }

    private fun selectPerson(person: TvSearchPerson) {
        invalidateSuggestions()
        reduce(TvSearchChange.PersonSelected(person))
        executeSearch(recentLabel = person.name)
    }

    private fun clearPerson() {
        if (state.value.selectedPerson == null) return
        invalidateSuggestions()
        reduce(TvSearchChange.PersonCleared)
        if (state.value.hasActiveQuery) {
            executeSearch(recentLabel = null)
        } else {
            publishIdle()
        }
    }

    private fun selectResultCategory(category: TvSearchResultCategory) {
        reduce(TvSearchChange.CategorySelected(category))
    }

    private fun clearRecents() {
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
            reduce(TvSearchChange.RecentsCleared)
        }
    }

    private fun refresh() {
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
        reduce(TvSearchChange.SearchStarted)
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
        reduce(TvSearchChange.SearchStarted)
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
                    reduce(TvSearchChange.SearchFailed(execution.suggestions))
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
        reduce(TvSearchChange.ResultsLoaded(cards.movies, cards.shows, cards.episodes, suggestions))
    }

    private fun accepts(
        request: TvSearchRequest,
        currentGeneration: Long,
    ): Boolean = generation == currentGeneration && state.value.toRequest() == request

    private fun publishIdle() {
        searchJob?.cancel()
        generation += 1
        reduce(TvSearchChange.Idle)
    }

    private suspend fun persistRecent(label: String?) {
        val recent = label?.trim()?.takeIf { value -> value.isNotBlank() } ?: return
        try {
            recentsMutationMutex.withLock {
                withContext(workDispatcher) {
                    addRecentSearch(recentSearchOwner, recent)
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
            reduce(TvSearchChange.RecentsLoaded(recents))
        } else if (currentGeneration == recentsGeneration && !keepExistingOnFailure) {
            reduce(TvSearchChange.RecentsCleared)
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
                    reduce(TvSearchChange.SuggestionsLoaded(suggestions))
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
