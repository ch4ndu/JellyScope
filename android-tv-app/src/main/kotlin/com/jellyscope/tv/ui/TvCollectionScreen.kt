// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.TvUiPreferencesStore
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
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.LoadMoreOnApproachEnd
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.collection.CollectionUiState
import com.jellyscope.ui.screen.collection.CollectionViewModel
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.jellyscope.ui.component.AdaptiveCenteredSpinner as TvCenteredSpinner
import com.jellyscope.ui.component.DetailHeadlineStyle as TvHeadlineStyle
import com.jellyscope.ui.component.DetailText as TvText

@Composable
fun TvCollectionScreen(
    session: Session,
    collectionId: String,
    title: String?,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit,
    onFindSelected: () -> Unit,
    onFavoritesSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    gridHeroEligible: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: CollectionViewModel =
        koinViewModel(
            key = "collection-$collectionId",
            parameters = { parametersOf(session, collectionId) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences = koinInject<TvUiPreferencesStore>()
    val showGridHero by preferences.showLibraryGridHero.collectAsStateWithLifecycle()

    TvCollectionContent(
        session = session,
        title = title ?: stringResource(R.string.tv_collection_title),
        state = state,
        onBack = onBack,
        onHomeSelected = onHomeSelected,
        onFindSelected = onFindSelected,
        onFavoritesSelected = onFavoritesSelected,
        onSettingsSelected = onSettingsSelected,
        onItemSelected = onItemSelected,
        onItemPlayDirect = onItemPlayDirect,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        showGridHero = gridHeroEligible && showGridHero,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvCollectionContent(
    session: Session,
    title: String,
    state: CollectionUiState,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit,
    onFindSelected: () -> Unit,
    onFavoritesSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    showGridHero: Boolean,
    modifier: Modifier = Modifier,
) {
    var focusedItem by remember { mutableStateOf<MediaCardUi?>(null) }
    var heroItem by remember { mutableStateOf<MediaCardUi?>(null) }
    LaunchedEffect(state.items, focusedItem) {
        val target = focusedItem ?: state.items.firstOrNull() ?: return@LaunchedEffect
        if (heroItem != null && heroItem?.id != target.id) delay(HERO_SETTLE_MS)
        heroItem = target
    }
    val heroVisible = showGridHero && state.items.isNotEmpty()
    val ambientState =
        rememberTvHeroAmbientState(
            item = heroItem.takeIf { heroVisible },
            surface = TvHeroAmbientSurface.Collection,
            accountKey = "${session.serverId}|${session.userId}",
        )
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .tvHomeAmbientBackground(ambientState.presentation)
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.Back &&
                        run {
                            onBack()
                            true
                        }
                },
    ) {
        AmbientLayer(
            ambientColor = ambientState.presentation.color,
            animationOwnerKey = ambientState.presentation.animationOwnerKey,
            clearWhenColorMissing = ambientState.presentation.clearWhenColorMissing,
        )
        if (heroVisible) {
            TvHeroBackdrop(
                session = session,
                item = heroItem,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd),
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
        ) {
            TvCollectionHeader(
                title = title,
                state = state,
                modifier =
                    Modifier.padding(
                        start = TvDimens.overscanHorizontal,
                        top = TvDimens.overscanVertical,
                        end = TvDimens.overscanHorizontal,
                    ),
            )
            if (heroVisible) {
                TvLibraryGridHero(
                    session = session,
                    item = heroItem,
                    showBackdrop = false,
                    modifier = Modifier.padding(horizontal = TvDimens.overscanHorizontal),
                )
            }
            TvCollectionBody(
                session = session,
                state = state,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                onItemFocused = { item -> focusedItem = item },
                onPosterLoaded = ambientState.onPosterLoaded,
                modifier = Modifier.focusProperties { canFocus = !state.isLoading },
            )
        }
    }
}

@Composable
private fun TvCollectionHeader(
    title: String,
    state: CollectionUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TvDimens.detailShelfTitleGap),
    ) {
        TvText(
            text = title,
            style = TvHeadlineStyle,
            maxLines = 1,
        )
        TvText(
            text = stringResource(R.string.tv_grid_item_count, state.totalCount),
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvCollectionBody(
    session: Session,
    state: CollectionUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onItemFocused: (MediaCardUi) -> Unit,
    onPosterLoaded: (itemId: String, bitmap: Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading ->
            TvCenteredSpinner(modifier = modifier, drawBackground = false)
        state.error && state.items.isEmpty() ->
            TvCollectionError(
                onRetry = onRetry,
                modifier = Modifier.padding(horizontal = TvDimens.overscanHorizontal),
            )
        state.items.isEmpty() ->
            TvText(
                text = stringResource(R.string.tv_library_empty),
                modifier = Modifier.padding(horizontal = TvDimens.overscanHorizontal),
                color = LocalJellyfinPalette.current.textSecondary,
            )
        else -> {
            val coordinator = requireNotNull(LocalTvFocusCoordinator.current)
            val gridScope = rememberTvFocusScopeNode(listOf("collection", "grid"))
            val gridState = rememberLazyGridState()
            val columnCount = rememberStableGridColumnCount(gridState)
            // Remembered focus target so BACK from a detail page restores the exact
            // card, not the first one. Saveable survives the detail round-trip; the
            // engine reveals it if it scrolled off-screen.
            var lastFocusedItemId by rememberSaveable {
                mutableStateOf(
                    coordinator.activeMemory.childFor(gridScope.scopeKey)?.removePrefix("item:"),
                )
            }
            // Plain remember, NOT rememberSaveable: returning from detail recreates
            // this composition, and a saved flag would suppress the re-focus and
            // strand the remote. Re-fires on every composition entry.
            var initialFocusRequested by remember { mutableStateOf(false) }
            val focusNav =
                rememberTvGridFocusNav(
                    gridState = gridState,
                    itemCount = state.items.size,
                    columnCount = columnCount,
                    hasMore = state.hasMore && !state.isLoading && !state.isLoadingMore,
                    onLoadMore = onLoadMore,
                )
            val ownedPendingRestore =
                coordinator.pendingRestore
                    ?.takeIf { restore -> restore.routeEntryId == coordinator.activeRouteEntryId }
                    ?.takeIf { restore -> restore.path.scopes == gridScope.scopes }
            val pendingRestore =
                coordinator.readyRestore
                    ?.takeIf { restore -> restore.routeEntryId == coordinator.activeRouteEntryId }
                    ?.takeIf { restore -> restore.path.scopes == gridScope.scopes }
            val resolveTo: (Int) -> Unit = { index ->
                coordinator.updateRestoreStatus(TvFocusRestoreStatus.Resolving)
                focusNav.requestFocus(index)
            }
            LaunchedEffect(pendingRestore?.token, state.items, state.isLoadingMore, state.hasMore) {
                val restore = pendingRestore ?: return@LaunchedEffect
                when (
                    val resolution =
                        collectionRestoreResolution(
                            targetKey = restore.path.targetKey,
                            fallbackIndex = restore.path.fallbackIndex,
                            itemIds = state.items.map { item -> item.id },
                            hasMore = state.hasMore,
                        )
                ) {
                    is TvFocusResolution.Exact -> resolveTo(resolution.index)
                    is TvFocusResolution.Fallback -> resolveTo(resolution.index)
                    TvFocusResolution.Deferred -> {
                        coordinator.updateRestoreStatus(TvFocusRestoreStatus.DeferredContent)
                        if (!state.isLoadingMore && state.hasMore) onLoadMore()
                    }
                    TvFocusResolution.Unavailable -> Unit
                }
            }
            // `lastFocusedItemId` dropped from the keys: every card's `onFocused`
            // writes it, so this was cancelled and relaunched on every D-pad move to
            // run a body that is a no-op after the first focus request. Reading it in
            // the body is untracked and gives the same value.
            LaunchedEffect(state.items, ownedPendingRestore?.token) {
                if (!initialFocusRequested && state.items.isNotEmpty() && ownedPendingRestore == null) {
                    val targetIndex =
                        collectionFocusRestoreIndex(
                            items = state.items,
                            focusedItemId = lastFocusedItemId,
                            firstVisibleItemIndex = gridState.firstVisibleItemIndex,
                        )
                    withFrameNanos { }
                    focusNav.requestFocus(targetIndex)
                    initialFocusRequested = true
                }
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowAwareMarioBringIntoViewSpec()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(TvDimens.posterWidth.tileScaled()),
                    modifier =
                        modifier
                            .fillMaxSize()
                            .tvFocusScope(gridScope) {
                                gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                                    state.items.getOrNull(info.index)?.let { item -> "item:${item.id}" }
                                }
                            }.onPreviewKeyEvent(focusNav::onPreviewKeyEvent),
                    horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                    verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                    contentPadding =
                        PaddingValues(
                            // Focus-scale room on all edges for mario-centered cards.
                            start = TvDimens.overscanHorizontal + TvDimens.gridContentHorizontalPadding,
                            top = TvDimens.gridContentTopPadding,
                            end = TvDimens.overscanHorizontal + TvDimens.gridContentHorizontalPadding,
                            bottom = TvDimens.overscanVertical + TvDimens.gridContentTopPadding,
                        ),
                ) {
                    itemsIndexed(
                        items = state.items,
                        key = { _, item -> item.id },
                    ) { index, item ->
                        val itemFocusRequester =
                            focusNav.rememberItemFocusRequester(index = index)
                        val itemKey = "item:${item.id}"
                        DisposableEffect(gridScope, itemKey, itemFocusRequester) {
                            gridScope.register(itemKey, itemFocusRequester)
                            onDispose { gridScope.unregister(itemKey, itemFocusRequester) }
                        }
                        TvMediaCard(
                            session = session,
                            item = item,
                            wide = false,
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
                                onItemFocused(item)
                            },
                            onPosterLoaded = { bitmap -> onPosterLoaded(item.id, bitmap) },
                            onClick = { onItemSelected(item) },
                            onPlayDirect = { onItemPlayDirect(item) },
                        )
                    }
                    if (state.isLoadingMore) {
                        item(key = "loading-more") {
                            TvText(
                                text = stringResource(R.string.tv_loading_more),
                                color = LocalJellyfinPalette.current.textSecondary,
                            )
                        }
                    }
                }
                LoadMoreOnApproachEnd(
                    itemCount = state.items.size,
                    hasMore = state.hasMore,
                    isLoading = state.isLoading,
                    isLoadingMore = state.isLoadingMore,
                    lastVisibleIndex = {
                        gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { item -> item.index } ?: -1
                    },
                    threshold = COLLECTION_LOAD_MORE_THRESHOLD,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

internal fun collectionRestoreResolution(
    targetKey: String,
    fallbackIndex: Int,
    itemIds: List<String>,
    hasMore: Boolean,
): TvFocusResolution =
    TvFocusResolver.resolveItem(
        targetKey = targetKey.removePrefix("item:"),
        fallbackIndex = fallbackIndex,
        itemKeys = itemIds,
        readiness = TvFocusContentReadiness.Ready,
        hasMore = hasMore,
    )

internal fun collectionFocusRestoreIndex(
    items: List<MediaCardUi>,
    focusedItemId: String?,
    firstVisibleItemIndex: Int,
): Int {
    if (items.isEmpty()) {
        return 0
    }
    return focusedItemId
        ?.let { itemId -> items.indexOfFirst { item -> item.id == itemId } }
        ?.takeIf { index -> index >= 0 }
        ?: firstVisibleItemIndex.coerceIn(0, items.lastIndex)
}

@Composable
private fun TvCollectionError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
    ) {
        TvText(
            text = stringResource(R.string.tv_library_error),
            color = LocalJellyfinPalette.current.error,
        )
        TvButton(
            text = stringResource(R.string.tv_retry),
            onClick = onRetry,
            modifier = Modifier.width(TvDimens.retryButtonWidth),
        )
    }
}

private const val COLLECTION_LOAD_MORE_THRESHOLD = 12
