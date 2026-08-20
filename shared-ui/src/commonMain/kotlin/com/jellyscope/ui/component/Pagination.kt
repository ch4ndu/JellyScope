// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Distance (in items) from the end of a paged list at which the next page is requested. */
const val DEFAULT_LOAD_MORE_THRESHOLD = 8

/**
 * Requests the next page once the last visible item comes within [threshold] items of the end of a
 * paged list. Shared by the mobile library/collection grids and the TV library grid.
 *
 * The effect is started once and never restarted, so `distinctUntilChanged` retains its memory for
 * the composable's lifetime. Every caller must therefore publish an observable [isLoadingMore]
 * transition for each page load, so the trigger passes through `false` before the next `true` edge.
 * A caller that loads without publishing [isLoadingMore] can stall its own pagination.
 *
 * @param lastVisibleIndex reads the largest currently-visible item index from the host lazy state;
 *   kept as a caller lambda so each surface tracks its own grid/list state.
 * @param debounceMs optional delay before firing so fast scrolls coalesce into one request
 *   (the TV grid uses this to soften rapid D-pad paging); 0 fires eagerly.
 */
@Composable
fun LoadMoreOnApproachEnd(
    itemCount: Int,
    hasMore: Boolean,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    lastVisibleIndex: () -> Int,
    threshold: Int = DEFAULT_LOAD_MORE_THRESHOLD,
    debounceMs: Long = 0L,
    onLoadMore: () -> Unit,
) {
    val currentItemCount by rememberUpdatedState(itemCount)
    val currentHasMore by rememberUpdatedState(hasMore)
    val currentIsLoading by rememberUpdatedState(isLoading)
    val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)
    val currentLastVisibleIndex by rememberUpdatedState(lastVisibleIndex)
    val currentThreshold by rememberUpdatedState(threshold)
    val currentDebounceMs by rememberUpdatedState(debounceMs)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)

    val shouldRequestNextPage by remember {
        derivedStateOf {
            shouldLoadMore(
                itemCount = currentItemCount,
                hasMore = currentHasMore,
                isLoading = currentIsLoading,
                isLoadingMore = currentIsLoadingMore,
                lastVisibleIndex = currentLastVisibleIndex(),
                threshold = currentThreshold,
            )
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { shouldRequestNextPage }
            .distinctUntilChanged()
            .collectLatest { shouldRequest ->
                if (shouldRequest) {
                    if (currentDebounceMs > 0L) {
                        delay(currentDebounceMs)
                    }
                    currentOnLoadMore()
                }
            }
    }
}

internal fun shouldLoadMore(
    itemCount: Int,
    hasMore: Boolean,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    lastVisibleIndex: Int,
    threshold: Int,
): Boolean =
    hasMore &&
        !isLoading &&
        !isLoadingMore &&
        itemCount > 0 &&
        lastVisibleIndex >= itemCount - threshold
