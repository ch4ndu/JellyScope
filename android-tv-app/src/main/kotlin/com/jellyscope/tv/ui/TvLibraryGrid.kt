// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.Dp
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.LocalTvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusContentReadiness
import com.jellyscope.tv.ui.focus.TvFocusResolution
import com.jellyscope.tv.ui.focus.TvFocusResolver
import com.jellyscope.tv.ui.focus.TvFocusRestoreStatus
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.acceptsFocusedChild
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.LoadMoreOnApproachEnd
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.library.LibraryBrowseUiState
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.launch
import com.jellyscope.ui.component.AdaptiveCenteredSpinner as TvCenteredSpinner
import com.jellyscope.ui.component.DetailText as TvText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvLibraryBody(
    session: Session,
    state: LibraryBrowseUiState,
    pickerOpen: Boolean,
    constrainedByHero: Boolean,
    parkLoadingFocus: Boolean,
    requestInitialFocus: Boolean,
    contentStartPadding: Dp,
    loadingFocusRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    onRequestGridFocusChanged: ((() -> Boolean)?) -> Unit,
    initialFocusRequested: MutableState<Boolean>,
    gridUpTarget: FocusRequester,
    onRequestRailFocus: () -> Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onItemFocused: (MediaCardUi) -> Unit = {},
    onFocusRegionChanged: (TvLibraryFocusRegion) -> Unit = {},
    onPosterLoaded: (itemId: String, bitmap: Bitmap) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (state.items.isEmpty()) {
        SideEffect { onFocusRegionChanged(TvLibraryFocusRegion.Chrome) }
    }
    when {
        state.isLoading && state.items.isEmpty() ->
            if (parkLoadingFocus) {
                TvLoadingFocusPark(
                    focusRequester = loadingFocusRequester,
                    onRequestRailFocus = onRequestRailFocus,
                    modifier = modifier,
                    requestFocusOnAttach = requestInitialFocus,
                )
            } else {
                TvCenteredSpinner(modifier = modifier, drawBackground = false)
            }
        state.error && state.items.isEmpty() ->
            TvLibraryError(
                onRetry = onRetry,
                modifier =
                    Modifier.padding(
                        start = contentStartPadding,
                        end = TvDimens.overscanHorizontal,
                    ),
            )
        state.items.isEmpty() ->
            TvText(
                text = stringResource(R.string.tv_library_empty),
                modifier =
                    Modifier.padding(
                        start = contentStartPadding,
                        end = TvDimens.overscanHorizontal,
                    ),
                color = LocalJellyfinPalette.current.textSecondary,
            )
        else ->
            TvLibraryGrid(
                session = session,
                state = state,
                pickerOpen = pickerOpen,
                constrainedByHero = constrainedByHero,
                contentStartPadding = contentStartPadding,
                gridFocusRequester = gridFocusRequester,
                onRequestGridFocusChanged = onRequestGridFocusChanged,
                initialFocusRequested = initialFocusRequested,
                requestInitialFocus = requestInitialFocus,
                gridUpTarget = gridUpTarget,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                onItemFocused = onItemFocused,
                onFocusRegionChanged = onFocusRegionChanged,
                onPosterLoaded = onPosterLoaded,
                modifier = modifier,
            )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvLibraryGrid(
    session: Session,
    state: LibraryBrowseUiState,
    pickerOpen: Boolean,
    constrainedByHero: Boolean,
    contentStartPadding: Dp,
    gridFocusRequester: FocusRequester,
    onRequestGridFocusChanged: ((() -> Boolean)?) -> Unit,
    initialFocusRequested: MutableState<Boolean>,
    requestInitialFocus: Boolean,
    gridUpTarget: FocusRequester,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onItemFocused: (MediaCardUi) -> Unit,
    onFocusRegionChanged: (TvLibraryFocusRegion) -> Unit,
    onPosterLoaded: (itemId: String, bitmap: Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinator = requireNotNull(LocalTvFocusCoordinator.current)
    val gridFocusScope = rememberTvFocusScopeNode(listOf("library", "grid"))
    val gridState = rememberLazyGridState()
    val focusScope = rememberCoroutineScope()
    val pageRetryRequester = remember { FocusRequester() }
    var pageRetryHasFocus by remember { mutableStateOf(false) }
    var pageRetryReturnIndex by remember { mutableStateOf(0) }
    val requestGridUpFocus =
        remember(gridUpTarget, focusScope) {
            {
                if (!gridUpTarget.requestFocusSafely()) {
                    focusScope.launch {
                        requestTvFocusWithRetry(attempts = LIBRARY_GRID_EXIT_FOCUS_ATTEMPTS) {
                            gridUpTarget.requestFocusSafely()
                        }
                    }
                }
                true
            }
        }
    val gridResetKey =
        remember(state.sortBy, state.sortOrder, state.filters) {
            libraryGridFocusResetKey(state.sortBy, state.sortOrder, state.filters)
        }
    var lastFocusedItemId by rememberSaveable(state.sortBy, state.sortOrder, state.filters) {
        mutableStateOf<String?>(null)
    }
    var appliedGridResetKey by rememberSaveable { mutableStateOf(gridResetKey) }
    var gridHasFocus by remember { mutableStateOf(false) }
    // Read only while focus is outside the grid — see the same guard in
    // TvGridBody. `activeMemory` is replaced on every focus event and
    // `lastFocusedItemId` is written by every card, so reading either at this scope
    // made every D-pad move invalidate the grid and rescan `state.items`. The value
    // is only consulted on entry, where this guard still supplies it.
    val entryTargetItemId =
        if (gridHasFocus) {
            null
        } else {
            coordinator.activeMemory
                .childFor(gridFocusScope.scopeKey)
                ?.removePrefix("item:")
                ?: lastFocusedItemId
        }
    val entryIndex by remember(state.items, entryTargetItemId) {
        derivedStateOf {
            val savedIndex =
                entryTargetItemId
                    ?.let { id -> state.items.indexOfFirst { item -> item.id == id } }
                    ?.takeIf { index -> index >= 0 }
            val visibleSavedIndex =
                savedIndex?.takeIf { target ->
                    gridState.layoutInfo.visibleItemsInfo.any { item -> item.index == target }
                }
            (visibleSavedIndex ?: gridState.firstVisibleItemIndex)
                .coerceIn(0, (state.items.size - 1).coerceAtLeast(0))
        }
    }
    val entryTarget = if (gridHasFocus) -1 else entryIndex
    val columnCount = rememberStableGridColumnCount(gridState)
    val gridLayoutReady by remember {
        derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() }
    }
    val currentOnFocusRegionChanged = rememberUpdatedState(onFocusRegionChanged)
    LaunchedEffect(gridHasFocus, state.items, columnCount, gridLayoutReady) {
        if (!gridHasFocus) {
            return@LaunchedEffect
        }
        val focusedItemIndex =
            lastFocusedItemId
                ?.let { focusedId -> state.items.indexOfFirst { item -> item.id == focusedId } }
                ?.takeIf { index -> index >= 0 }
        currentOnFocusRegionChanged.value(
            when {
                !gridLayoutReady || focusedItemIndex == null -> TvLibraryFocusRegion.Chrome
                focusedItemIndex < columnCount -> TvLibraryFocusRegion.TopContent
                else -> TvLibraryFocusRegion.LowerContent
            },
        )
    }
    val focusNav =
        rememberTvGridFocusNav(
            gridState = gridState,
            itemCount = state.items.size,
            columnCount = columnCount,
            hasMore = state.hasMore && !state.isLoading && !state.isLoadingMore && !state.error,
            revealComposedTargets = constrainedByHero,
            onLoadMore = onLoadMore,
        )
    val requestPageRetryFocus: (Int) -> Boolean = { returnIndex ->
        pageRetryReturnIndex = returnIndex
        focusScope.launch {
            gridState.animateScrollToItem(state.items.size)
            requestTvFocusWithRetry(attempts = LIBRARY_GRID_EXIT_FOCUS_ATTEMPTS) {
                pageRetryRequester.requestFocusSafely()
            }
        }
        true
    }
    val gridBringIntoViewSpec =
        if (constrainedByHero) {
            rememberLibraryGridBringIntoViewSpec(
                metadataReserve = TvDimens.cardMetadataHeight,
                sameRowSlack = TvDimens.gridContentTopPadding,
            )
        } else {
            rememberRowAwareMarioBringIntoViewSpec()
        }
    val currentOnRequestGridFocusChanged = rememberUpdatedState(onRequestGridFocusChanged)
    val currentRequestGridFocus =
        rememberUpdatedState {
            requestLibraryGridFocus(
                state = state,
                pickerOpen = pickerOpen,
                gridState = gridState,
                focusNav = focusNav,
                // Resolved when this runs, not when the lambda is built: reading the
                // focus memory during composition would re-subscribe this scope to
                // every focus move, which is what the guard above removes.
                lastFocusedItemId =
                    coordinator.activeMemory
                        .childFor(gridFocusScope.scopeKey)
                        ?.removePrefix("item:")
                        ?: lastFocusedItemId,
            )
        }

    DisposableEffect(Unit) {
        currentOnRequestGridFocusChanged.value { currentRequestGridFocus.value() }
        onDispose { currentOnRequestGridFocusChanged.value(null) }
    }

    val restoreWaiting = coordinator.pendingRestore != null && coordinator.readyRestore == null
    // `lastFocusedItemId` dropped from the keys: it changes on every focus move, so
    // this restarted per D-pad press to run a body that is a no-op after the first
    // request. Read inside instead, where it is untracked and current.
    LaunchedEffect(requestInitialFocus, state.items, pickerOpen, restoreWaiting) {
        if (requestInitialFocus &&
            !initialFocusRequested.value &&
            !pickerOpen &&
            state.items.isNotEmpty() &&
            !restoreWaiting
        ) {
            val targetIndex =
                lastFocusedItemId
                    ?.let { id -> state.items.indexOfFirst { item -> item.id == id } }
                    ?.takeIf { index -> index >= 0 }
                    ?: gridState.firstVisibleItemIndex.coerceIn(0, state.items.lastIndex)
            withFrameNanos { }
            focusNav.requestFocus(targetIndex)
            initialFocusRequested.value = true
        }
    }

    val pendingRestore =
        coordinator.readyRestore
            ?.takeIf { restore -> restore.routeEntryId == coordinator.activeRouteEntryId }
            ?.takeIf { restore -> restore.path.scopes == listOf("library", "grid") }
    LaunchedEffect(pendingRestore?.token, pickerOpen, state.items, state.isLoading, state.hasMore, state.error) {
        val restore = pendingRestore ?: return@LaunchedEffect
        if (pickerOpen) {
            return@LaunchedEffect
        }
        val targetId = restore.path.targetKey.removePrefix("item:")
        val resolution =
            TvFocusResolver.resolveItem(
                targetKey = targetId,
                fallbackIndex = restore.path.fallbackIndex,
                itemKeys = state.items.map { item -> item.id },
                readiness =
                    if (state.isLoading) {
                        TvFocusContentReadiness.Loading
                    } else {
                        TvFocusContentReadiness.Ready
                    },
                hasMore = state.hasMore,
            )
        val targetIndex =
            when (resolution) {
                is TvFocusResolution.Exact -> resolution.index
                is TvFocusResolution.Fallback -> resolution.index
                TvFocusResolution.Deferred -> {
                    coordinator.updateRestoreStatus(TvFocusRestoreStatus.DeferredContent)
                    if (!state.isLoading && state.hasMore && !state.error) onLoadMore()
                    return@LaunchedEffect
                }
                TvFocusResolution.Unavailable -> return@LaunchedEffect
            }
        coordinator.updateRestoreStatus(TvFocusRestoreStatus.Resolving)
        focusNav.requestFocus(targetIndex)
    }

    LaunchedEffect(gridResetKey) {
        if (appliedGridResetKey != gridResetKey) {
            appliedGridResetKey = gridResetKey
            coordinator.clearScope(gridFocusScope.scopeKey)
            focusNav.resetFocus()
            gridState.scrollToItem(0)
        }
    }

    LoadMoreOnApproachEnd(
        itemCount = state.items.size,
        hasMore = state.hasMore,
        isLoading = state.isLoading,
        isLoadingMore = state.isLoadingMore,
        automaticPagingAllowed = !state.error,
        lastVisibleIndex = {
            gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { item -> item.index } ?: 0
        },
        threshold = LIBRARY_LOAD_MORE_THRESHOLD,
        debounceMs = LIBRARY_LOAD_MORE_DELAY_MS,
        onLoadMore = onLoadMore,
    )

    CompositionLocalProvider(LocalBringIntoViewSpec provides gridBringIntoViewSpec) {
        key(state.sortBy, state.sortOrder, state.filters) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TvDimens.posterWidth.tileScaled()),
                modifier =
                    modifier
                        .fillMaxWidth()
                        .focusProperties {
                            canFocus = !pickerOpen
                            // UP out of the top grid row returns to the selected
                            // inner-view tab group, never to geometric neighbors.
                            up = gridUpTarget
                        }.tvFocusScope(gridFocusScope) {
                            gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                                state.items.getOrNull(info.index)?.let { item -> "item:${item.id}" }
                            }
                        }.onFocusChanged { focusState -> gridHasFocus = focusState.hasFocus }
                        .onPreviewKeyEvent { event ->
                            if (
                                pageRetryHasFocus &&
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionUp
                            ) {
                                focusNav.requestFocus(pageRetryReturnIndex)
                                return@onPreviewKeyEvent true
                            }
                            val focusedIndex =
                                lastFocusedItemId
                                    ?.let { id -> state.items.indexOfFirst { item -> item.id == id } }
                                    ?.takeIf { index -> index >= 0 }
                            val lastRowStart =
                                ((state.items.lastIndex / columnCount.coerceAtLeast(1)) * columnCount.coerceAtLeast(1))
                            val retryDirection =
                                event.type == KeyEventType.KeyDown &&
                                    focusedIndex != null &&
                                    state.error &&
                                    (
                                        (event.key == Key.DirectionDown && focusedIndex >= lastRowStart) ||
                                            (event.key == Key.DirectionRight && focusedIndex == state.items.lastIndex)
                                    )
                            if (retryDirection) {
                                focusedIndex?.let(requestPageRetryFocus) == true
                            } else {
                                focusNav.onPreviewKeyEvent(event)
                            }
                        },
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                contentPadding =
                    PaddingValues(
                        // Reserve focus-scale room on all four edges so mario-centered
                        // cards clamped at the grid extents aren't clipped. The top
                        // needs extra room so the first row's focused (1.1x) card
                        // clears the Sort/Filter header above it.
                        start = contentStartPadding + TvDimens.gridContentHorizontalPadding,
                        top = TvDimens.libraryGridContentTopReserve,
                        end = TvDimens.overscanHorizontal + TvDimens.gridContentHorizontalPadding,
                        bottom = TvDimens.overscanVertical + TvDimens.gridContentTopPadding,
                    ),
            ) {
                itemsIndexed(
                    items = state.items,
                    key = { _, item -> item.id },
                    contentType = { _, item ->
                        if (item.kind == MediaCardKind.Library) "library-card" else "media-card"
                    },
                ) { index, item ->
                    val topRowItem = index < columnCount
                    val rightEdgeItem = (index + 1) % columnCount == 0 || index == state.items.lastIndex
                    val itemFocusRequester =
                        focusNav.rememberItemFocusRequester(
                            index = index,
                            entryFocusRequester = gridFocusRequester.takeIf { index == entryTarget },
                        )
                    val itemKey = "item:${item.id}"
                    DisposableEffect(gridFocusScope, itemKey, itemFocusRequester) {
                        gridFocusScope.register(itemKey, itemFocusRequester)
                        onDispose { gridFocusScope.unregister(itemKey, itemFocusRequester) }
                    }
                    TvMediaCard(
                        session = session,
                        item = item,
                        wide = item.kind == MediaCardKind.Library,
                        modifier =
                            Modifier.onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    false
                                } else {
                                    when {
                                        topRowItem && event.key == Key.DirectionUp -> requestGridUpFocus()
                                        rightEdgeItem && event.key == Key.DirectionRight -> true
                                        else -> false
                                    }
                                }
                            },
                        focusRequester = itemFocusRequester,
                        onFocused = {
                            focusNav.onItemFocused(index)
                            lastFocusedItemId = item.id
                            onFocusRegionChanged(
                                when {
                                    !gridLayoutReady ->
                                        TvLibraryFocusRegion.Chrome
                                    index < columnCount -> TvLibraryFocusRegion.TopContent
                                    else -> TvLibraryFocusRegion.LowerContent
                                },
                            )
                            val focusedPath = gridFocusScope.path(itemKey, TvFocusTargetKind.Item, index)
                            coordinator.recordFocused(focusedPath)
                            pendingRestore?.let { restore ->
                                if (
                                    restore.acceptsFocusedChild(
                                        focusedPath.scopes,
                                        itemKey,
                                        index,
                                        TvFocusTargetKind.Item,
                                    )
                                ) {
                                    coordinator.resolveRestore(restore.token, focusedPath)
                                }
                            }
                            onItemFocused(item)
                        },
                        onPosterLoaded = { bitmap -> onPosterLoaded(item.id, bitmap) },
                        onClick = { onItemSelected(item) },
                        onPlayDirect = { onItemPlayDirect(item) },
                    )
                }
                if (state.isLoadingMore) {
                    item(key = "loading-more", contentType = "loading-more") {
                        TvText(
                            text = stringResource(R.string.tv_loading_more),
                            color = LocalJellyfinPalette.current.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
                if (state.error) {
                    item(
                        key = "library:control:page-retry",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "page-retry",
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvText(
                                text = stringResource(R.string.tv_library_error),
                                color = LocalJellyfinPalette.current.error,
                            )
                            TvButton(
                                text = stringResource(R.string.tv_retry),
                                onClick = {
                                    focusNav.requestFocus(pageRetryReturnIndex)
                                    onRetry()
                                },
                                modifier =
                                    Modifier
                                        .width(TvDimens.retryButtonWidth)
                                        .focusRequester(pageRetryRequester)
                                        .onFocusChanged { focusState -> pageRetryHasFocus = focusState.isFocused }
                                        .onPreviewKeyEvent { event ->
                                            event.type == KeyEventType.KeyDown &&
                                                event.key == Key.DirectionUp &&
                                                run {
                                                    focusNav.requestFocus(pageRetryReturnIndex)
                                                    true
                                                }
                                        },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val LIBRARY_GRID_EXIT_FOCUS_ATTEMPTS = 8

@Composable
private fun TvLibraryError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText(
            text = stringResource(R.string.tv_library_error),
            color = LocalJellyfinPalette.current.error,
        )
        TvButton(
            text = stringResource(R.string.tv_retry),
            onClick = onRetry,
        )
    }
}

private fun libraryGridFocusResetKey(
    sortBy: LibrarySortBy,
    sortOrder: LibrarySortOrder,
    filters: LibraryFilterSelection,
): String =
    buildString {
        append(sortBy.name)
        append('|')
        append(sortOrder.name)
        append('|')
        append(filters.genres.joinToString(","))
        append('|')
        append(filters.genreIds.joinToString(","))
        append('|')
        append(filters.years.joinToString(","))
        append('|')
        append(filters.officialRatings.joinToString(","))
        append('|')
        append(filters.studioIds.joinToString(","))
        append('|')
        append(filters.tags.joinToString(","))
        append('|')
        append(filters.itemFilters.joinToString(",") { filter -> filter.name })
        append('|')
        append(filters.seriesStatus.joinToString(",") { status -> status.name })
        append('|')
        append(filters.hasSubtitles)
        append('|')
        append(filters.hasTrailer)
        append('|')
        append(filters.hasSpecialFeature)
    }

private fun requestLibraryGridFocus(
    state: LibraryBrowseUiState,
    pickerOpen: Boolean,
    gridState: LazyGridState,
    focusNav: TvGridFocusNav,
    lastFocusedItemId: String?,
): Boolean {
    if (pickerOpen || state.items.isEmpty()) {
        return false
    }

    val visibleIndices =
        gridState.layoutInfo.visibleItemsInfo
            .map { item -> item.index }
            .filter { index -> index in state.items.indices }
            .sorted()
    val lastFocusedVisibleIndex =
        lastFocusedItemId
            ?.let { id -> state.items.indexOfFirst { item -> item.id == id } }
            ?.takeIf { index -> index >= 0 && index in visibleIndices }

    val candidates = mutableListOf<Int>()
    lastFocusedVisibleIndex?.let { index -> candidates += index }
    candidates += visibleIndices
    if (visibleIndices.isEmpty()) {
        candidates += gridState.firstVisibleItemIndex.coerceIn(0, state.items.lastIndex)
    }
    candidates += 0

    return candidates
        .distinct()
        .any { index -> focusNav.requestFocusNow(index) }
}

private const val LIBRARY_LOAD_MORE_THRESHOLD = 12
private const val LIBRARY_LOAD_MORE_DELAY_MS = 180L
