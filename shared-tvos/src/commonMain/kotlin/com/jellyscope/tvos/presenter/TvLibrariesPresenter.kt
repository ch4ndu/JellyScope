// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetUserLibrariesUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvLibraryTile(
    val id: String,
    val name: String,
    val imageUrl: String?,
)

data class TvLibrariesState(
    val isLoading: Boolean = true,
    val libraries: List<TvLibraryTile> = emptyList(),
    val error: TvErrorKind? = null,
)

class TvLibrariesPresenter(
    private val session: Session,
    private val getUserLibraries: GetUserLibrariesUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvLibrariesState())
    val state: StateFlow<TvLibrariesState> = _state.asStateFlow()

    fun watchState(onChange: (TvLibrariesState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        _state.update { current -> current.copy(isLoading = true, error = null) }
        scope.launch {
            getUserLibraries()
                .onSuccess { libraries ->
                    val tiles =
                        withContext(dispatchers.work) {
                            libraries.map { library ->
                                TvLibraryTile(
                                    id = library.id,
                                    name = library.name,
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
                    _state.update {
                        TvLibrariesState(
                            isLoading = false,
                            libraries = tiles,
                        )
                    }
                }.onFailure { error ->
                    _state.update {
                        TvLibrariesState(
                            isLoading = false,
                            error = error.toTvErrorKind(),
                        )
                    }
                }
        }
    }
}
