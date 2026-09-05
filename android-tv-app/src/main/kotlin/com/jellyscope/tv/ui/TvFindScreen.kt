// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import com.jellyscope.ui.screen.find.FindResultTab
import com.jellyscope.ui.screen.find.FindResultsUi
import com.jellyscope.ui.screen.find.FindUiState
import com.jellyscope.ui.screen.find.FindViewModel
import com.jellyscope.ui.screen.find.PersonUi
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
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
        viewModel.refreshSilently()
    }

    TvFindContent(
        session = session,
        state = state,
        onBack = onBack,
        onQueryTextChanged = viewModel::onQueryTextChanged,
        onClearQuery = viewModel::clearQuery,
        onRecentSelected = viewModel::selectRecentSearch,
        onClearRecentSearches = viewModel::clearRecentSearches,
        onPersonSelected = viewModel::selectPerson,
        onResultTabSelected = viewModel::selectResultTab,
        onRetry = viewModel::retry,
        onSearchCommitted = viewModel::commitRecentSearch,
        onItemSelected = onItemSelected,
        onItemPlayDirect = onItemPlayDirect,
        modifier = modifier,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvFindContent(
    session: Session,
    state: FindUiState,
    onBack: () -> Unit,
    onQueryTextChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRecentSelected: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onPersonSelected: (PersonUi) -> Unit,
    onResultTabSelected: (FindResultTab) -> Unit,
    onRetry: () -> Unit,
    onSearchCommitted: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchFieldRequester = remember { FocusRequester() }
    val resultsFocusRequester = remember { FocusRequester() }
    val hostedRailController = LocalTvHostedRailController.current
    val hostedRail = hostedRailController.enabled
    val hostedRailVisible = hostedRailController.visible
    val requestInitialContentFocus = !hostedRailController.contentAutofocusSuppressed
    val hostedRailContentRegistrationKey = remember { hostedRailController.contentRegistrationKey }
    val resultScrollState = rememberScrollState()
    var recentResultFocusPending by remember { mutableStateOf(false) }
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

    // IME "Search" action / reaching results: focus the first result ribbon now if
    // results are in, otherwise focus them as soon as the debounced search returns
    // (handled by the pending-focus effect below).
    fun focusResults() {
        if (state.groupedResults.hasResultsFor(state.selectedResultTab)) {
            resultsFocusRequester.requestFocusSafely()
        } else {
            recentResultFocusPending = true
        }
    }

    LaunchedEffect(Unit) {
        if (requestInitialContentFocus) {
            requestTvFocusWithRetry { focusSearchField() }
        }
    }

    LaunchedEffect(
        recentResultFocusPending,
        state.groupedResults,
        state.selectedResultTab,
        state.isSearching,
        state.error,
    ) {
        if (!recentResultFocusPending) {
            return@LaunchedEffect
        }
        if (state.groupedResults.hasResultsFor(state.selectedResultTab)) {
            withFrameNanos { }
            resultsFocusRequester.requestFocusSafely()
            recentResultFocusPending = false
        } else if (!state.isSearching) {
            recentResultFocusPending = false
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalAppBackgroundBrush.current)
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
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
        ) {
            TvFindHeader(
                queryText = state.queryText,
                onQueryTextChanged = onQueryTextChanged,
                onClearQuery = {
                    recentResultFocusPending = false
                    focusSearchField()
                    onClearQuery()
                },
                searchFieldRequester = searchFieldRequester,
                onSearchFieldEditingChange = { editing -> searchFieldEditing = editing },
                onImeSearch = { focusResults() },
                onEditingCommitted = onSearchCommitted,
                modifier =
                    Modifier.padding(
                        start = contentStartPadding,
                        top = TvDimens.overscanVertical,
                        end = TvDimens.overscanHorizontal,
                    ),
            )
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
                        recentResultFocusPending = true
                        focusSearchField()
                        onRecentSelected(search)
                    },
                    onClearRecentSearches = onClearRecentSearches,
                    onPersonSelected = onPersonSelected,
                    onResultTabSelected = onResultTabSelected,
                    onRetry = onRetry,
                    onItemSelected = onItemSelected,
                    onItemPlayDirect = onItemPlayDirect,
                )
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
