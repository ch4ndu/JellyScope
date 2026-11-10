// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.find.FindIntent
import com.jellyscope.ui.screen.find.FindRequestToken
import com.jellyscope.ui.screen.find.FindResultTab
import com.jellyscope.ui.screen.find.FindResultsUi
import com.jellyscope.ui.screen.find.FindUiState
import com.jellyscope.ui.screen.find.FindViewModel
import com.jellyscope.ui.screen.find.PersonUi
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TvFindScreen(
    session: Session,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FindViewModel =
        koinViewModel(
            parameters = { parametersOf(session) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnResumeEffect {
        viewModel.accept(FindIntent.Refresh)
    }

    TvFindContent(
        session = session,
        state = state,
        onBack = onBack,
        onQueryTextChanged = { viewModel.accept(FindIntent.QueryChanged(it)) },
        onClearQuery = { viewModel.accept(FindIntent.ClearQuery) },
        onRecentSelected = { viewModel.accept(FindIntent.SelectRecentSearch(it)) },
        onClearRecentSearches = { viewModel.accept(FindIntent.ClearRecentSearches) },
        onPersonSelected = { viewModel.accept(FindIntent.SelectPerson(it)) },
        onResultTabSelected = { viewModel.accept(FindIntent.SelectResultTab(it)) },
        onRetry = { viewModel.accept(FindIntent.Retry) },
        onSubmitSearch = { viewModel.accept(FindIntent.SubmitSearch) },
        onSearchCommitted = { viewModel.accept(FindIntent.CommitRecentSearch) },
        onItemSelected = onItemSelected,
        onItemPlayDirect = onItemPlayDirect,
        modifier = modifier,
    )
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun TvFindContent(
    session: Session,
    state: FindUiState,
    onBack: () -> Unit,
    onQueryTextChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRecentSelected: (String) -> FindRequestToken,
    onClearRecentSearches: () -> Unit,
    onPersonSelected: (PersonUi) -> Unit,
    onResultTabSelected: (FindResultTab) -> Unit,
    onRetry: () -> Unit,
    onSubmitSearch: () -> FindRequestToken,
    onSearchCommitted: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val heroEntry = "find:${state.completedRequestToken}:${state.selectedResultTab}"
    var focusedMedia by remember(session.serverId, session.userId, heroEntry) { mutableStateOf<MediaCardUi?>(null) }
    val currentMedia =
        focusedMedia?.let { focused ->
            val results = state.groupedResults
            when (state.selectedResultTab) {
                FindResultTab.All ->
                    results.movies.firstOrNull { it.id == focused.id }
                        ?: results.shows.firstOrNull { it.id == focused.id }
                        ?: results.episodes.firstOrNull { it.id == focused.id }
                FindResultTab.Movies -> results.movies.firstOrNull { it.id == focused.id }
                FindResultTab.Shows -> results.shows.firstOrNull { it.id == focused.id }
                FindResultTab.Episodes -> results.episodes.firstOrNull { it.id == focused.id }
            }
        }
    val hero = rememberTvBrowseHero(session, currentMedia, TvHeroAmbientSurface.Find, heroEntry)
    val heroVisible = hero.enabled && state.groupedResults.hasResultsFor(state.selectedResultTab)
    val searchFieldRequester = remember { FocusRequester() }
    val resultsFocusRequester = remember { FocusRequester() }
    val hostedRailController = LocalTvHostedRailController.current
    val hostedRail = hostedRailController.enabled
    val hostedRailVisible = hostedRailController.visible
    val requestInitialContentFocus = !hostedRailController.contentAutofocusSuppressed
    val hostedRailContentRegistrationKey = remember { hostedRailController.contentRegistrationKey }
    val resultScrollState = rememberScrollState()
    val verticalBringIntoViewSpec = rememberRowAwareMarioBringIntoViewSpec()
    var pendingResultFocusToken by remember { mutableStateOf<FindRequestToken?>(null) }
    var searchFieldEditing by remember { mutableStateOf(false) }
    val railHasFocus = hostedRailController.railHasFocus
    val contentStartPadding =
        if (hostedRailVisible) {
            TvDimens.drawerContentStartPadding
        } else {
            TvDimens.overscanHorizontal
        }

    fun focusSearchField(): Boolean = searchFieldRequester.requestFocusSafely()

    val focusContent =
        remember(searchFieldRequester) {
            { searchFieldRequester.requestFocusSafely() }
        }

    LaunchedEffect(hostedRailVisible, hostedRailContentRegistrationKey, searchFieldRequester, focusContent) {
        if (hostedRailVisible) {
            hostedRailController.setContentRightFocusRequester(
                hostedRailContentRegistrationKey,
                searchFieldRequester,
            )
            hostedRailController.setContentRightFocusAction(
                hostedRailContentRegistrationKey,
                focusContent,
            )
        }
    }

    fun requestRailFocus(): Boolean =
        if (hostedRail) {
            hostedRailController.requestRailFocus()
        } else {
            false
        }

    LaunchedEffect(Unit) {
        if (requestInitialContentFocus) {
            requestTvFocusWithRetry { focusSearchField() }
        }
    }

    LaunchedEffect(
        pendingResultFocusToken,
        state.activeRequestToken,
        state.completedRequestToken,
        state.groupedResults,
        state.selectedResultTab,
        state.isSearching,
        state.error,
    ) {
        val pendingToken = pendingResultFocusToken ?: return@LaunchedEffect
        when {
            state.completedRequestToken == pendingToken -> {
                if (state.error) {
                    pendingResultFocusToken = null
                } else if (state.groupedResults.hasResultsFor(state.selectedResultTab)) {
                    if (requestTvFocusWithRetry { resultsFocusRequester.requestFocusSafely() }) {
                        pendingResultFocusToken = null
                    }
                } else {
                    pendingResultFocusToken = null
                }
            }
            state.activeRequestToken != null && state.activeRequestToken != pendingToken -> {
                pendingResultFocusToken = null
            }
            state.completedRequestToken != null && state.completedRequestToken != pendingToken -> {
                pendingResultFocusToken = null
            }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .tvHomeAmbientBackground(hero.ambient.presentation, overlay = true, enabled = heroVisible)
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.Back &&
                        run {
                            if (searchFieldEditing) {
                                false
                            } else if (!railHasFocus) {
                                if (!requestRailFocus()) {
                                    onBack()
                                }
                                true
                            } else {
                                false
                            }
                        }
                },
    ) {
        if (heroVisible) {
            TvHeroBackdrop(
                session,
                hero.item,
                Modifier.align(androidx.compose.ui.Alignment.TopEnd),
                onArtworkLoaded = hero.ambient.onArtworkLoaded,
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
        ) {
            TvFindHeader(
                queryText = state.queryText,
                onQueryTextChanged = { text ->
                    pendingResultFocusToken = null
                    onQueryTextChanged(text)
                },
                onClearQuery = {
                    pendingResultFocusToken = null
                    focusSearchField()
                    onClearQuery()
                },
                searchFieldRequester = searchFieldRequester,
                onSearchFieldEditingChange = { editing -> searchFieldEditing = editing },
                onImeSearch = {
                    pendingResultFocusToken = onSubmitSearch()
                },
                onEditingCommitted = onSearchCommitted,
                modifier =
                    Modifier.padding(
                        start = contentStartPadding,
                        top = TvDimens.overscanVertical,
                        end = TvDimens.overscanHorizontal,
                    ),
            )
            if (heroVisible) {
                TvLibraryGridHero(
                    session,
                    hero.item,
                    contentStartPadding = contentStartPadding,
                    showBackdrop = false,
                    reserveGridViewport = true,
                )
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides verticalBringIntoViewSpec) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(resultScrollState)
                            .padding(bottom = TvDimens.overscanVertical),
                    verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
                ) {
                    TvFindResultsPane(
                        session = session,
                        state = state,
                        resultsFocusRequester = resultsFocusRequester,
                        contentStartPadding = contentStartPadding,
                        onRecentSelected = { search ->
                            focusSearchField()
                            pendingResultFocusToken = onRecentSelected(search)
                        },
                        onClearRecentSearches = onClearRecentSearches,
                        onPersonSelected = { person ->
                            pendingResultFocusToken = null
                            onPersonSelected(person)
                        },
                        onResultTabSelected = { tab ->
                            pendingResultFocusToken = null
                            onResultTabSelected(tab)
                        },
                        onRetry = onRetry,
                        onItemSelected = onItemSelected,
                        onItemPlayDirect = onItemPlayDirect,
                        onMediaFocused = { focusedMedia = it },
                    )
                }
            }
        }
    }
}

internal fun FindResultsUi.hasResultsFor(tab: FindResultTab): Boolean =
    when (tab) {
        FindResultTab.All -> !isEmpty
        FindResultTab.Movies -> movies.isNotEmpty()
        FindResultTab.Shows -> shows.isNotEmpty()
        FindResultTab.Episodes -> episodes.isNotEmpty()
    }
