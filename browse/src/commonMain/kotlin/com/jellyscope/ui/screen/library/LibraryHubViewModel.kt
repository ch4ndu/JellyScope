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
import com.jellyscope.ui.component.MediaCardUi
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

sealed interface LibraryRecommendationRowState {
    data object Loading : LibraryRecommendationRowState

    data object Error : LibraryRecommendationRowState

    data object Empty : LibraryRecommendationRowState

    data class Content(
        val rows: List<LibraryRecommendationRowUi>,
    ) : LibraryRecommendationRowState
}

data class LibraryRecommendationRowUi(
    val key: String,
    val section: LibraryRecommendationSection,
    val reason: com.jellyscope.core.domain.model.LibraryRecommendationReason?,
    val baselineItemName: String?,
    val items: List<MediaCardUi>,
)

data class LibraryHubUiState(
    val availableViews: List<LibraryInnerView>,
    val selectedView: LibraryInnerView,
    val recommendationSections: Map<LibraryRecommendationSection, LibraryRecommendationRowState>,
)

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
            _state.update { current -> current.copy(selectedView = initialView) }
            loadSelectedView(initialView)
        }
    }

    fun selectView(view: LibraryInnerView) {
        if (view !in availableViews) return
        viewIntentGeneration += 1
        if (rememberLastView.value) {
            pendingSavedViews.trySend(view)
        }
        if (view == _state.value.selectedView) return
        _state.update { current -> current.copy(selectedView = view) }
        loadSelectedView(view)
    }

    fun retryRecommendation(section: LibraryRecommendationSection) {
        loadRecommendation(section, keepContentOnError = false)
    }

    fun refreshRecommendedSilently() {
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
        val previous = _state.value.recommendationSections[section]
        if (!keepContentOnError || previous !is LibraryRecommendationRowState.Content) {
            updateRecommendation(section, LibraryRecommendationRowState.Loading)
        }
        sectionJobs[section] =
            viewModelScope.launch(workDispatcher) {
                try {
                    getLibraryRecommendationSectionUseCase(
                        LibraryRecommendationRequest(
                            parentId = parentId,
                            collectionType = collectionType,
                            section = section,
                        ),
                    ).fold(
                        onSuccess = { rows ->
                            val mapped = rows.mapNotNull { row -> row.toUi() }
                            updateRecommendation(
                                section,
                                if (mapped.isEmpty()) {
                                    LibraryRecommendationRowState.Empty
                                } else {
                                    LibraryRecommendationRowState.Content(mapped)
                                },
                            )
                        },
                        onFailure = {
                            if (!keepContentOnError || previous !is LibraryRecommendationRowState.Content) {
                                updateRecommendation(section, LibraryRecommendationRowState.Error)
                            }
                        },
                    )
                } catch (exception: CancellationException) {
                    throw exception
                }
            }
    }

    private fun updateRecommendation(
        section: LibraryRecommendationSection,
        state: LibraryRecommendationRowState,
    ) {
        _state.update { current ->
            current.copy(recommendationSections = current.recommendationSections + (section to state))
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
