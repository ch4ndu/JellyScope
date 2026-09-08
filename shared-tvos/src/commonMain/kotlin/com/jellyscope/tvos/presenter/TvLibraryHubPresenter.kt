// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.action.SetSavedLibraryViewAction
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibraryRecommendationRequest
import com.jellyscope.core.domain.model.LibraryRecommendationRow
import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.availableInnerViews
import com.jellyscope.core.domain.usecase.GetLibraryRecommendationSectionUseCase
import com.jellyscope.core.domain.usecase.GetSavedLibraryViewUseCase
import com.jellyscope.core.domain.usecase.ObserveRememberLastLibraryViewUseCase
import com.jellyscope.tvos.bridge.WatchHandle
import com.jellyscope.tvos.bridge.watchIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvLibraryHubPresenter(
    private val session: Session,
    private val libraryId: String,
    private val collectionType: LibraryCollectionType,
    private val getSavedLibraryView: GetSavedLibraryViewUseCase,
    observeRememberLastLibraryView: ObserveRememberLastLibraryViewUseCase,
    private val setSavedLibraryView: SetSavedLibraryViewAction,
    private val getLibraryRecommendationSection: GetLibraryRecommendationSectionUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    dispatchers: TvosDispatchers,
) : TvPresenter(dispatchers) {
    private val libraryKey = "${session.serverId}:${session.userId}:$libraryId"
    private val rememberLastView = observeRememberLastLibraryView()
    private val availableViews = collectionType.availableInnerViews()
    private val defaultView = availableViews.firstOrNull() ?: LibraryInnerView.Library
    private val recommendationSections = collectionType.recommendationSections()
    private val sectionJobs = mutableMapOf<LibraryRecommendationSection, Job>()
    private val sectionGenerations = mutableMapOf<LibraryRecommendationSection, Long>()
    private val pendingSavedViews = Channel<LibraryInnerView>(Channel.CONFLATED)
    private var viewIntentGeneration = 0L

    private val _state =
        MutableStateFlow(
            TvLibraryHubState(
                availableViews = availableViews,
                selectedView = defaultView,
                recommendationSections = recommendationSections.map { section -> section.loadingState() },
            ),
        )
    val state: StateFlow<TvLibraryHubState> = _state.asStateFlow()

    init {
        scope.launch(workDispatcher) {
            for (view in pendingSavedViews) {
                try {
                    setSavedLibraryView(libraryKey, view)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // A failed write must not prevent a later selection from being saved.
                }
            }
        }
        scope.launch {
            val restoreGeneration = viewIntentGeneration
            val savedView =
                withContext(workDispatcher) {
                    try {
                        getSavedLibraryView(libraryKey)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                }
            if (restoreGeneration != viewIntentGeneration) return@launch
            val restored =
                savedView?.takeIf { view -> rememberLastView.value && view in availableViews } ?: defaultView
            _state.update { current -> current.copy(selectedView = restored) }
            loadSelectedView(restored)
        }
    }

    fun watchState(onChange: (TvLibraryHubState) -> Unit): WatchHandle = state.watchIn(scope, onChange)

    fun selectView(view: LibraryInnerView) {
        if (view !in availableViews) return
        viewIntentGeneration += 1
        if (rememberLastView.value) {
            pendingSavedViews.trySend(view)
        }
        if (view == _state.value.selectedView) {
            loadSelectedView(view)
            return
        }
        _state.update { current -> current.copy(selectedView = view) }
        loadSelectedView(view)
    }

    fun retryRecommendation(section: LibraryRecommendationSection) {
        if (section in recommendationSections) {
            loadRecommendation(section, keepContentOnError = false)
        }
    }

    fun refreshRecommendedSilently() {
        if (_state.value.selectedView != LibraryInnerView.Recommended) return
        recommendationSections.forEach { section ->
            loadRecommendation(section, keepContentOnError = true)
        }
    }

    private fun loadSelectedView(view: LibraryInnerView) {
        if (view != LibraryInnerView.Recommended) return
        recommendationSections.forEach { section ->
            val status =
                _state.value.recommendationSections
                    .first { it.section == section }
                    .status
            if (status == TvLibraryRecommendationStatus.Loading) {
                loadRecommendation(section, keepContentOnError = false)
            }
        }
    }

    private fun loadRecommendation(
        section: LibraryRecommendationSection,
        keepContentOnError: Boolean,
    ) {
        sectionJobs.remove(section)?.cancel()
        val generation = (sectionGenerations[section] ?: 0L) + 1L
        sectionGenerations[section] = generation
        val previous = _state.value.recommendationSections.first { it.section == section }
        if (!keepContentOnError || previous.status != TvLibraryRecommendationStatus.Content) {
            updateSection(section, section.loadingState())
        }
        sectionJobs[section] =
            scope.launch {
                val result =
                    withContext(workDispatcher) {
                        try {
                            getLibraryRecommendationSection(
                                LibraryRecommendationRequest(
                                    parentId = libraryId,
                                    collectionType = collectionType,
                                    section = section,
                                ),
                            ).map { rows -> rows.mapNotNull { row -> row.toTvRow() } }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                if (sectionGenerations[section] != generation) return@launch
                result
                    .onSuccess { rows ->
                        updateSection(
                            section,
                            TvLibraryRecommendationSectionState(
                                stableId = section.name,
                                section = section,
                                status =
                                    if (rows.isEmpty()) {
                                        TvLibraryRecommendationStatus.Empty
                                    } else {
                                        TvLibraryRecommendationStatus.Content
                                    },
                                rows = rows,
                            ),
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        if (sectionGenerations[section] != generation) return@onFailure
                        if (!keepContentOnError || previous.status != TvLibraryRecommendationStatus.Content) {
                            updateSection(
                                section,
                                TvLibraryRecommendationSectionState(
                                    stableId = section.name,
                                    section = section,
                                    status = TvLibraryRecommendationStatus.Error,
                                ),
                            )
                        }
                    }
            }
    }

    private fun updateSection(
        section: LibraryRecommendationSection,
        next: TvLibraryRecommendationSectionState,
    ) {
        _state.update { current ->
            current.copy(
                recommendationSections =
                    current.recommendationSections.map { state ->
                        if (state.section == section) next else state
                    },
            )
        }
    }

    private fun LibraryRecommendationRow.toTvRow(): TvLibraryRecommendationRow? {
        val cards = items.map { item -> item.toTvMediaCard(session, imageUrlBuilder) }
        return cards.takeIf { it.isNotEmpty() }?.let {
            TvLibraryRecommendationRow(
                stableId = "${section.name}/$key",
                section = section,
                reason = reason,
                baselineItemName = baselineItemName,
                items = cards,
            )
        }
    }
}

private fun LibraryRecommendationSection.loadingState() =
    TvLibraryRecommendationSectionState(
        stableId = name,
        section = this,
    )

private fun LibraryCollectionType.recommendationSections(): List<LibraryRecommendationSection> =
    when (this) {
        LibraryCollectionType.Movies ->
            listOf(
                LibraryRecommendationSection.ContinueWatching,
                LibraryRecommendationSection.RecentlyAdded,
                LibraryRecommendationSection.MovieRecommendations,
            )
        LibraryCollectionType.TvShows ->
            listOf(
                LibraryRecommendationSection.ContinueWatching,
                LibraryRecommendationSection.RecentlyAdded,
                LibraryRecommendationSection.NextUp,
            )
        else -> emptyList()
    }
