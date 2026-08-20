// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.LoadingIndicator
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.RetryableError
import com.jellyscope.ui.component.ScrollToTopOnChange
import com.jellyscope.ui.component.TopBarSlideEnter
import com.jellyscope.ui.component.TopBarSlideExit
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.component.rememberAutoHidingTopBarVisible
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.home_empty_row
import com.jellyscope.ui.generated.resources.library_error
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryBrowseGridContent(
    session: Session,
    state: LibraryBrowseUiState,
    gridState: LazyGridState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSetSort: (LibrarySortBy, LibrarySortOrder) -> Unit,
    onSetFilters: (LibraryFilterSelection) -> Unit,
    onShuffleAll: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
    controlsResetKey: Any? = state.parentId,
    bottomContentPadding: Dp = Dimensions.screenPadding,
    topBarContent: @Composable (@Composable RowScope.() -> Unit) -> Unit = { _ -> },
    trailingControls: @Composable () -> Unit = {},
) {
    var filtersDialogVisible by remember { mutableStateOf(false) }
    val controlsVisible = rememberAutoHidingTopBarVisible(gridState, controlsResetKey)
    val topBarContentPadding = appTopBarContentPadding()
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val density = LocalDensity.current
    var barsHeightPx by remember(density, topBarContentPadding) {
        mutableStateOf(with(density) { topBarContentPadding.roundToPx() })
    }
    val topBarsHeight = with(density) { barsHeightPx.toDp() }
    val filterOptions = state.filterOptions
    val scrollResetKey =
        remember(state.sortBy, state.sortOrder, state.filters) {
            LibraryBrowseScrollResetKey(
                sortBy = state.sortBy,
                sortOrder = state.sortOrder,
                filters = state.filters,
            )
        }

    ScrollToTopOnChange(scrollResetKey) {
        gridState.scrollToItem(0)
    }

    if (filtersDialogVisible) {
        LibraryFilterDialog(
            filters = state.filters,
            options = filterOptions,
            onApply = onSetFilters,
            onDismiss = { filtersDialogVisible = false },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading && state.items.isEmpty() ->
                LoadingMessage(
                    Modifier
                        .align(Alignment.Center)
                        .padding(
                            start = horizontalContentPadding.start,
                            top = topBarsHeight,
                            end = horizontalContentPadding.end,
                            bottom = bottomContentPadding,
                        ),
                )
            state.error && state.items.isEmpty() ->
                ErrorMessage(
                    onRetry = onRetry,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(
                                start = horizontalContentPadding.start,
                                top = topBarsHeight,
                                end = horizontalContentPadding.end,
                                bottom = bottomContentPadding,
                            ),
                )
            state.items.isEmpty() ->
                Text(
                    text = stringResource(Res.string.home_empty_row),
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(
                                start = horizontalContentPadding.start,
                                top = topBarsHeight,
                                end = horizontalContentPadding.end,
                                bottom = bottomContentPadding,
                            ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            else ->
                LibraryGrid(
                    session = session,
                    state = state,
                    gridState = gridState,
                    onLoadMore = onLoadMore,
                    onItemSelected = onItemSelected,
                    onPlayItem = onPlayItem,
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            start = horizontalContentPadding.start,
                            top = topBarsHeight,
                            end = horizontalContentPadding.end,
                            bottom = bottomContentPadding,
                        ),
                )
        }
        if (state.items.isNotEmpty()) {
            LibraryGridScrollbar(
                state = gridState,
                topPadding = topBarsHeight,
                bottomPadding = bottomContentPadding,
                endPadding = horizontalContentPadding.end,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        if (state.isLoadingMore) {
            LoadingMessage(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomContentPadding),
            )
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = TopBarSlideEnter,
            exit = TopBarSlideExit,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { size ->
                        if (size.height > barsHeightPx) {
                            barsHeightPx = size.height
                        }
                    },
        ) {
            topBarContent {
                LibrarySortMenu(
                    collectionType = state.collectionType,
                    sortBy = state.sortBy,
                    sortOrder = state.sortOrder,
                    onSetSort = onSetSort,
                )
                LibraryFilterButton(
                    filters = state.filters,
                    onClick = { filtersDialogVisible = true },
                )
                if (state.collectionType == LibraryCollectionType.Movies) {
                    LibraryShuffleButton(
                        isPreparing = state.isPreparingShuffle,
                        onClick = onShuffleAll,
                    )
                }
                trailingControls()
            }
        }
    }
}

@Composable
internal fun LibraryListModeContent(
    session: Session,
    state: LibraryBrowseUiState,
    listState: LazyListState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSetSort: (LibrarySortBy, LibrarySortOrder) -> Unit,
    onSetFilters: (LibraryFilterSelection) -> Unit,
    onShuffleAll: () -> Unit,
    onToggleMode: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.screenPadding,
    topBarContent: @Composable (@Composable RowScope.() -> Unit) -> Unit = { _ -> },
) {
    var filtersDialogVisible by remember { mutableStateOf(false) }
    val controlsVisible = rememberAutoHidingTopBarVisible(listState, state.parentId)
    val topBarContentPadding = appTopBarContentPadding()
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val density = LocalDensity.current
    var barsHeightPx by remember(density, topBarContentPadding) {
        mutableStateOf(with(density) { topBarContentPadding.roundToPx() })
    }
    val topBarsHeight = with(density) { barsHeightPx.toDp() }
    val filterOptions = state.filterOptions
    val scrollResetKey =
        remember(state.sortBy, state.sortOrder, state.filters) {
            LibraryBrowseScrollResetKey(
                sortBy = state.sortBy,
                sortOrder = state.sortOrder,
                filters = state.filters,
            )
        }

    ScrollToTopOnChange(scrollResetKey) {
        listState.scrollToItem(0)
    }

    if (filtersDialogVisible) {
        LibraryFilterDialog(
            filters = state.filters,
            options = filterOptions,
            onApply = onSetFilters,
            onDismiss = { filtersDialogVisible = false },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading && state.items.isEmpty() ->
                LoadingMessage(
                    Modifier
                        .align(Alignment.Center)
                        .padding(
                            start = horizontalContentPadding.start,
                            top = topBarsHeight,
                            end = horizontalContentPadding.end,
                            bottom = bottomContentPadding,
                        ),
                )
            state.error && state.items.isEmpty() ->
                ErrorMessage(
                    onRetry = onRetry,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(
                                start = horizontalContentPadding.start,
                                top = topBarsHeight,
                                end = horizontalContentPadding.end,
                                bottom = bottomContentPadding,
                            ),
                )
            state.items.isEmpty() ->
                Text(
                    text = stringResource(Res.string.home_empty_row),
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(
                                start = horizontalContentPadding.start,
                                top = topBarsHeight,
                                end = horizontalContentPadding.end,
                                bottom = bottomContentPadding,
                            ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            else ->
                LibraryList(
                    session = session,
                    state = state,
                    listState = listState,
                    onLoadMore = onLoadMore,
                    onItemSelected = onItemSelected,
                    onPlayItem = onPlayItem,
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            start = horizontalContentPadding.start,
                            top = topBarsHeight,
                            end = horizontalContentPadding.end,
                            bottom = bottomContentPadding,
                        ),
                )
        }
        if (state.items.isNotEmpty()) {
            LibraryListScrollbar(
                state = listState,
                topPadding = topBarsHeight,
                bottomPadding = bottomContentPadding,
                endPadding = horizontalContentPadding.end,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        if (state.isLoadingMore) {
            LoadingMessage(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomContentPadding),
            )
        }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = TopBarSlideEnter,
            exit = TopBarSlideExit,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { size ->
                        if (size.height > barsHeightPx) {
                            barsHeightPx = size.height
                        }
                    },
        ) {
            topBarContent {
                LibrarySortMenu(
                    collectionType = state.collectionType,
                    sortBy = state.sortBy,
                    sortOrder = state.sortOrder,
                    onSetSort = onSetSort,
                )
                LibraryFilterButton(
                    filters = state.filters,
                    onClick = { filtersDialogVisible = true },
                )
                if (state.collectionType == LibraryCollectionType.Movies) {
                    LibraryShuffleButton(
                        isPreparing = state.isPreparingShuffle,
                        onClick = onShuffleAll,
                    )
                }
                LibraryViewModeButton(
                    listMode = true,
                    onToggleMode = onToggleMode,
                )
            }
        }
    }
}

@Composable
private fun LoadingMessage(modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier)
}

@Composable
private fun ErrorMessage(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RetryableError(
        message = stringResource(Res.string.library_error),
        retryable = true,
        onRetry = onRetry,
        modifier = modifier,
    )
}

private data class LibraryBrowseScrollResetKey(
    val sortBy: LibrarySortBy,
    val sortOrder: LibrarySortOrder,
    val filters: LibraryFilterSelection,
)
