// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.LocalTvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.acceptsFocusedChild
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.CardImageAspect
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.rememberCardImageDecode
import com.jellyscope.ui.screen.discover.DiscoverFacetUi
import com.jellyscope.ui.screen.discover.DiscoverListState
import com.jellyscope.ui.screen.discover.DiscoverMediaItemsState
import com.jellyscope.ui.screen.discover.DiscoverSection
import com.jellyscope.ui.screen.discover.DiscoverUiState
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@Composable
internal fun TvDiscoverSectionBody(
    session: Session,
    state: DiscoverUiState,
    section: DiscoverSection,
    contentStartPadding: Dp,
    gridFocusRequester: FocusRequester,
    loadingFocusRequester: FocusRequester,
    onRequestRailFocus: () -> Boolean,
    onGenreSelected: (DiscoverFacetUi) -> Unit,
    onStudioSelected: (DiscoverFacetUi) -> Unit,
    onCollectionSelected: (MediaCardUi) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onRetryGenres: () -> Unit,
    onRetryStudios: () -> Unit,
    onRetryCollections: () -> Unit,
    onRetrySuggestions: () -> Unit,
    onRetryUpcoming: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (section) {
            DiscoverSection.Genres ->
                TvDiscoverFacetGrid(
                    session = session,
                    state = state.genres,
                    onFacetSelected = onGenreSelected,
                    onRetry = onRetryGenres,
                    gridFocusRequester = gridFocusRequester,
                    loadingFocusRequester = loadingFocusRequester,
                    onRequestRailFocus = onRequestRailFocus,
                    gridKey = "genres",
                    contentStartPadding = contentStartPadding,
                )
            DiscoverSection.Studios ->
                TvDiscoverFacetGrid(
                    session = session,
                    state = state.studios,
                    onFacetSelected = onStudioSelected,
                    onRetry = onRetryStudios,
                    gridFocusRequester = gridFocusRequester,
                    loadingFocusRequester = loadingFocusRequester,
                    onRequestRailFocus = onRequestRailFocus,
                    gridKey = "studios",
                    contentStartPadding = contentStartPadding,
                )
            DiscoverSection.Collections ->
                TvDiscoverMediaGrid(
                    session = session,
                    state = state.collectionMediaState,
                    onItemSelected = onCollectionSelected,
                    onItemPlayDirect = null,
                    onRetry = onRetryCollections,
                    gridFocusRequester = gridFocusRequester,
                    loadingFocusRequester = loadingFocusRequester,
                    onRequestRailFocus = onRequestRailFocus,
                    gridKey = "collections",
                    contentStartPadding = contentStartPadding,
                )
            DiscoverSection.Suggestions ->
                TvDiscoverMediaGrid(
                    session = session,
                    state = state.suggestionMediaState,
                    onItemSelected = onItemSelected,
                    onItemPlayDirect = onItemPlayDirect,
                    onRetry = onRetrySuggestions,
                    gridFocusRequester = gridFocusRequester,
                    loadingFocusRequester = loadingFocusRequester,
                    onRequestRailFocus = onRequestRailFocus,
                    gridKey = "suggestions",
                    contentStartPadding = contentStartPadding,
                )
            DiscoverSection.Upcoming ->
                TvDiscoverMediaGrid(
                    session = session,
                    state = state.upcomingMediaState,
                    onItemSelected = onItemSelected,
                    onItemPlayDirect = onItemPlayDirect,
                    onRetry = onRetryUpcoming,
                    gridFocusRequester = gridFocusRequester,
                    loadingFocusRequester = loadingFocusRequester,
                    onRequestRailFocus = onRequestRailFocus,
                    gridKey = "upcoming",
                    contentStartPadding = contentStartPadding,
                )
        }
    }
}

@Composable
private fun TvDiscoverFacetGrid(
    session: Session,
    state: DiscoverListState<DiscoverFacetUi>,
    onFacetSelected: (DiscoverFacetUi) -> Unit,
    onRetry: () -> Unit,
    gridFocusRequester: FocusRequester,
    loadingFocusRequester: FocusRequester,
    onRequestRailFocus: () -> Boolean,
    gridKey: String,
    contentStartPadding: Dp,
) {
    when (state) {
        DiscoverListState.Loading ->
            TvDiscoverLoading(
                loadingFocusRequester = loadingFocusRequester,
                onRequestRailFocus = onRequestRailFocus,
            )
        DiscoverListState.Empty -> TvDiscoverStatus(stringResource(R.string.tv_empty_row))
        is DiscoverListState.Error -> TvDiscoverErrorState(retryable = state.retryable, onRetry = onRetry)
        is DiscoverListState.Content ->
            if (state.items.isEmpty()) {
                TvDiscoverStatus(stringResource(R.string.tv_empty_row))
            } else {
                TvDiscoverGrid(
                    items = state.items,
                    cellMinWidth = TvDimens.discoverFacetWidth.tileScaled(),
                    gridFocusRequester = gridFocusRequester,
                    gridKey = gridKey,
                    itemKey = { facet -> facet.id ?: facet.name },
                    contentStartPadding = contentStartPadding,
                ) { facet, itemRequester, onFocused ->
                    TvDiscoverFacetCard(
                        session = session,
                        facet = facet,
                        onClick = { onFacetSelected(facet) },
                        focusRequester = itemRequester,
                        onFocused = onFocused,
                    )
                }
            }
    }
}

@Composable
private fun TvDiscoverMediaGrid(
    session: Session,
    state: DiscoverMediaItemsState,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: ((MediaCardUi) -> Unit)?,
    onRetry: () -> Unit,
    gridFocusRequester: FocusRequester,
    loadingFocusRequester: FocusRequester,
    onRequestRailFocus: () -> Boolean,
    gridKey: String,
    contentStartPadding: Dp,
) {
    when (state) {
        DiscoverMediaItemsState.Loading ->
            TvDiscoverLoading(
                loadingFocusRequester = loadingFocusRequester,
                onRequestRailFocus = onRequestRailFocus,
            )
        DiscoverMediaItemsState.Empty -> TvDiscoverStatus(stringResource(R.string.tv_empty_row))
        is DiscoverMediaItemsState.Error -> TvDiscoverErrorState(retryable = state.retryable, onRetry = onRetry)
        is DiscoverMediaItemsState.Content ->
            TvDiscoverGrid(
                items = state.items,
                cellMinWidth = TvDimens.posterWidth.tileScaled(),
                gridFocusRequester = gridFocusRequester,
                gridKey = gridKey,
                itemKey = { item -> item.id },
                contentStartPadding = contentStartPadding,
            ) { item, itemRequester, onFocused ->
                val onItemClick = remember(item, onItemSelected) { { onItemSelected(item) } }
                val onItemPlay = onItemPlayDirect?.let { callback -> remember(item, callback) { { callback(item) } } }
                TvMediaCard(
                    session = session,
                    item = item,
                    wide = false,
                    focusRequester = itemRequester,
                    onFocused = onFocused,
                    onClick = onItemClick,
                    onPlayDirect = onItemPlay,
                )
            }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun <T> TvDiscoverGrid(
    items: List<T>,
    cellMinWidth: Dp,
    gridFocusRequester: FocusRequester,
    gridKey: String,
    itemKey: (T) -> String,
    contentStartPadding: Dp,
    itemContent: @Composable (T, FocusRequester?, () -> Unit) -> Unit,
) {
    val coordinator = requireNotNull(LocalTvFocusCoordinator.current)
    val focusScope = rememberTvFocusScopeNode(listOf("discover", gridKey))
    CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowAwareMarioBringIntoViewSpec()) {
        key(gridKey) {
            val gridState = rememberLazyGridState()
            var lastFocusedItemKey by rememberSaveable {
                mutableStateOf(
                    coordinator.activeMemory.childFor(focusScope.scopeKey)?.removePrefix("item:"),
                )
            }
            var gridHasFocus by remember { mutableStateOf(false) }
            // Read only while focus is outside the grid: every card's `onFocused`
            // writes this, so reading it at grid scope invalidated the grid and
            // rescanned `items` on every D-pad move — to compute a target that is
            // only used on entry (`entryTarget` is -1 once the grid has focus).
            val entryTargetItemKey = if (gridHasFocus) null else lastFocusedItemKey
            val entryIndex by remember(items, entryTargetItemKey) {
                derivedStateOf {
                    val savedIndex =
                        entryTargetItemKey
                            ?.let { id -> items.indexOfFirst { item -> itemKey(item) == id } }
                            ?.takeIf { index -> index >= 0 }
                    val visibleSavedIndex =
                        savedIndex?.takeIf { target ->
                            gridState.layoutInfo.visibleItemsInfo.any { item -> item.index == target }
                        }
                    (visibleSavedIndex ?: gridState.firstVisibleItemIndex)
                        .coerceIn(0, (items.size - 1).coerceAtLeast(0))
                }
            }
            // When focus is NOT in the grid the requester follows the saved visible
            // item, or the first visible item when there is no saved target.
            val entryTarget = if (gridHasFocus) -1 else entryIndex
            val columnCount = rememberStableGridColumnCount(gridState)
            val focusNav =
                rememberTvGridFocusNav(
                    gridState = gridState,
                    itemCount = items.size,
                    columnCount = columnCount,
                )
            val pendingRestore =
                coordinator.readyRestore
                    ?.takeIf { restore -> restore.path.scopes == focusScope.scopes }
            LaunchedEffect(pendingRestore?.token, items) {
                val restore = pendingRestore ?: return@LaunchedEffect
                if (items.isEmpty()) return@LaunchedEffect
                val targetKey = restore.path.targetKey.removePrefix("item:")
                val targetIndex =
                    items
                        .indexOfFirst { item -> itemKey(item) == targetKey }
                        .takeIf { index -> index >= 0 }
                        ?: restore.path.fallbackIndex.coerceIn(0, items.lastIndex)
                focusNav.requestFocus(targetIndex)
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(cellMinWidth),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .tvFocusScope(focusScope) {
                            gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                                items.getOrNull(info.index)?.let { item -> "item:${itemKey(item)}" }
                            }
                        }.onFocusChanged { focusState -> gridHasFocus = focusState.hasFocus }
                        .onPreviewKeyEvent(focusNav::onPreviewKeyEvent),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                contentPadding =
                    PaddingValues(
                        // Focus-scale room on all edges for mario-centered cards.
                        start = contentStartPadding + TvDimens.gridContentHorizontalPadding,
                        top = TvDimens.gridContentTopPadding,
                        end = TvDimens.overscanHorizontal + TvDimens.gridContentHorizontalPadding,
                        bottom = TvDimens.overscanVertical + TvDimens.gridContentTopPadding,
                    ),
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> itemKey(item) },
                ) { index, item ->
                    val itemFocusRequester =
                        focusNav.rememberItemFocusRequester(
                            index = index,
                            entryFocusRequester = gridFocusRequester.takeIf { index == entryTarget },
                        )
                    val stableItemKey = "item:${itemKey(item)}"
                    DisposableEffect(focusScope, stableItemKey, itemFocusRequester) {
                        focusScope.register(stableItemKey, itemFocusRequester)
                        onDispose { focusScope.unregister(stableItemKey, itemFocusRequester) }
                    }
                    itemContent(item, itemFocusRequester) {
                        focusNav.onItemFocused(index)
                        lastFocusedItemKey = itemKey(item)
                        val path = focusScope.path(stableItemKey, TvFocusTargetKind.Item, index)
                        coordinator.recordFocused(path)
                        coordinator.readyRestore?.let { restore ->
                            if (
                                restore.acceptsFocusedChild(
                                    path.scopes,
                                    stableItemKey,
                                    index,
                                    TvFocusTargetKind.Item,
                                )
                            ) {
                                coordinator.resolveRestore(restore.token, path)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvDiscoverFacetCard(
    session: Session,
    facet: DiscoverFacetUi,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
) {
    val imageDecode =
        rememberCardImageDecode(
            width = TvDimens.discoverFacetWidth.tileScaled(),
            aspect = CardImageAspect.Wide,
        )
    Column(
        modifier = Modifier.width(TvDimens.discoverFacetWidth.tileScaled()),
        verticalArrangement = Arrangement.spacedBy(TvDimens.cardTitleGap),
    ) {
        TvFocusableBox(
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TvDimens.discoverFacetHeight.tileScaled())
                    .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            onFocused()
                        }
                    },
            contentDescription = stringResource(R.string.tv_open_discover_facet, facet.name),
            backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            focusedBackgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            contentAlignment = Alignment.Center,
        ) { _ ->
            val imageUrl = facet.imageUrl
            if (imageUrl != null) {
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session, imageDecode),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(LocalJellyfinPalette.current.navy.copy(alpha = 0.44f)),
                )
            }
            TvText(
                text = facet.name,
                style = TvDiscoverFacetTitleStyle,
                maxLines = 2,
                modifier = Modifier.padding(TvDimens.buttonHorizontalPadding),
            )
        }
        TvText(
            text = facet.name,
            style = TvCardLabelStyle,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvDiscoverLoading(
    loadingFocusRequester: FocusRequester,
    onRequestRailFocus: () -> Boolean,
) {
    TvLoadingFocusPark(
        focusRequester = loadingFocusRequester,
        onRequestRailFocus = onRequestRailFocus,
        requestFocusOnAttach = false,
    )
}

@Composable
private fun TvDiscoverStatus(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TvText(
            text = text,
            color = LocalJellyfinPalette.current.textSecondary,
        )
    }
}

@Composable
private fun TvDiscoverErrorState(
    retryable: Boolean,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvText(
                text = stringResource(R.string.tv_row_error),
                color = LocalJellyfinPalette.current.error,
            )
            if (retryable) {
                TvButton(
                    text = stringResource(R.string.tv_retry),
                    onClick = onRetry,
                    modifier = Modifier.width(TvDimens.retryButtonWidth),
                )
            }
        }
    }
}
