// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.usecase.GetContinueWatchingUseCase
import com.jellyscope.core.domain.usecase.GetFavoritesUseCase
import com.jellyscope.core.domain.usecase.GetNextUpUseCase
import com.jellyscope.core.domain.usecase.GetRecentlyAddedUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvHomePresenter(
    private val session: Session,
    private val getContinueWatching: GetContinueWatchingUseCase,
    private val getNextUp: GetNextUpUseCase,
    private val getRecentlyAdded: GetRecentlyAddedUseCase,
    private val getFavorites: GetFavoritesUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val _state = MutableStateFlow(TvHomeState())
    val state: StateFlow<TvHomeState> = _state.asStateFlow()

    private val rowJobs = mutableMapOf<TvHomeRowKind, Job>()
    private val rowGenerations = TvHomeRowKind.entries.associateWith { 0 }.toMutableMap()

    fun watchState(onChange: (TvHomeState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun load() {
        TvHomeRowKind.entries.forEach { row ->
            val current = state.value.row(row)
            startLoad(
                row = row,
                keepContentOnError = current.status == TvHomeRowStatus.Content,
            )
        }
    }

    fun retry(row: TvHomeRowKind) {
        startLoad(row = row, keepContentOnError = false)
    }

    override fun close() {
        rowJobs.values.forEach { job -> job.cancel() }
        rowJobs.clear()
        super.close()
    }

    private fun startLoad(
        row: TvHomeRowKind,
        keepContentOnError: Boolean,
    ) {
        rowJobs.remove(row)?.cancel()
        val generation = (rowGenerations[row] ?: 0) + 1
        rowGenerations[row] = generation

        if (!keepContentOnError) {
            updateRow(row, generation) { current ->
                current.copy(
                    status = TvHomeRowStatus.Loading,
                    items = emptyList(),
                    error = null,
                )
            }
        }

        rowJobs[row] =
            scope.launch {
                val result =
                    withContext(dispatchers.work) {
                        loadItems(row).mapCatching { items ->
                            items
                                .take(HOME_ROW_CAP)
                                .map { item -> item.toTvMediaCard(session, imageUrlBuilder) }
                        }
                    }
                result.fold(
                    onSuccess = { cards ->
                        updateRow(row, generation) { current ->
                            current.copy(
                                status =
                                    if (cards.isEmpty()) {
                                        TvHomeRowStatus.Empty
                                    } else {
                                        TvHomeRowStatus.Content
                                    },
                                items = cards,
                                error = null,
                            )
                        }
                    },
                    onFailure = { failure ->
                        if (!keepContentOnError) {
                            updateRow(row, generation) { current ->
                                current.copy(
                                    status = TvHomeRowStatus.Error,
                                    items = emptyList(),
                                    error = failure.toTvErrorKind(),
                                )
                            }
                        }
                    },
                )
            }
    }

    private suspend fun loadItems(row: TvHomeRowKind): Result<List<MediaItem>> =
        when (row) {
            TvHomeRowKind.ContinueWatching -> getContinueWatching()
            TvHomeRowKind.Favorites -> getFavorites()
            TvHomeRowKind.NextUp -> getNextUp(includeResumable = false)
            TvHomeRowKind.RecentlyAdded -> getRecentlyAdded()
        }

    private fun updateRow(
        row: TvHomeRowKind,
        generation: Int,
        transform: (TvMediaRow) -> TvMediaRow,
    ) {
        _state.update { current ->
            if (rowGenerations[row] != generation) {
                return@update current
            }
            val nextRows =
                current.rows.map { candidate ->
                    if (candidate.kind == row) transform(candidate) else candidate
                }
            if (current.rows == nextRows) current else current.copy(rows = nextRows)
        }
    }
}

private const val HOME_ROW_CAP = 20
