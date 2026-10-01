// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.jellyscope.ui.screen.detail.requestFocusSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun rememberTvGridFocusNav(
    gridState: LazyGridState,
    itemCount: Int,
    columnCount: Int,
    hasMore: Boolean = false,
    revealComposedTargets: Boolean = false,
    onLoadMore: () -> Unit = {},
): TvGridFocusNav {
    val scope = rememberCoroutineScope()
    val nav =
        remember(gridState) {
            TvGridFocusNav(
                gridState = gridState,
                scope = scope,
            )
        }
    nav.update(
        itemCount = itemCount,
        columnCount = columnCount.coerceAtLeast(1),
        hasMore = hasMore,
        revealComposedTargets = revealComposedTargets,
        onLoadMore = onLoadMore,
    )

    val totalItemsCount by remember {
        derivedStateOf { gridState.layoutInfo.totalItemsCount }
    }
    LaunchedEffect(nav.pendingFocusIndex, totalItemsCount) {
        nav.focusPendingIfReady()
    }
    DisposableEffect(nav) {
        onDispose { nav.cancelNavigation() }
    }

    return nav
}

internal class TvGridFocusNav(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    private val requestFocus: (FocusRequester) -> Boolean = { it.requestFocusSafely() },
) {
    private val focusRequesters = mutableMapOf<Int, FocusRequester>()
    private var focusedIndex by mutableIntStateOf(0)
    internal var pendingFocusIndex by mutableStateOf<Int?>(null)
        private set

    private var itemCount: Int = 0
    private var columnCount: Int = 1
    private var hasMore: Boolean = false
    private var revealComposedTargets: Boolean = false
    private var onLoadMore: () -> Unit = {}
    private var lastNavAtMs = 0L
    private var revealJob: Job? = null
    private var revealIndex: Int? = null
    private var navigationGeneration = 0L

    internal fun update(
        itemCount: Int,
        columnCount: Int,
        hasMore: Boolean,
        revealComposedTargets: Boolean,
        onLoadMore: () -> Unit,
    ) {
        if (itemCount < this.itemCount ||
            columnCount != this.columnCount ||
            revealComposedTargets != this.revealComposedTargets
        ) {
            cancelNavigation()
        }
        this.itemCount = itemCount
        this.columnCount = columnCount.coerceAtLeast(1)
        this.hasMore = hasMore
        this.revealComposedTargets = revealComposedTargets
        this.onLoadMore = onLoadMore
        if (itemCount <= 0) {
            focusedIndex = 0
            pendingFocusIndex = null
        } else {
            val lastItemIndex = itemCount - 1
            focusedIndex = focusedIndex.coerceIn(0, lastItemIndex)
            pendingFocusIndex = pendingFocusIndex?.takeIf { index -> index in 0..lastItemIndex }
        }
    }

    internal fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || itemCount <= 0) {
            return false
        }
        if (event.key != Key.DirectionDown && event.key != Key.DirectionUp) {
            cancelNavigation()
            return false
        }

        val direction =
            if (event.key == Key.DirectionDown) {
                TvGridDpadDirection.Down
            } else {
                TvGridDpadDirection.Up
            }
        val decision =
            TvGridDpadPolicy.decide(
                direction = direction,
                eventTimeMs = event.nativeKeyEvent.eventTime,
                lastAcceptedAtMs = lastNavAtMs,
                focusedIndex = pendingFocusIndex ?: focusedIndex,
                itemCount = itemCount,
                columnCount = columnCount,
                hasMore = hasMore,
            )
        decision.acceptedAtMs?.let { acceptedAt -> lastNavAtMs = acceptedAt }
        decision.targetIndex?.let(::focusTarget)
        if (!decision.consumed) cancelNavigation()
        if (decision.loadMore) onLoadMore()
        return decision.consumed
    }

    internal fun register(
        index: Int,
        requester: FocusRequester,
    ) {
        focusRequesters[index] = requester
        if (pendingFocusIndex == index) {
            scope.launch { focusPendingIfReady() }
        }
    }

    internal fun unregister(
        index: Int,
        requester: FocusRequester,
    ) {
        if (focusRequesters[index] === requester) {
            focusRequesters.remove(index)
        }
    }

    internal fun onItemFocused(index: Int) {
        if (pendingFocusIndex?.let { it != index } == true ||
            revealIndex?.let { it / columnCount != index / columnCount } == true
        ) {
            cancelNavigation()
        }
        focusedIndex = index
        if (pendingFocusIndex == index) {
            pendingFocusIndex = null
        }
    }

    internal fun requestFocus(index: Int) {
        if (itemCount <= 0) {
            return
        }
        focusTarget(index.coerceIn(0, itemCount - 1))
    }

    internal fun requestFocusNow(index: Int): Boolean {
        if (itemCount <= 0) {
            return false
        }
        val target = index.coerceIn(0, itemCount - 1)
        val requester = focusRequesters[target] ?: return false
        cancelNavigation()
        if (!requestFocus(requester)) {
            return false
        }
        focusedIndex = target
        pendingFocusIndex = null
        if (revealComposedTargets) {
            revealTowardsCenter(target)
        }
        return true
    }

    internal fun resetFocus() {
        cancelNavigation()
        focusedIndex = 0
        lastNavAtMs = 0L
    }

    internal fun cancelNavigation() {
        navigationGeneration += 1
        revealJob?.cancel()
        revealJob = null
        revealIndex = null
        pendingFocusIndex = null
    }

    internal suspend fun focusPendingIfReady() {
        val pending = pendingFocusIndex ?: return
        val generation = navigationGeneration
        if (focusRequesters[pending] == null) return
        // Delay one frame; withFrameNanos can stall on an idle frame clock.
        delay(PENDING_FOCUS_DELAY_MS)
        // User input wins over delayed focus restoration.
        if (pendingFocusIndex != pending || navigationGeneration != generation) return
        val requester = focusRequesters[pending] ?: return
        if (requestFocus(requester)) {
            focusedIndex = pending
            pendingFocusIndex = null
        }
    }

    private fun focusTarget(index: Int) {
        cancelNavigation()
        val requester = focusRequesters[index]
        if (requester != null && requestFocus(requester)) {
            // Advance the index only after focus actually moves.
            focusedIndex = index
            pendingFocusIndex = null
            if (revealComposedTargets) {
                revealTowardsCenter(index)
            }
        } else {
            // Keep current focus until the pending target is composed.
            pendingFocusIndex = index
            revealTowardsCenter(index)
        }
    }

    private fun revealTowardsCenter(index: Int) {
        val generation = navigationGeneration
        revealIndex = index
        revealJob =
            scope.launch {
                try {
                    val info = gridState.layoutInfo
                    val itemHeight =
                        (info.visibleItemsInfo.firstOrNull { it.index == index } ?: info.visibleItemsInfo.firstOrNull())
                            ?.size
                            ?.height ?: 0
                    // Indexed offsets use the content origin; hero grids center the
                    // entire item (including labels) in the physical viewport.
                    val origin = if (revealComposedTargets) info.beforeContentPadding else 0
                    val centerOffset = origin - ((info.viewportSize.height - itemHeight) / 2).coerceAtLeast(0)
                    gridState.animateScrollToItem(index, centerOffset)
                    focusPendingIfReady()
                } finally {
                    if (navigationGeneration == generation) {
                        revealIndex = null
                        revealJob = null
                    }
                }
            }
    }
}

@Composable
internal fun TvGridFocusNav.rememberItemFocusRequester(
    index: Int,
    entryFocusRequester: FocusRequester? = null,
): FocusRequester {
    val registryRequester = remember { FocusRequester() }
    val requester = entryFocusRequester ?: registryRequester
    DisposableEffect(index, requester) {
        register(index, requester)
        onDispose { unregister(index, requester) }
    }
    return requester
}

/** Uses the configured grid span as the stable column count once items are visible. */
@Composable
internal fun rememberStableGridColumnCount(gridState: LazyGridState): Int {
    val columns by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty()) {
                1
            } else {
                layoutInfo.maxSpan.coerceAtLeast(1)
            }
        }
    }
    return columns
}

// One frame at 60 fps.
private const val PENDING_FOCUS_DELAY_MS = 16L
