// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.LoadingIndicator
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.RetryableError
import com.jellyscope.ui.component.ScrollToTopOnChange
import com.jellyscope.ui.component.TooltipIconButton
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.directPlayAction
import com.jellyscope.ui.component.rememberAutoHidingTopBarVisible
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.grid_error
import com.jellyscope.ui.generated.resources.grid_loading
import com.jellyscope.ui.generated.resources.grid_shuffle
import com.jellyscope.ui.generated.resources.grid_sort
import com.jellyscope.ui.generated.resources.grid_sort_date_added
import com.jellyscope.ui.generated.resources.grid_sort_default
import com.jellyscope.ui.generated.resources.grid_sort_release_date
import com.jellyscope.ui.generated.resources.grid_sort_runtime
import com.jellyscope.ui.generated.resources.grid_sort_title
import com.jellyscope.ui.generated.resources.home_favorites
import com.jellyscope.ui.generated.resources.home_next_up
import com.jellyscope.ui.generated.resources.home_recently_added
import com.jellyscope.ui.generated.resources.home_resume
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
fun GridScreen(
    session: Session,
    row: HomeRow,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onShuffleQueue: (List<String>) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GridViewModel =
        koinViewModel(
            parameters = { parametersOf(session, row) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    GridContent(
        session = session,
        state = state,
        row = row,
        onBack = onBack,
        onRetry = viewModel::retry,
        onSortSelected = viewModel::setSort,
        onShuffle = { onShuffleQueue(viewModel.buildShuffleQueue()) },
        onItemSelected = onItemSelected,
        onPlayItem = onPlayItem,
        onSettingsClick = onSettingsClick,
        modifier = modifier,
    )
}

@Composable
internal fun GridContent(
    session: Session,
    state: GridUiState,
    row: HomeRow,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSortSelected: (GridSort) -> Unit,
    onShuffle: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val topBarVisible = rememberAutoHidingTopBarVisible(gridState)
    val title = stringResource(row.titleResource())
    val contentState = state as? GridUiState.Content
    val shuffleEnabled = contentState?.items.orEmpty().isNotEmpty()
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val topContentPadding = appTopBarContentPadding()
    val bottomContentPadding = appNavigationBarContentPadding()

    ScrollToTopOnChange(contentState?.sort) {
        gridState.scrollToItem(0)
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            GridUiState.Loading ->
                GridCenteredMessage(
                    text = stringResource(Res.string.grid_loading),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                start = horizontalContentPadding.start,
                                top = topContentPadding,
                                end = horizontalContentPadding.end,
                                bottom = bottomContentPadding,
                            ),
                )
            is GridUiState.Error ->
                GridError(
                    retryable = state.retryable,
                    onRetry = onRetry,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                start = horizontalContentPadding.start,
                                top = topContentPadding,
                                end = horizontalContentPadding.end,
                                bottom = bottomContentPadding,
                            ),
                )
            is GridUiState.Content -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(Dimensions.gridMinCellWidth.tileScaled()),
                    state = gridState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .desktopScrollInput(gridState, DesktopScrollOrientation.Vertical),
                    contentPadding =
                        horizontalContentPadding.asPaddingValues(
                            top = topContentPadding,
                            bottom = bottomContentPadding,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
                ) {
                    gridItems(
                        items = state.items,
                        key = { item -> item.id },
                    ) { item ->
                        // Keyed on the whole item, not its id: both lambdas close over
                        // item state that changes while the id stays stable (the
                        // direct-play action captures `resumePositionTicks`).
                        val onItemClick = remember(item, onItemSelected) { { onItemSelected(item) } }
                        val onItemLongClick = remember(item, onPlayItem) { item.directPlayAction(onPlayItem) }
                        MediaCard(
                            item = item,
                            session = session,
                            onClick = onItemClick,
                            onLongClick = onItemLongClick,
                        )
                    }
                }
            }
        }
        AppTopBar(
            title = title,
            visible = topBarVisible,
            onBack = onBack,
            onSettingsClick = onSettingsClick,
            modifier = Modifier.align(Alignment.TopCenter),
            actions = {
                GridShuffleAction(
                    onShuffle = onShuffle,
                    shuffleEnabled = shuffleEnabled,
                )
                if (contentState != null) {
                    GridSortMenu(
                        selected = contentState.sort,
                        onSortSelected = onSortSelected,
                    )
                }
            },
        )
    }
}

@Composable
private fun GridShuffleAction(
    onShuffle: () -> Unit,
    shuffleEnabled: Boolean,
) {
    val label = stringResource(Res.string.grid_shuffle)
    TooltipIconButton(
        label = label,
        onClick = onShuffle,
        enabled = shuffleEnabled,
        modifier = Modifier.size(Dimensions.minTouchTarget),
    ) {
        Icon(
            imageVector = Icons.Filled.Shuffle,
            contentDescription = null,
            modifier = Modifier.size(Dimensions.controlButtonIconSize),
        )
    }
}

@Composable
private fun GridSortMenu(
    selected: GridSort,
    onSortSelected: (GridSort) -> Unit,
) {
    val sortChoices = remember { gridSortChoices() }
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.grid_sort)

    Box {
        TooltipIconButton(
            label = label,
            onClick = { expanded = true },
            modifier = Modifier.size(Dimensions.minTouchTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.controlButtonIconSize),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            sortChoices.forEach { choice ->
                val choiceSelected = selected == choice.sort
                DropdownMenuItem(
                    text = { Text(stringResource(choice.label)) },
                    onClick = {
                        expanded = false
                        onSortSelected(choice.sort)
                    },
                    trailingIcon =
                        if (choiceSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun GridCenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadingIndicator()
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun GridError(
    retryable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        RetryableError(
            message = stringResource(Res.string.grid_error),
            retryable = retryable,
            onRetry = onRetry,
        )
    }
}

private data class GridSortChoice(
    val sort: GridSort,
    val label: StringResource,
)

private fun gridSortChoices(): List<GridSortChoice> =
    listOf(
        GridSortChoice(GridSort.Default, Res.string.grid_sort_default),
        GridSortChoice(GridSort.Title, Res.string.grid_sort_title),
        GridSortChoice(GridSort.DateAdded, Res.string.grid_sort_date_added),
        GridSortChoice(GridSort.ReleaseDate, Res.string.grid_sort_release_date),
        GridSortChoice(GridSort.Runtime, Res.string.grid_sort_runtime),
    )

private fun HomeRow.titleResource(): StringResource =
    when (this) {
        HomeRow.ContinueWatching -> Res.string.home_resume
        HomeRow.NextUp -> Res.string.home_next_up
        HomeRow.RecentlyAdded -> Res.string.home_recently_added
        HomeRow.Favorites -> Res.string.home_favorites
    }
