// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.LocalTvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.acceptsFocusedChild
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.grid.GridSort
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvGridLoadedContent(
    session: Session,
    row: HomeRow,
    items: List<MediaCardUi>,
    sort: GridSort,
    sortPickerOpen: Boolean,
    sortButtonRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    onOpenSortPicker: () -> Unit,
    onShuffle: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
    ) {
        TvGridHeader(
            title = gridRowTitle(row),
            itemCount = items.size,
            sort = sort,
            sortButtonRequester = sortButtonRequester,
            gridFocusRequester = gridFocusRequester,
            onShuffle = onShuffle,
            onOpenSortPicker = onOpenSortPicker,
            modifier =
                Modifier.padding(
                    start = TvDimens.overscanHorizontal,
                    top = TvDimens.overscanVertical,
                    end = TvDimens.overscanHorizontal,
                ),
        )
        TvGrid(
            session = session,
            items = items,
            sort = sort,
            sortPickerOpen = sortPickerOpen,
            gridFocusRequester = gridFocusRequester,
            onItemSelected = onItemSelected,
            onItemPlayDirect = onItemPlayDirect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TvGridHeader(
    title: String,
    itemCount: Int,
    sort: GridSort,
    sortButtonRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    onShuffle: () -> Unit,
    onOpenSortPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText(
            text = title,
            style = TvScreenHeaderStyle,
            maxLines = 1,
        )
        TvText(
            text = stringResource(R.string.tv_grid_item_count, itemCount),
            style = TvSecondaryStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.weight(1f))
        // DOWN from options enters the grid.
        TvButton(
            text = stringResource(R.string.tv_shuffle),
            onClick = onShuffle,
            enabled = itemCount > 0,
            modifier = Modifier.focusProperties { down = gridFocusRequester },
        )
        TvButton(
            text = stringResource(R.string.tv_sort_button, gridSortLabel(sort)),
            onClick = onOpenSortPicker,
            enabled = itemCount > 0,
            modifier =
                Modifier
                    .focusRequester(sortButtonRequester)
                    .focusProperties { down = gridFocusRequester },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvGrid(
    session: Session,
    items: List<MediaCardUi>,
    sort: GridSort,
    sortPickerOpen: Boolean,
    gridFocusRequester: FocusRequester,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinator = requireNotNull(LocalTvFocusCoordinator.current)
    val gridScope = rememberTvFocusScopeNode(listOf("grid", "items"))
    val gridState = rememberLazyGridState()
    var lastFocusedItemId by rememberSaveable(sort) { mutableStateOf<String?>(null) }
    var appliedSortKey by rememberSaveable { mutableStateOf(sort.name) }

    // Do not save this flag; detail return must request restored focus again.
    var initialFocusRequested by remember { mutableStateOf(false) }

    // Focus requesters must attach to real cards, not the focusGroup.
    var gridHasFocus by remember { mutableStateOf(false) }

    // Read remembered identity only while focus is outside the grid.
    val entryTargetItemId =
        if (gridHasFocus) {
            null
        } else {
            coordinator.activeMemory.childFor(gridScope.scopeKey)?.removePrefix("item:")
                ?: lastFocusedItemId
        }
    val entryIndex by remember(items, entryTargetItemId) {
        derivedStateOf {
            val savedIndex =
                entryTargetItemId
                    ?.let { id -> items.indexOfFirst { item -> item.id == id } }
                    ?.takeIf { index -> index >= 0 }
            val visibleSavedIndex =
                savedIndex?.takeIf { target ->
                    gridState.layoutInfo.visibleItemsInfo.any { item -> item.index == target }
                }
            (visibleSavedIndex ?: gridState.firstVisibleItemIndex)
                .coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }
    val entryTarget = if (gridHasFocus) -1 else entryIndex
    val columnCount = rememberStableGridColumnCount(gridState)
    val focusNav =
        rememberTvGridFocusNav(
            gridState = gridState,
            itemCount = items.size,
            columnCount = columnCount,
        )
    val gridBringIntoViewSpec = rememberRowAwareMarioBringIntoViewSpec()

    val restoreWaiting = coordinator.pendingRestore != null && coordinator.readyRestore == null
    // Re-run only when a pending restore resolves, not on every focus move.
    LaunchedEffect(items, sortPickerOpen, restoreWaiting) {
        if (!initialFocusRequested && !sortPickerOpen && items.isNotEmpty() && !restoreWaiting) {
            val targetItemId =
                coordinator.activeMemory.childFor(gridScope.scopeKey)?.removePrefix("item:")
                    ?: lastFocusedItemId
            val targetIndex =
                targetItemId
                    ?.let { id -> items.indexOfFirst { item -> item.id == id } }
                    ?.takeIf { index -> index >= 0 }
                    ?: gridState.firstVisibleItemIndex.coerceIn(0, items.lastIndex)
            withFrameNanos { }
            focusNav.requestFocus(targetIndex)
            initialFocusRequested = true
        }
    }

    // Reset only for a new sort, not detail return.
    LaunchedEffect(sort) {
        val nextSortKey = sort.name
        if (appliedSortKey != nextSortKey) {
            appliedSortKey = nextSortKey
            coordinator.clearScope(gridScope.scopeKey)
            focusNav.resetFocus()
            gridState.scrollToItem(0)
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides gridBringIntoViewSpec) {
        key(sort) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TvDimens.posterWidth.tileScaled()),
                modifier =
                    modifier
                        .fillMaxWidth()
                        .focusProperties {
                            canFocus = !sortPickerOpen
                        }.tvFocusScope(gridScope) {
                            gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                                items.getOrNull(info.index)?.let { item -> "item:${item.id}" }
                            }
                        }.onFocusChanged { state -> gridHasFocus = state.hasFocus }
                        .onPreviewKeyEvent(focusNav::onPreviewKeyEvent),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                contentPadding =
                    PaddingValues(
                        start = TvDimens.overscanHorizontal + TvDimens.gridContentHorizontalPadding,
                        // Reserve focus-scale room at viewport edges.
                        top = TvDimens.gridContentTopPadding,
                        end = TvDimens.overscanHorizontal + TvDimens.gridContentHorizontalPadding,
                        bottom = TvDimens.overscanVertical + TvDimens.gridContentTopPadding,
                    ),
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> "media-card" },
                ) { index, item ->
                    val rightEdgeItem = (index + 1) % columnCount == 0 || index == items.lastIndex
                    val itemFocusRequester =
                        focusNav.rememberItemFocusRequester(
                            index = index,
                            entryFocusRequester = gridFocusRequester.takeIf { index == entryTarget },
                        )
                    val itemKey = "item:${item.id}"
                    DisposableEffect(gridScope, itemKey, itemFocusRequester) {
                        gridScope.register(itemKey, itemFocusRequester)
                        onDispose { gridScope.unregister(itemKey, itemFocusRequester) }
                    }
                    val onItemClick = remember(item.id, onItemSelected) { { onItemSelected(item) } }
                    val onItemPlay = remember(item.id, onItemPlayDirect) { { onItemPlayDirect(item) } }
                    TvMediaCard(
                        session = session,
                        item = item,
                        wide = false,
                        modifier =
                            Modifier.onPreviewKeyEvent { event ->
                                rightEdgeItem &&
                                    event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionRight
                            },
                        focusRequester = itemFocusRequester,
                        onFocused = {
                            focusNav.onItemFocused(index)
                            lastFocusedItemId = item.id
                            val path = gridScope.path(itemKey, TvFocusTargetKind.Item, index)
                            coordinator.recordFocused(path)
                            coordinator.readyRestore?.let { restore ->
                                if (
                                    restore.acceptsFocusedChild(
                                        path.scopes,
                                        itemKey,
                                        index,
                                        TvFocusTargetKind.Item,
                                    )
                                ) {
                                    coordinator.resolveRestore(restore.token, path)
                                }
                            }
                        },
                        onClick = onItemClick,
                        onPlayDirect = onItemPlay,
                    )
                }
            }
        }
    }
}
