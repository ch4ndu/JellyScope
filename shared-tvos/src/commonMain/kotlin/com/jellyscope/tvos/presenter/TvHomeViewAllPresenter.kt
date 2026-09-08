// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetRibbonItemsUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TvHomeViewAllState(
    val row: TvHomeRowKind,
    val status: TvHomeRowStatus = TvHomeRowStatus.Loading,
    val items: List<TvMediaCard> = emptyList(),
    val error: TvErrorKind? = null,
    val isRefreshing: Boolean = false,
)

class TvHomeViewAllPresenter(
    private val session: Session,
    private val row: TvHomeRowKind,
    private val getRibbonItems: GetRibbonItemsUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvHomeViewAllState(row = row))
    val state: StateFlow<TvHomeViewAllState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var generation = 0

    fun watchState(onChange: (TvHomeViewAllState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        loadJob?.cancel()
        generation += 1
        val loadGeneration = generation
        _state.update { current ->
            current.copy(
                status = TvHomeRowStatus.Loading,
                items = emptyList(),
                error = null,
                isRefreshing = false,
            )
        }
        loadJob =
            scope.launch {
                val result =
                    withContext(dispatchers.work) {
                        getRibbonItems(row.toMediaRibbon()).mapCatching { items ->
                            items.map { item -> item.toTvMediaCard(session, imageUrlBuilder) }
                        }
                    }
                result.fold(
                    onSuccess = { cards ->
                        if (generation == loadGeneration) {
                            _state.update { current ->
                                current.copy(
                                    status =
                                        if (cards.isEmpty()) {
                                            TvHomeRowStatus.Empty
                                        } else {
                                            TvHomeRowStatus.Content
                                        },
                                    items = cards,
                                    error = null,
                                    isRefreshing = false,
                                )
                            }
                        }
                    },
                    onFailure = { failure ->
                        if (generation == loadGeneration) {
                            _state.update { current ->
                                current.copy(
                                    status = TvHomeRowStatus.Error,
                                    items = emptyList(),
                                    error = failure.toTvErrorKind(),
                                    isRefreshing = false,
                                )
                            }
                        }
                    },
                )
            }
    }

    fun retry() {
        load()
    }

    fun refresh() {
        if (state.value.status != TvHomeRowStatus.Content) {
            load()
            return
        }

        loadJob?.cancel()
        generation += 1
        val refreshGeneration = generation
        _state.update { current -> current.copy(isRefreshing = true, error = null) }
        loadJob =
            scope.launch {
                val result =
                    withContext(dispatchers.work) {
                        getRibbonItems(row.toMediaRibbon()).mapCatching { items ->
                            items.map { item -> item.toTvMediaCard(session, imageUrlBuilder) }
                        }
                    }
                result.fold(
                    onSuccess = { cards ->
                        if (generation == refreshGeneration) {
                            _state.update { current ->
                                current.copy(
                                    status =
                                        if (cards.isEmpty()) {
                                            TvHomeRowStatus.Empty
                                        } else {
                                            TvHomeRowStatus.Content
                                        },
                                    items = cards,
                                    error = null,
                                    isRefreshing = false,
                                )
                            }
                        }
                    },
                    onFailure = { failure ->
                        if (generation == refreshGeneration) {
                            _state.update { current ->
                                current.copy(
                                    error = failure.toTvErrorKind(),
                                    isRefreshing = false,
                                )
                            }
                        }
                    },
                )
            }
    }

    override fun close() {
        loadJob?.cancel()
        super.close()
    }
}

internal fun TvHomeRowKind.toMediaRibbon(): MediaRibbon =
    when (this) {
        TvHomeRowKind.ContinueWatching -> MediaRibbon.ContinueWatching
        TvHomeRowKind.Favorites -> MediaRibbon.Favorites
        TvHomeRowKind.NextUp -> MediaRibbon.NextUp
        TvHomeRowKind.RecentlyAdded -> MediaRibbon.RecentlyAdded
    }
