// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.DEFAULT_DISCOVERY_PAGE_SIZE
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.PersonFilmography
import com.jellyscope.core.domain.model.PersonHeader
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetPersonItemsPageUseCase
import com.jellyscope.core.domain.usecase.GetPersonUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvPersonPresenter(
    private val session: Session,
    private val personId: String,
    private val getPerson: GetPersonUseCase,
    private val getPersonItemsPage: GetPersonItemsPageUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvPersonState())
    val state: StateFlow<TvPersonState> = _state.asStateFlow()
    private var loaded = false
    private var headerJob: Job? = null
    private var pageJob: Job? = null
    private var headerGeneration = 0L
    private var pageGeneration = 0L
    private var failedAppendOffset: Int? = null

    private fun reduce(change: TvPersonChange) {
        _state.update { current -> reduceTvPerson(current, change, pageGeneration) }
    }

    fun accept(intent: TvPersonIntent) {
        when (intent) {
            TvPersonIntent.Load -> load()
            TvPersonIntent.RetryHeader -> retryHeader()
            TvPersonIntent.Retry -> retry()
            is TvPersonIntent.LoadMoreIfNeeded -> loadMoreIfNeeded(intent.focusedIndex)
        }
    }

    fun watchState(onChange: (TvPersonState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun load() {
        if (loaded) return
        loaded = true
        loadHeader()
        loadFirstPage()
    }

    private fun retryHeader() {
        loadHeader()
    }

    private fun retry() {
        val failedOffset = failedAppendOffset
        if (failedOffset != null && (state.value.movies.isNotEmpty() || state.value.shows.isNotEmpty())) {
            reduce(TvPersonChange.AppendStarted)
            fetchPage(failedOffset, append = true)
        } else {
            loadFirstPage()
        }
    }

    private fun loadMoreIfNeeded(focusedIndex: Int) {
        val current = state.value
        val visibleCount = current.movies.size + current.shows.size
        if (
            pageJob?.isActive == true ||
            failedAppendOffset != null ||
            current.isLoading ||
            current.isLoadingMore ||
            current.endReached ||
            visibleCount == 0 ||
            focusedIndex < visibleCount - LOAD_MORE_THRESHOLD
        ) {
            return
        }
        reduce(TvPersonChange.AppendStarted)
        fetchPage(current.consumedOffset, append = true)
    }

    private fun loadHeader() {
        headerJob?.cancel()
        val generation = ++headerGeneration
        reduce(TvPersonChange.HeaderStarted)
        headerJob =
            scope.launch {
                val result =
                    withContext(workDispatcher) {
                        try {
                            getPerson(personId).map { header -> header.toProjection() }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                if (generation != headerGeneration) return@launch
                result
                    .onSuccess { header ->
                        reduce(TvPersonChange.HeaderLoaded(header))
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        reduce(TvPersonChange.HeaderFailed(error.toTvErrorKind()))
                    }
            }
    }

    private fun loadFirstPage() {
        failedAppendOffset = null
        pageJob?.cancel()
        pageGeneration += 1L
        reduce(TvPersonChange.Started)
        fetchPage(startIndex = 0, append = false)
    }

    private fun fetchPage(
        startIndex: Int,
        append: Boolean,
    ) {
        pageJob?.cancel()
        val generation = ++pageGeneration
        val existingMovies = if (append) state.value.movies else emptyList()
        val existingShows = if (append) state.value.shows else emptyList()
        pageJob =
            scope.launch {
                val result =
                    withContext(workDispatcher) {
                        try {
                            getPersonItemsPage(
                                personId = personId,
                                startIndex = startIndex,
                                limit = DEFAULT_DISCOVERY_PAGE_SIZE,
                            ).map { page -> page.toProjection(existingMovies, existingShows, append) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                if (generation != pageGeneration) return@launch
                result
                    .onSuccess { page ->
                        val continuePaging = append && page.addedCount == 0 && page.rawCount == DEFAULT_DISCOVERY_PAGE_SIZE
                        failedAppendOffset = null
                        reduce(TvPersonChange.Loaded(generation, page, continuePaging, startIndex))
                        if (continuePaging && generation == pageGeneration) {
                            pageJob = null
                            fetchPage(startIndex + page.rawCount, append = true)
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        failedAppendOffset = startIndex.takeIf { append }
                        reduce(TvPersonChange.Failed(generation, error.toTvErrorKind()))
                    }
            }
    }

    private fun PersonHeader.toProjection(): TvPersonHeader =
        TvPersonHeader(
            id = id,
            name = name,
            overview = overview?.takeIf(String::isNotBlank),
            imageUrl =
                imageRefs.primaryTag?.let { tag ->
                    imageUrlBuilder.personPrimary(
                        serverUrl = session.serverUrl,
                        personId = id,
                        tag = tag,
                        maxWidth = 500,
                    )
                },
        )

    private fun PersonFilmography.toProjection(
        existingMovies: List<TvMediaCard>,
        existingShows: List<TvMediaCard>,
        append: Boolean,
    ): TvPersonPageProjection {
        val movieIds = if (append) existingMovies.mapTo(mutableSetOf()) { card -> card.id } else mutableSetOf()
        val showIds = if (append) existingShows.mapTo(mutableSetOf()) { card -> card.id } else mutableSetOf()
        val newMovies = movies.map { item -> item.toTvMediaCard(session, imageUrlBuilder) }.filter { card -> movieIds.add(card.id) }
        val newShows = series.map { item -> item.toTvMediaCard(session, imageUrlBuilder) }.filter { card -> showIds.add(card.id) }
        return TvPersonPageProjection(
            movies = if (append) existingMovies + newMovies else newMovies,
            shows = if (append) existingShows + newShows else newShows,
            rawCount = movies.size + series.size,
            addedCount = newMovies.size + newShows.size,
            totalCount = totalCount,
        )
    }
}

internal data class TvPersonPageProjection(
    val movies: List<TvMediaCard>,
    val shows: List<TvMediaCard>,
    val rawCount: Int,
    val addedCount: Int,
    val totalCount: Int,
)

private const val LOAD_MORE_THRESHOLD = 12
