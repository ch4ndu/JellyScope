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

    private fun reduce(change: TvHomeViewAllChange) {
        _state.update { current -> reduceTvHomeViewAll(current, change) }
    }

    fun accept(intent: TvHomeViewAllIntent) {
        when (intent) {
            TvHomeViewAllIntent.Load -> load()
            TvHomeViewAllIntent.Retry -> retry()
            TvHomeViewAllIntent.Refresh -> refresh()
        }
    }

    fun watchState(onChange: (TvHomeViewAllState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    private fun load() {
        loadJob?.cancel()
        generation += 1
        val loadGeneration = generation
        reduce(TvHomeViewAllChange.Started)
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
                            reduce(TvHomeViewAllChange.Loaded(cards))
                        }
                    },
                    onFailure = { failure ->
                        if (generation == loadGeneration) {
                            reduce(TvHomeViewAllChange.Failed(failure.toTvErrorKind()))
                        }
                    },
                )
            }
    }

    private fun retry() {
        load()
    }

    private fun refresh() {
        if (state.value.status != TvHomeRowStatus.Content) {
            load()
            return
        }

        loadJob?.cancel()
        generation += 1
        val refreshGeneration = generation
        reduce(TvHomeViewAllChange.RefreshStarted)
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
                            reduce(TvHomeViewAllChange.Loaded(cards))
                        }
                    },
                    onFailure = { failure ->
                        if (generation == refreshGeneration) {
                            reduce(TvHomeViewAllChange.RefreshFailed(failure.toTvErrorKind()))
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
