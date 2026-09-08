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

    fun watchState(onChange: (TvPersonState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        if (loaded) return
        loaded = true
        loadHeader()
        loadFirstPage()
    }

    fun retryHeader() {
        loadHeader()
    }

    fun retry() {
        val failedOffset = failedAppendOffset
        if (failedOffset != null && (state.value.movies.isNotEmpty() || state.value.shows.isNotEmpty())) {
            _state.update { current -> current.copy(isLoadingMore = true, error = null) }
            fetchPage(failedOffset, append = true)
        } else {
            loadFirstPage()
        }
    }

    fun loadMoreIfNeeded(focusedIndex: Int) {
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
        _state.update { state -> state.copy(isLoadingMore = true, error = null) }
        fetchPage(current.consumedOffset, append = true)
    }

    private fun loadHeader() {
        headerJob?.cancel()
        val generation = ++headerGeneration
        _state.update { current -> current.copy(headerLoading = true, headerError = null) }
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
                        _state.update { current -> current.copy(headerLoading = false, header = header, headerError = null) }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.update { current -> current.copy(headerLoading = false, headerError = error.toTvErrorKind()) }
                    }
            }
    }

    private fun loadFirstPage() {
        failedAppendOffset = null
        pageJob?.cancel()
        pageGeneration += 1L
        _state.update { current ->
            current.copy(
                isLoading = true,
                isLoadingMore = false,
                movies = emptyList(),
                shows = emptyList(),
                totalCount = null,
                consumedOffset = 0,
                endReached = false,
                error = null,
            )
        }
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
                        _state.update { current ->
                            if (generation == pageGeneration) {
                                current.copy(
                                    isLoading = false,
                                    isLoadingMore = continuePaging,
                                    movies = page.movies,
                                    shows = page.shows,
                                    totalCount = page.totalCount,
                                    consumedOffset = startIndex + page.rawCount,
                                    endReached = page.rawCount < DEFAULT_DISCOVERY_PAGE_SIZE,
                                    error = null,
                                )
                            } else {
                                current
                            }
                        }
                        if (continuePaging && generation == pageGeneration) {
                            pageJob = null
                            fetchPage(startIndex + page.rawCount, append = true)
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        failedAppendOffset = startIndex.takeIf { append }
                        _state.update { current ->
                            if (generation == pageGeneration) {
                                current.copy(
                                    isLoading = false,
                                    isLoadingMore = false,
                                    error = error.toTvErrorKind(),
                                )
                            } else {
                                current
                            }
                        }
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

private data class TvPersonPageProjection(
    val movies: List<TvMediaCard>,
    val shows: List<TvMediaCard>,
    val rawCount: Int,
    val addedCount: Int,
    val totalCount: Int,
)

private const val LOAD_MORE_THRESHOLD = 12
