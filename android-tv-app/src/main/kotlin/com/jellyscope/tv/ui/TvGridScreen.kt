// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.grid.GridSort
import com.jellyscope.ui.screen.grid.GridUiState
import com.jellyscope.ui.screen.grid.GridViewModel
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.jellyscope.ui.component.AdaptiveCenteredSpinner as TvCenteredSpinner

@Composable
fun TvGridScreen(
    session: Session,
    row: HomeRow,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit,
    onFindSelected: () -> Unit,
    onDiscoverSelected: () -> Unit,
    onFavoritesSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onShuffle: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GridViewModel =
        koinViewModel(
            key = "grid-${row.name}",
            parameters = { parametersOf(session, row) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TvGridContent(
        session = session,
        state = state,
        onBack = onBack,
        onHomeSelected = onHomeSelected,
        onFindSelected = onFindSelected,
        onDiscoverSelected = onDiscoverSelected,
        onFavoritesSelected = onFavoritesSelected,
        onSettingsSelected = onSettingsSelected,
        onItemSelected = onItemSelected,
        onItemPlayDirect = onItemPlayDirect,
        onShuffle = { onShuffle(viewModel.buildShuffleQueue()) },
        onRetry = viewModel::retry,
        onSortSelected = viewModel::setSort,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvGridContent(
    session: Session,
    state: GridUiState,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit,
    onFindSelected: () -> Unit,
    onDiscoverSelected: () -> Unit,
    onFavoritesSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onShuffle: () -> Unit,
    onRetry: () -> Unit,
    onSortSelected: (GridSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortPickerOpen by rememberSaveable { mutableStateOf(false) }
    val sortButtonRequester = remember { FocusRequester() }

    // Attached to the first VISIBLE grid card (a real focusable — requesting
    // focus on a focusGroup node silently does nothing). Every entry path
    // into the grid targets this: initial focus, DOWN from the option pills,
    // RIGHT from the rail.
    val gridFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = sortPickerOpen) {
        sortPickerOpen = false
    }

    // Closing the sort picker (select or dismiss) returns focus to the Sort
    // button — same contract as the player pickers; without this, focus
    // died with the picker and stranded the remote.
    var sortPickerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sortPickerOpen) {
        if (sortPickerOpen) {
            sortPickerWasOpen = true
        } else if (sortPickerWasOpen) {
            sortPickerWasOpen = false
            sortButtonRequester.requestFocusSafely()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalAppBackgroundBrush.current)
                // Own BACK explicitly (player-style): the dispatcher route
                // proved unreliable here — BACK consumed nothing and just
                // cleared focus. Picker first, then leave the screen.
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.Back &&
                        run {
                            if (sortPickerOpen) {
                                sortPickerOpen = false
                            } else {
                                onBack()
                            }
                            true
                        }
                },
    ) {
        Row(
            modifier =
                Modifier.fillMaxSize(),
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .focusProperties { canFocus = !sortPickerOpen }
                        .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
            ) {
                when (state) {
                    GridUiState.Loading ->
                        TvCenteredSpinner(drawBackground = false)
                    is GridUiState.Error ->
                        TvGridError(
                            retryable = state.retryable,
                            onRetry = onRetry,
                            modifier =
                                Modifier.padding(
                                    horizontal = TvDimens.overscanHorizontal,
                                    vertical = TvDimens.overscanVertical,
                                ),
                        )
                    is GridUiState.Content ->
                        TvGridLoadedContent(
                            session = session,
                            row = state.row,
                            items = state.items,
                            sort = state.sort,
                            sortPickerOpen = sortPickerOpen,
                            sortButtonRequester = sortButtonRequester,
                            gridFocusRequester = gridFocusRequester,
                            onOpenSortPicker = { sortPickerOpen = true },
                            onShuffle = onShuffle,
                            onItemSelected = onItemSelected,
                            onItemPlayDirect = onItemPlayDirect,
                        )
                }
            }
        }

        val content = state as? GridUiState.Content
        if (sortPickerOpen && content != null) {
            TvGridSortPickerOverlay(
                selected = content.sort,
                onSelectSort = { sort ->
                    onSortSelected(sort)
                    sortPickerOpen = false
                },
                onDismiss = { sortPickerOpen = false },
            )
        }
    }
}
