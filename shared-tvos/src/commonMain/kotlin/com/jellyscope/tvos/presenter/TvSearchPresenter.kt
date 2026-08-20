// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.AddRecentSearchAction
import com.jellyscope.core.domain.action.ClearRecentSearchesAction
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetRecentSearchesUseCase
import com.jellyscope.core.domain.usecase.SearchLibraryUseCase
import com.jellyscope.core.util.DiagnosticOperation
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.formatSafeFailureDiagnostic
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvSearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val movies: List<TvMediaCard> = emptyList(),
    val shows: List<TvMediaCard> = emptyList(),
    val episodes: List<TvMediaCard> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val error: TvErrorKind? = null,
) {
    val hasResults: Boolean
        get() = movies.isNotEmpty() || shows.isNotEmpty() || episodes.isNotEmpty()
}

/**
 * Debounced sectioned search. Every query edit cancels the prior search job
 * and bumps a generation counter, so a slow stale response can never
 * overwrite a newer query's results. Committed non-empty searches persist to
 * the recent-search store (rendered in the idle state).
 */
class TvSearchPresenter(
    private val session: Session,
    private val searchLibrary: SearchLibraryUseCase,
    private val getRecentSearches: GetRecentSearchesUseCase,
    private val addRecentSearch: AddRecentSearchAction,
    private val clearRecentSearches: ClearRecentSearchesAction,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvSearchState())
    val state: StateFlow<TvSearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var generation = 0L

    init {
        scope.launch { refreshRecents() }
    }

    fun watchState(onChange: (TvSearchState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun setQuery(query: String) {
        searchJob?.cancel()
        generation += 1
        val current = generation
        if (query.isBlank()) {
            _state.update { state ->
                TvSearchState(query = query, recentSearches = state.recentSearches)
            }
            return
        }
        _state.update { state -> state.copy(query = query, isSearching = true, error = null) }
        searchJob =
            scope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                // Debounced edits never persist — a TV-keyboard pause is not a
                // committed search.
                runSearch(query, current)
            }
    }

    /** Committed search (Select on the keyboard / a recent-search pick): runs
     * immediately and persists the non-blank query regardless of outcome. */
    fun submit(query: String) {
        if (query.isBlank()) {
            return
        }
        searchJob?.cancel()
        generation += 1
        _state.update { state -> state.copy(query = query, isSearching = true, error = null) }
        val current = generation
        searchJob =
            scope.launch {
                // Persistence is best-effort — a failing recents store must
                // never block the search itself.
                try {
                    withContext(workDispatcher) {
                        addRecentSearch(session.serverId, session.userId, query.trim())
                    }
                    refreshRecents()
                } catch (exception: kotlinx.coroutines.CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    diagnosticLogger(DiagnosticTag.TvSearchPresenter).w {
                        formatSafeFailureDiagnostic(
                            stage = "search",
                            event = "save-recent-failed",
                            operation = DiagnosticOperation.AddRecentSearch,
                            throwable = exception,
                        )
                    }
                }
                runSearch(query, current)
            }
    }

    fun clearRecents() {
        scope.launch {
            try {
                withContext(workDispatcher) {
                    clearRecentSearches(session.serverId, session.userId)
                }
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                diagnosticLogger(DiagnosticTag.TvSearchPresenter).w {
                    formatSafeFailureDiagnostic(
                        stage = "search",
                        event = "clear-recents-failed",
                        operation = DiagnosticOperation.ClearRecentSearches,
                        throwable = exception,
                    )
                }
                return@launch
            }
            _state.update { state -> state.copy(recentSearches = emptyList()) }
        }
    }

    private suspend fun runSearch(
        query: String,
        current: Long,
    ) {
        val results =
            withContext(dispatchers.work) {
                searchLibrary(FindQuery(text = query.trim()))
            }
        if (generation != current) {
            return
        }
        results
            .onSuccess { findResults ->
                val (movies, shows, episodes) =
                    withContext(dispatchers.work) {
                        Triple(
                            findResults.movies.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                            findResults.shows.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                            findResults.episodes.map { item -> item.toTvMediaCard(session, imageUrlBuilder) },
                        )
                    }
                _state.update { state ->
                    state.copy(
                        isSearching = false,
                        movies = movies,
                        shows = shows,
                        episodes = episodes,
                    )
                }
            }.onFailure { throwable ->
                diagnosticLogger(DiagnosticTag.TvSearchPresenter).w {
                    formatSafeFailureDiagnostic(
                        stage = "search",
                        event = "failed",
                        operation = DiagnosticOperation.Search,
                        throwable = throwable,
                    )
                }
                // A failed query must never leave the previous query's results
                // on screen behind a hidden error.
                _state.update { state ->
                    state.copy(
                        isSearching = false,
                        movies = emptyList(),
                        shows = emptyList(),
                        episodes = emptyList(),
                        error = TvErrorKind.Network,
                    )
                }
            }
    }

    private suspend fun refreshRecents() {
        val recents =
            try {
                withContext(workDispatcher) { getRecentSearches(session.serverId, session.userId) }
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                diagnosticLogger(DiagnosticTag.TvSearchPresenter).w {
                    formatSafeFailureDiagnostic(
                        stage = "recent-searches",
                        event = "failed",
                        operation = DiagnosticOperation.GetRecentSearches,
                        throwable = exception,
                    )
                }
                emptyList()
            }
        _state.update { state -> state.copy(recentSearches = recents) }
    }
}

private const val SEARCH_DEBOUNCE_MS = 350L
