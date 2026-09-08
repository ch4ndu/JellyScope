// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetUserLibrariesUseCase
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

class TvLibrariesPresenter(
    private val session: Session,
    private val getUserLibraries: GetUserLibrariesUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvLibrariesState())
    val state: StateFlow<TvLibrariesState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    fun watchState(onChange: (TvLibrariesState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        loadJob?.cancel()
        val generation = ++loadGeneration
        _state.update { current -> current.copy(isLoading = true, error = null) }
        loadJob =
            scope.launch {
                val result =
                    withContext(workDispatcher) {
                        try {
                            getUserLibraries().map { libraries ->
                                libraries.map { library ->
                                    TvLibraryTile(
                                        id = library.id,
                                        name = library.name,
                                        collectionType = library.collectionType,
                                        imageUrl =
                                            imageUrlBuilder.build(
                                                serverUrl = session.serverUrl,
                                                itemId = library.id,
                                                type = JellyfinImageType.Primary,
                                                tag = null,
                                                maxWidth = CARD_IMAGE_MAX_WIDTH,
                                            ),
                                    )
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                if (generation != loadGeneration) return@launch
                result
                    .onSuccess { tiles ->
                        _state.update { current ->
                            current.copy(
                                isLoading = false,
                                libraries = tiles,
                                error = null,
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        _state.update { current ->
                            current.copy(
                                isLoading = false,
                                error = error.toTvErrorKind(),
                            )
                        }
                    }
            }
    }
}
