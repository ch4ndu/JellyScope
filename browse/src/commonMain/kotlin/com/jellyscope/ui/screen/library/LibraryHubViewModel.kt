// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.jellyscope.ui.component.toMediaCardUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryHubViewModel(
    private val session: Session,
    private val parentId: String,
    private val collectionType: LibraryCollectionType,
    private val getSavedLibraryViewUseCase: GetSavedLibraryViewUseCase,
    observeRememberLastLibraryViewUseCase: ObserveRememberLastLibraryViewUseCase,
    private val setSavedLibraryViewAction: SetSavedLibraryViewAction,
    private val getLibraryRecommendationSectionUseCase: GetLibraryRecommendationSectionUseCase,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val libraryKey = "${session.serverId}:${session.userId}:$parentId"
    private val rememberLastView = observeRememberLastLibraryViewUseCase()
    private val availableViews = collectionType.availableInnerViews()
    private val defaultView = availableViews.firstOrNull() ?: LibraryInnerView.Library
    private val recommendationSections = collectionType.recommendationSections()
    private val sectionJobs = mutableMapOf<LibraryRecommendationSection, Job>()
    private var viewIntentGeneration = 0L
    private val pendingSavedViews = Channel<LibraryInnerView>(capacity = Channel.CONFLATED)

    private val _state =
        MutableStateFlow(
            LibraryHubUiState(
                availableViews = availableViews,
                selectedView = defaultView,
                recommendationSections =
                    recommendationSections.associateWith { LibraryRecommendationRowState.Loading },
            ),
        )
    val state: StateFlow<LibraryHubUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(workDispatcher) {
            for (view in pendingSavedViews) {
                try {
                    setSavedLibraryViewAction(libraryKey, view)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Throwable) {
                    // A failed preference write must not stop later selections from being saved.
                }
            }
        }
        viewModelScope.launch {
            val restoreGeneration = viewIntentGeneration
            val savedView =
                withContext(workDispatcher) {
                    getSavedLibraryViewUseCase(libraryKey)
                }
            if (restoreGeneration != viewIntentGeneration) return@launch
            val initialView =
                savedView?.takeIf { view -> rememberLastView.value && view in availableViews } ?: defaultView
            reduce(LibraryHubChange.ViewSelected(initialView))
            loadSelectedView(initialView)
        }
    }

    fun accept(intent: LibraryHubIntent) {
        when (intent) {
            is LibraryHubIntent.SelectView -> selectView(intent.view)
            is LibraryHubIntent.RetryRecommendation -> loadRecommendation(intent.section, keepContentOnError = false)
            LibraryHubIntent.RefreshRecommended -> refreshRecommendedSilently()
        }
    }

    private fun reduce(change: LibraryHubChange) {
        _state.update { current -> reduceLibraryHub(current, change) }
    }

    private fun selectView(view: LibraryInnerView) {
        if (view !in availableViews) return
        viewIntentGeneration += 1
        if (rememberLastView.value) {
            pendingSavedViews.trySend(view)
        }
        if (view == _state.value.selectedView) return
        reduce(LibraryHubChange.ViewSelected(view))
        loadSelectedView(view)
    }

    private fun refreshRecommendedSilently() {
        if (_state.value.selectedView != LibraryInnerView.Recommended) return
        recommendationSections.forEach { section -> loadRecommendation(section, keepContentOnError = true) }
    }

    private fun loadSelectedView(view: LibraryInnerView) {
        when (view) {
            LibraryInnerView.Recommended ->
                recommendationSections.forEach { section ->
                    if (_state.value.recommendationSections[section] is LibraryRecommendationRowState.Loading) {
                        loadRecommendation(section, keepContentOnError = false)
                    }
                }
            // Retained enum entries that are no longer selectable views.
            LibraryInnerView.Library,
            LibraryInnerView.Genres,
            LibraryInnerView.Collections,
            -> Unit
        }
    }

    private fun loadRecommendation(
        section: LibraryRecommendationSection,
        keepContentOnError: Boolean,
    ) {
        sectionJobs.remove(section)?.cancel()
        reduce(LibraryHubChange.RecommendationStarted(section, keepContentOnError))
        sectionJobs[section] =
            viewModelScope.launch {
                try {
                    // Return to Main before publication. Replacing this section's job
                    // prevents old mapped results from publishing at the dispatcher boundary.
                    val result =
                        withContext(workDispatcher) {
                            getLibraryRecommendationSectionUseCase(
                                LibraryRecommendationRequest(
                                    parentId = parentId,
                                    collectionType = collectionType,
                                    section = section,
                                ),
                            ).map { rows -> rows.mapNotNull { row -> row.toUi() } }
                        }
                    result.fold(
                        onSuccess = { rows -> reduce(LibraryHubChange.RecommendationLoaded(section, rows)) },
                        onFailure = { reduce(LibraryHubChange.RecommendationFailed(section, keepContentOnError)) },
                    )
                } catch (exception: CancellationException) {
                    throw exception
                }
            }
    }

    private fun LibraryRecommendationRow.toUi(): LibraryRecommendationRowUi? {
        val mapped = items.map { item -> item.toMediaCardUi(session, imageUrlBuilder) }
        return if (mapped.isEmpty()) {
            null
        } else {
            LibraryRecommendationRowUi(
                key = key,
                section = section,
                reason = reason,
                baselineItemName = baselineItemName,
                items = mapped,
            )
        }
    }
}

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
