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
        paginator.first()
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
        val currentContent = _state.value as? PersonUiState.Content
        val startIndex =
            if (reset) {
                0
            } else {
                currentContent?.itemCount ?: 0
            }
        reduce(PersonChange.LoadStarted(reset))

        val header =
            try {
                if (reset) {
                    getPersonUseCase
                        ?.invoke(personId)
                        ?.getOrElse {
                            reduce(PersonChange.LoadFailed(reset = true))
                            return
                        }?.toUi()
                } else {
                    currentContent?.header
                }
            } catch (exception: CancellationException) {
                throw exception
            }

        val result =
            try {
                getPersonItemsPageUseCase
                    ?.invoke(
                        personId = personId,
                        startIndex = startIndex,
                        limit = DEFAULT_DISCOVERY_PAGE_SIZE,
                    )
                    ?: getPersonItemsUseCase(personId)
            } catch (exception: CancellationException) {
                throw exception
            }

        // Map DTOs -> cards once, outside update{}: the CAS lambda can re-run
        // under contention, so it must stay cheap and side-effect free (A2).
        result.fold(
            onSuccess = { filmography ->
                val pageMovies = filmography.movies.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
                val pageSeries = filmography.series.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
                val movies =
                    if (reset) {
                        pageMovies
                    } else {
                        currentContent?.movies.orEmpty() + pageMovies
                    }
                val series =
                    if (reset) {
                        pageSeries
                    } else {
                        currentContent?.series.orEmpty() + pageSeries
                    }

                reduce(
                    PersonChange.Loaded(
                        PersonUiState.Content(
                            movies = movies,
                            series = series,
                            header = header ?: currentContent?.header,
                            totalCount = filmography.totalCount,
                            startIndex = filmography.startIndex,
                            hasMore = movies.size + series.size < filmography.totalCount,
                            isLoadingMore = false,
                            error = false,
                        ),
                    ),
                )
            },
            onFailure = {
                reduce(PersonChange.LoadFailed(reset))
            },
        )
    }

    private val PersonUiState.Content.itemCount: Int
        get() = movies.size + series.size

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
