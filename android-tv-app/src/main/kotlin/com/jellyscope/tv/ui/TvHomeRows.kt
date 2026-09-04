// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.TvFocusScopeNode
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.rememberChildRequester
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.focus.rememberRestoreAwareBringIntoViewSpec
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.screen.home.RowState
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvHomeRow(
    row: TvHomeRowSpec,
    session: Session,
    parentFocusScope: TvFocusScopeNode,
    focusTarget: TvHomeFocusTarget?,
    focusFirstSlotTick: Int,
    loadingPlaceholderHadFocus: Boolean,
    contentStartPadding: Dp,
    horizontalBringIntoViewSpec: BringIntoViewSpec,
    onRowFocused: () -> Unit,
    onRetry: (HomeRow) -> Unit,
    onFocusedItem: (MediaCardUi) -> Unit,
    onLoadingPlaceholderFocused: () -> Unit,
    onLoadingPlaceholderReplaced: () -> Unit,
    onRetryFocused: () -> Unit,
    onViewAllFocused: () -> Unit,
    onPosterLoaded: (String, Bitmap) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onViewAllSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowKey = "row:${row.row.name}"
    val rowFocusScope = rememberTvFocusScopeNode(listOf("home", rowKey))
    DisposableEffect(parentFocusScope, rowFocusScope, rowKey) {
        parentFocusScope.register(rowKey, rowFocusScope.entryRequester)
        onDispose { parentFocusScope.unregister(rowKey, rowFocusScope.entryRequester) }
    }
    Column(
        modifier =
            modifier
                .focusGroup()
                .onFocusChanged { state ->
                    if (state.hasFocus) {
                        onRowFocused()
                    }
                },
        verticalArrangement = Arrangement.spacedBy(TvDimens.cardTitleGap),
    ) {
        TvText(
            text = row.title,
            style = TvHomeRowTitleStyle,
            maxLines = 1,
            modifier = Modifier.padding(start = contentStartPadding),
        )
        when (val rowState = row.rowState) {
            RowState.Empty -> {
            }
            RowState.Error ->
                Row(
                    modifier = Modifier.padding(start = contentStartPadding),
                    horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val retryRequester = remember { FocusRequester() }
                    LaunchedEffect(focusTarget, focusFirstSlotTick, loadingPlaceholderHadFocus) {
                        if (focusTarget != null || focusFirstSlotTick > 0 || loadingPlaceholderHadFocus) {
                            if (loadingPlaceholderHadFocus) {
                                onLoadingPlaceholderReplaced()
                            }
                            withFrameNanos { }
                            retryRequester.requestFocusSafely()
                        }
                    }
                    TvText(
                        text = stringResource(R.string.tv_row_error),
                        color = LocalJellyfinPalette.current.error,
                    )
                    TvButton(
                        text = stringResource(R.string.tv_retry),
                        onClick = { onRetry(row.row) },
                        modifier =
                            Modifier
                                .width(TvDimens.retryButtonWidth)
                                .focusRequester(retryRequester)
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        onRetryFocused()
                                    }
                                },
                    )
                }
            RowState.Loading, is RowState.Content -> {
                // Bridge loading and content in one row so focus never orphans.
                val contentItems = (rowState as? RowState.Content)?.items ?: emptyList()
                val hasContent = contentItems.isNotEmpty()
                val isLoading = rowState is RowState.Loading
                val lazyRowState = rememberLazyListState()
                val firstCardKey = contentItems.firstOrNull()?.let { item -> "item:${item.id}" }
                val firstCardRequester =
                    firstCardKey?.let { key -> rowFocusScope.rememberChildRequester(key) }
                        ?: remember { FocusRequester() }
                val loadingRequester = rowFocusScope.rememberChildRequester("slot:loading")
                val viewAllRequester = rowFocusScope.rememberChildRequester("slot:view-all")

                fun focusTargetIndex(): Int? =
                    when (val target = focusTarget) {
                        is TvHomeFocusTarget.Item ->
                            contentItems.indexOfFirst { item -> item.id == target.itemId }.takeIf { index -> index >= 0 }
                        is TvHomeFocusTarget.ViewAll -> contentItems.size
                        is TvHomeFocusTarget.RowSlot -> null
                        null -> null
                    }

                // Keep a focused placeholder until the first card takes focus.
                val bridgeOnArrival = remember(hasContent) { hasContent && loadingPlaceholderHadFocus }
                var bridgeFinished by remember { mutableStateOf(false) }
                val showLoadingTile = isLoading || (bridgeOnArrival && !bridgeFinished)
                val restoreRequest = rowFocusScope.restoreRequest()
                val restoreAwareSpec =
                    rememberRestoreAwareBringIntoViewSpec(
                        horizontalBringIntoViewSpec,
                        rowFocusScope.restoreHandoffActive,
                    )
                LaunchedEffect(bridgeOnArrival) {
                    if (bridgeOnArrival && !bridgeFinished) {
                        withFrameNanos { }
                        if (focusTargetIndex() == null) {
                            firstCardRequester.requestFocusSafely()
                        }
                        withFrameNanos { }
                        bridgeFinished = true
                        onLoadingPlaceholderReplaced()
                    }
                }
                // BACK rewinds and focuses the first ribbon.
                LaunchedEffect(focusFirstSlotTick) {
                    if (focusFirstSlotTick > 0 && hasContent) {
                        lazyRowState.scrollToItem(0)
                        withFrameNanos { }
                        firstCardRequester.requestFocusSafely()
                    }
                }
                LaunchedEffect(restoreRequest, contentItems, showLoadingTile) {
                    val request = restoreRequest ?: return@LaunchedEffect
                    if (!hasContent) {
                        return@LaunchedEffect
                    }
                    val semanticKeys =
                        buildList {
                            contentItems.forEach { item -> add("item:${item.id}") }
                            add("slot:view-all")
                        }
                    rowFocusScope.restoreFocus(
                        request = request,
                        semanticKeys = semanticKeys,
                        lazySlotIndex = { semanticIndex -> homeLazySlotIndex(semanticIndex, showLoadingTile) },
                        revealCentered = { lazyIndex ->
                            lazyRowState.scrollToItem(lazyIndex)
                            val info = lazyRowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
                            if (info != null) {
                                val viewportCenter =
                                    (
                                        lazyRowState.layoutInfo.viewportStartOffset +
                                            lazyRowState.layoutInfo.viewportEndOffset
                                    ) / 2
                                lazyRowState.scrollToItem(lazyIndex, -(viewportCenter - info.size / 2))
                            }
                        },
                    )
                }
                // Preserve horizontal auto-scroll inside the ribbon.
                CompositionLocalProvider(LocalBringIntoViewSpec provides restoreAwareSpec) {
                    LazyRow(
                        // Restore each ribbon's last card, falling back to its first real card.
                        modifier =
                            if (hasContent) {
                                Modifier.tvFocusScope(rowFocusScope) {
                                    buildList {
                                        lazyRowState.layoutInfo.visibleItemsInfo.forEach { info ->
                                            contentItems.getOrNull(info.index)?.let { item -> add("item:${item.id}") }
                                        }
                                        firstCardKey?.let(::add)
                                    }
                                }
                            } else {
                                Modifier
                            },
                        state = lazyRowState,
                        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                        contentPadding =
                            PaddingValues(
                                start = contentStartPadding + TvDimens.ribbonStartPeek,
                                end = TvDimens.ribbonEndPeek + TvDimens.overscanHorizontal,
                                top = TvDimens.ribbonContentTopPadding,
                                bottom = TvDimens.ribbonContentBottomPadding,
                            ),
                    ) {
                        if (showLoadingTile) {
                            item(
                                key = "loading-slot",
                                contentType = if (row.wide) "loading-wide" else "loading-poster",
                            ) {
                                TvLoadingPlaceholderCard(
                                    wide = row.wide,
                                    // During the bridge, the first card receives focus.
                                    restoreFocus = focusTarget != null && !hasContent,
                                    focusRequestTick = if (hasContent) 0 else focusFirstSlotTick,
                                    focusRequester = loadingRequester,
                                    onFocused = onLoadingPlaceholderFocused,
                                )
                            }
                        }
                        // Capture the indexed position outside focus callbacks.
                        itemsIndexed(
                            items = contentItems,
                            key = { _, item -> item.id },
                            contentType = { _, _ -> if (row.wide) "media-card-wide" else "media-card-poster" },
                        ) { index, item ->
                            val itemKey = "item:${item.id}"
                            val itemRequester =
                                if (itemKey == firstCardKey) {
                                    firstCardRequester
                                } else {
                                    rowFocusScope.rememberChildRequester(itemKey)
                                }
                            val onItemClick = remember(item.id, onItemSelected) { { onItemSelected(item) } }
                            val onItemPlay = remember(item.id, onItemPlayDirect) { { onItemPlayDirect(item) } }
                            val onItemPosterLoad =
                                remember(item.id, onPosterLoaded) {
                                    { bitmap: Bitmap ->
                                        onPosterLoaded(item.id, bitmap)
                                    }
                                }
                            TvMediaCard(
                                session = session,
                                item = item,
                                wide = row.wide,
                                focusRequester = itemRequester,
                                onFocused = {
                                    rowFocusScope.onChildFocused(itemKey, TvFocusTargetKind.Item, index)
                                    onFocusedItem(item)
                                },
                                onPosterLoaded = onItemPosterLoad,
                                onClick = onItemClick,
                                onPlayDirect = onItemPlay,
                            )
                        }
                        if (hasContent) {
                            item(
                                key = "view-all",
                                contentType = if (row.wide) "view-all-wide" else "view-all-poster",
                            ) {
                                TvViewAllCard(
                                    wide = row.wide,
                                    restoreFocus = focusTarget is TvHomeFocusTarget.ViewAll,
                                    focusRequester = viewAllRequester,
                                    onFocused = {
                                        rowFocusScope.onChildFocused(
                                            "slot:view-all",
                                            TvFocusTargetKind.ViewAll,
                                            contentItems.size,
                                        )
                                        onViewAllFocused()
                                    },
                                    onClick = onViewAllSelected,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvLoadingPlaceholderCard(
    wide: Boolean,
    restoreFocus: Boolean,
    focusRequestTick: Int,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.tv_loading)
    val requester = focusRequester
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            requester.requestFocusSafely()
        }
    }
    LaunchedEffect(focusRequestTick) {
        if (focusRequestTick > 0) {
            requester.requestFocusSafely()
        }
    }
    Column(
        modifier =
            modifier.width(
                (if (wide) TvDimens.libraryWidth else TvDimens.posterWidth).tileScaled(),
            ),
        verticalArrangement = Arrangement.spacedBy(TvDimens.cardTitleGap),
    ) {
        TvFocusableBox(
            onClick = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height((if (wide) TvDimens.libraryHeight else TvDimens.posterHeight).tileScaled())
                    .focusRequester(requester)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            onFocused()
                        }
                    },
            backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            focusedBackgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            contentDescription = label,
            contentAlignment = Alignment.Center,
        ) {
            TvSpinner(size = TvDimens.playerIconSize)
        }
        TvText(
            text = label,
            style = TvCardLabelStyle,
            maxLines = 1,
        )
    }
}

@Composable
private fun TvViewAllCard(
    wide: Boolean,
    restoreFocus: Boolean = false,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.tv_view_all)
    val requester = focusRequester
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            requester.requestFocusSafely()
        }
    }
    Column(
        modifier =
            modifier.width(
                (if (wide) TvDimens.libraryWidth else TvDimens.posterWidth).tileScaled(),
            ),
        verticalArrangement = Arrangement.spacedBy(TvDimens.cardTitleGap),
    ) {
        TvFocusableBox(
            onClick = onClick,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height((if (wide) TvDimens.libraryHeight else TvDimens.posterHeight).tileScaled())
                    .focusRequester(requester)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            onFocused()
                        }
                    },
            backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            focusedBackgroundColor = LocalJellyfinPalette.current.surfaceRaised,
            contentDescription = label,
            contentAlignment = Alignment.Center,
        ) { focused ->
            Icon(
                imageVector = TvIcons.ViewGrid,
                contentDescription = null,
                tint =
                    if (focused) {
                        LocalJellyfinPalette.current.textPrimary
                    } else {
                        LocalJellyfinPalette.current.textSecondary
                    },
                modifier = Modifier.size(TvDimens.viewAllIconSize.tileScaled()),
            )
        }
        TvText(
            text = label,
            style = TvCardLabelStyle,
            maxLines = 1,
        )
    }
}

@Immutable
internal data class TvHomeRowSpec(
    val row: HomeRow,
    val title: String,
    val rowState: RowState,
    val wide: Boolean = false,
)

internal sealed interface TvHomeFocusTarget {
    val row: HomeRow

    data class Item(
        override val row: HomeRow,
        val itemId: String,
    ) : TvHomeFocusTarget

    data class ViewAll(
        override val row: HomeRow,
    ) : TvHomeFocusTarget

    data class RowSlot(
        override val row: HomeRow,
    ) : TvHomeFocusTarget
}

internal fun shouldExpireHomeRestore(
    target: TvHomeFocusTarget?,
    focused: TvHomeFocusTarget,
    restoreConsumed: Boolean,
): Boolean = target == null || (restoreConsumed && target != focused)

internal fun homeRestoreItemIndex(
    itemIds: List<String>,
    targetItemId: String?,
    preferredIndex: Int,
): Int? {
    if (itemIds.isEmpty()) {
        return null
    }
    val exactIndex = targetItemId?.let(itemIds::indexOf) ?: -1
    return exactIndex.takeIf { index -> index >= 0 }
        ?: preferredIndex.takeIf { index -> index in itemIds.indices }
        ?: 0
}

internal fun homeLazySlotIndex(
    semanticIndex: Int,
    hasLeadingLoadingPlaceholder: Boolean,
): Int = semanticIndex + if (hasLeadingLoadingPlaceholder) 1 else 0
