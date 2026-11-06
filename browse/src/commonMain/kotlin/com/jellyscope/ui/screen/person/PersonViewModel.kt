// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jellyscope.core.domain.model.DEFAULT_DISCOVERY_PAGE_SIZE
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.PersonHeader
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetPersonItemsPageUseCase
import com.jellyscope.core.domain.usecase.GetPersonItemsUseCase
import com.jellyscope.core.domain.usecase.GetPersonUseCase
import com.jellyscope.ui.component.toMediaCardUi
import com.jellyscope.ui.paging.Paginator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PersonViewModel(
    private val session: Session,
    private val personId: String,
    private val getPersonItemsUseCase: GetPersonItemsUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val getPersonUseCase: GetPersonUseCase? = null,
    private val getPersonItemsPageUseCase: GetPersonItemsPageUseCase? = null,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow<PersonUiState>(PersonUiState.Loading)
    val state: StateFlow<PersonUiState> = _state.asStateFlow()
    private val paginator = Paginator(viewModelScope, workDispatcher, ::loadPageLocked)

    private fun reduce(change: PersonChange) {
        _state.update { current -> reducePerson(current, change) }
    }

    fun accept(intent: PersonIntent) {
        when (intent) {
            PersonIntent.Retry -> retry()
            PersonIntent.LoadMore -> loadMore()
            PersonIntent.LoadMoreAutomatically -> loadMoreAutomatically()
        }
    }

    init {
        paginator.first()
    }

    private fun retry() {
        if (_state.value is PersonUiState.Content) loadMore() else paginator.first()
    }

    private fun loadMore() {
        val content = _state.value as? PersonUiState.Content ?: return
        if (content.isLoadingMore || !content.hasMore) {
            return
        }
        if (content.error) {
            reduce(PersonChange.RetryPage)
        }
        paginator.more()
    }

    private fun loadMoreAutomatically() {
        val content = _state.value as? PersonUiState.Content ?: return
        if (content.error) return
        loadMore()
    }

    private suspend fun loadPageLocked(reset: Boolean) {
        var currentContent = if (reset) null else _state.value as? PersonUiState.Content
        var startIndex = currentContent?.consumedOffset ?: 0
        var emptyPages = 0
        reduce(PersonChange.LoadStarted(reset))
        val header =
            if (reset) {
                getPersonUseCase
                    ?.invoke(personId)
                    ?.getOrElse {
                        if (it is CancellationException) throw it
                        reduce(PersonChange.LoadFailed(reset = true))
                        return
                    }?.toUi()
            } else {
                currentContent?.header
            }
        while (true) {
            val result =
                getPersonItemsPageUseCase?.invoke(personId, startIndex, DEFAULT_DISCOVERY_PAGE_SIZE)
                    ?: getPersonItemsUseCase(personId)
            kotlin.coroutines.coroutineContext.ensureActive()
            val page =
                result.getOrElse {
                    if (it is CancellationException) throw it
                    reduce(PersonChange.LoadFailed(reset = currentContent == null))
                    return
                }
            val nextOffset = page.startIndex + page.receivedCount
            if (page.startIndex != startIndex ||
                page.receivedCount < 0 ||
                nextOffset < startIndex ||
                (page.receivedCount > 0 && nextOffset == startIndex)
            ) {
                reduce(PersonChange.LoadFailed(reset = currentContent == null))
                return
            }
            val ids = (currentContent?.movies.orEmpty() + currentContent?.series.orEmpty()).mapTo(mutableSetOf()) { it.id }
            val newMovies = page.movies.filter { ids.add(it.id) }.map { it.toMediaCardUi(session, imageUrlBuilder) }
            val newSeries = page.series.filter { ids.add(it.id) }.map { it.toMediaCardUi(session, imageUrlBuilder) }
            val hasMore = page.receivedCount > 0 && nextOffset < page.totalCount
            val continueLoading = hasMore && newMovies.isEmpty() && newSeries.isEmpty()
            val content =
                PersonUiState.Content(
                    movies = currentContent?.movies.orEmpty() + newMovies,
                    series = currentContent?.series.orEmpty() + newSeries,
                    header = header,
                    totalCount = page.totalCount,
                    startIndex = page.startIndex,
                    consumedOffset = nextOffset,
                    hasMore = hasMore,
                    isLoadingMore = continueLoading,
                )
            reduce(PersonChange.Loaded(content))
            currentContent = content
            if (!continueLoading) return
            if (++emptyPages >= 3) {
                reduce(PersonChange.LoadFailed(reset = false))
                return
            }
            startIndex = nextOffset
        }
    }

    private fun PersonHeader.toUi(): PersonHeaderUi =
        PersonHeaderUi(
            id = id,
            name = name,
            overview = overview,
            imageUrl =
                imageRefs.primaryTag?.let { tag ->
                    imageUrlBuilder.build(
                        serverUrl = session.serverUrl,
                        itemId = id,
                        type = JellyfinImageType.Primary,
                        tag = tag,
                        maxWidth = PERSON_IMAGE_MAX_WIDTH,
                    )
                },
            backdropUrl =
                imageRefs.backdropTag?.let { tag ->
                    imageUrlBuilder.build(
                        serverUrl = session.serverUrl,
                        itemId = id,
                        type = JellyfinImageType.Backdrop,
                        tag = tag,
                        maxWidth = PERSON_BACKDROP_MAX_WIDTH,
                    )
                },
        )
}

private const val PERSON_IMAGE_MAX_WIDTH = 300
private const val PERSON_BACKDROP_MAX_WIDTH = 1280
