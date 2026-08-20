// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.jellyscope.ui.theme.Dimensions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class LibraryScrollbarMetrics(
    val totalItems: Int,
    val firstVisibleIndex: Int,
    val lastVisibleIndex: Int,
    val visibleFraction: Float,
    val scrollableRange: Int,
    val normalizedLeadingPosition: Float,
    val hasMoreContent: Boolean,
    val itemsPerRow: Int,
    val totalRows: Int,
    val firstVisibleRow: Int,
    val firstVisibleRowScrollFraction: Float,
    val visibleRows: Int,
)

internal fun libraryScrollbarMetrics(
    totalItems: Int,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    itemsPerRow: Int = 1,
    visibleExtentInRows: Float? = null,
    firstVisibleRowScrollFraction: Float = 0f,
    canScrollBackward: Boolean? = null,
    canScrollForward: Boolean? = null,
): LibraryScrollbarMetrics {
    val safeTotalItems = totalItems.coerceAtLeast(0)
    val safeItemsPerRow = itemsPerRow.coerceAtLeast(1)
    if (safeTotalItems == 0 || firstVisibleIndex > lastVisibleIndex || lastVisibleIndex < 0) {
        return LibraryScrollbarMetrics(
            totalItems = safeTotalItems,
            firstVisibleIndex = 0,
            lastVisibleIndex = -1,
            visibleFraction = 0f,
            scrollableRange = 0,
            normalizedLeadingPosition = 0f,
            hasMoreContent = false,
            itemsPerRow = safeItemsPerRow,
            totalRows = 0,
            firstVisibleRow = 0,
            firstVisibleRowScrollFraction = 0f,
            visibleRows = 0,
        )
    }

    val firstIndex = firstVisibleIndex.coerceIn(0, safeTotalItems - 1)
    val lastIndex = lastVisibleIndex.coerceIn(firstIndex, safeTotalItems - 1)
    val totalRows = (safeTotalItems + safeItemsPerRow - 1) / safeItemsPerRow
    val firstVisibleRow = firstIndex / safeItemsPerRow
    val lastVisibleRow = lastIndex / safeItemsPerRow
    val visibleRows =
        (lastVisibleRow - firstVisibleRow + 1).coerceIn(1, totalRows)
    val safeVisibleExtent =
        visibleExtentInRows
            ?.takeIf { extent -> extent.isFinite() }
            ?.coerceIn(0f, totalRows.toFloat())
            ?: visibleRows.toFloat()
    val safeFirstVisibleRowScrollFraction =
        firstVisibleRowScrollFraction
            .takeIf { fraction -> fraction.isFinite() }
            ?.coerceIn(0f, 1f)
            ?: 0f
    val scrollableRange =
        maxOf(
            (totalRows - visibleRows).coerceAtLeast(0),
            ceil(totalRows - safeVisibleExtent).toInt().coerceAtLeast(0),
        )
    val runtimeOverflow =
        if (canScrollBackward != null || canScrollForward != null) {
            canScrollBackward == true || canScrollForward == true
        } else {
            null
        }
    val hasMoreContent = runtimeOverflow ?: (scrollableRange > 0)
    val effectiveScrollableRange =
        if (hasMoreContent) {
            scrollableRange.coerceAtLeast(1)
        } else {
            0
        }
    val normalizedLeadingPosition =
        when {
            canScrollBackward == false -> 0f
            canScrollForward == false -> 1f
            effectiveScrollableRange == 0 -> 0f
            else ->
                ((firstVisibleRow + safeFirstVisibleRowScrollFraction) / effectiveScrollableRange)
                    .coerceIn(0f, 1f)
        }

    return LibraryScrollbarMetrics(
        totalItems = safeTotalItems,
        firstVisibleIndex = firstIndex,
        lastVisibleIndex = lastIndex,
        visibleFraction = (safeVisibleExtent / totalRows).coerceIn(0f, 1f),
        scrollableRange = effectiveScrollableRange,
        normalizedLeadingPosition = normalizedLeadingPosition,
        hasMoreContent = hasMoreContent,
        itemsPerRow = safeItemsPerRow,
        totalRows = totalRows,
        firstVisibleRow = firstVisibleRow,
        firstVisibleRowScrollFraction = safeFirstVisibleRowScrollFraction,
        visibleRows = visibleRows,
    )
}

internal fun libraryScrollbarTargetIndex(
    metrics: LibraryScrollbarMetrics,
    normalizedPosition: Float,
): Int {
    if (metrics.totalItems == 0 || metrics.scrollableRange == 0) {
        return 0
    }

    val safePosition =
        if (normalizedPosition.isFinite()) {
            normalizedPosition.coerceIn(0f, 1f)
        } else {
            0f
        }
    val targetRow =
        (safePosition * metrics.scrollableRange)
            .roundToInt()
            .coerceIn(0, metrics.scrollableRange)
    return (targetRow * metrics.itemsPerRow).coerceIn(0, metrics.totalItems - 1)
}

internal fun libraryScrollbarThumbLength(
    trackLengthPx: Float,
    fixedThumbLengthPx: Float,
): Float {
    if (!trackLengthPx.isFinite() || trackLengthPx <= 0f || !fixedThumbLengthPx.isFinite()) {
        return 0f
    }
    return fixedThumbLengthPx.coerceIn(0f, trackLengthPx)
}

internal fun libraryScrollbarThumbOffset(
    trackLengthPx: Float,
    thumbLengthPx: Float,
    normalizedPosition: Float,
): Float {
    if (!trackLengthPx.isFinite() || !thumbLengthPx.isFinite() || trackLengthPx <= 0f) {
        return 0f
    }

    val availableLength = (trackLengthPx - thumbLengthPx).coerceAtLeast(0f)
    val safePosition =
        if (normalizedPosition.isFinite()) {
            normalizedPosition.coerceIn(0f, 1f)
        } else {
            0f
        }
    return (availableLength * safePosition).coerceIn(0f, availableLength)
}

internal fun libraryScrollbarPositionForThumbTop(
    thumbTopPx: Float,
    trackLengthPx: Float,
    thumbLengthPx: Float,
): Float {
    val availableLength = (trackLengthPx - thumbLengthPx).coerceAtLeast(0f)
    if (!availableLength.isFinite() || availableLength <= 0f || !thumbTopPx.isFinite()) {
        return 0f
    }
    return (thumbTopPx / availableLength).coerceIn(0f, 1f)
}

internal fun libraryScrollbarScrollOffset(
    maxScrollOffsetPx: Int,
    normalizedPosition: Float,
): Int {
    if (maxScrollOffsetPx <= 0 || !normalizedPosition.isFinite()) {
        return 0
    }
    return (maxScrollOffsetPx * normalizedPosition.coerceIn(0f, 1f))
        .roundToInt()
        .coerceIn(0, maxScrollOffsetPx)
}

@Composable
internal fun LibraryGridScrollbar(
    state: LazyGridState,
    topPadding: Dp,
    bottomPadding: Dp,
    endPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val metricsState =
        remember(state) {
            derivedStateOf {
                state.layoutInfo.libraryScrollbarMetrics(
                    canScrollBackward = state.canScrollBackward,
                    canScrollForward = state.canScrollForward,
                )
            }
        }
    val hasMoreContent by remember(metricsState) {
        derivedStateOf { metricsState.value.hasMoreContent }
    }
    if (!hasMoreContent) {
        return
    }
    val metricsProvider = remember(metricsState) { { metricsState.value } }

    LibraryScrollbar(
        metricsProvider = metricsProvider,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        endPadding = endPadding,
        targetIndex = { normalizedPosition ->
            libraryScrollbarTargetIndex(metricsProvider(), normalizedPosition)
        },
        scrollToPosition = { normalizedPosition, index ->
            val metrics = metricsProvider()
            val scrollOffset =
                if (metrics.totalRows == 1) {
                    libraryScrollbarScrollOffset(
                        maxScrollOffsetPx = state.layoutInfo.singleRowMaxScrollOffset(),
                        normalizedPosition = normalizedPosition,
                    )
                } else {
                    0
                }
            state.scrollToItem(index, scrollOffset)
        },
        modifier = modifier,
    )
}

@Composable
internal fun LibraryListScrollbar(
    state: LazyListState,
    topPadding: Dp,
    bottomPadding: Dp,
    endPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val metricsState =
        remember(state) {
            derivedStateOf {
                state.layoutInfo.libraryScrollbarMetrics(
                    canScrollBackward = state.canScrollBackward,
                    canScrollForward = state.canScrollForward,
                )
            }
        }
    val hasMoreContent by remember(metricsState) {
        derivedStateOf { metricsState.value.hasMoreContent }
    }
    if (!hasMoreContent) {
        return
    }
    val metricsProvider = remember(metricsState) { { metricsState.value } }

    LibraryScrollbar(
        metricsProvider = metricsProvider,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        endPadding = endPadding,
        targetIndex = { normalizedPosition ->
            libraryScrollbarTargetIndex(metricsProvider(), normalizedPosition)
        },
        scrollToPosition = { normalizedPosition, index ->
            val metrics = metricsProvider()
            val scrollOffset =
                if (metrics.totalRows == 1) {
                    libraryScrollbarScrollOffset(
                        maxScrollOffsetPx = state.layoutInfo.singleRowMaxScrollOffset(),
                        normalizedPosition = normalizedPosition,
                    )
                } else {
                    0
                }
            state.scrollToItem(index, scrollOffset)
        },
        modifier = modifier,
    )
}

@Composable
private fun LibraryScrollbar(
    metricsProvider: () -> LibraryScrollbarMetrics,
    topPadding: Dp,
    bottomPadding: Dp,
    endPadding: Dp,
    targetIndex: (Float) -> Int,
    scrollToPosition: suspend (Float, Int) -> Unit,
    modifier: Modifier,
) {
    var requestedTarget by remember { mutableStateOf<Float?>(null) }
    val currentTargetIndex = rememberUpdatedState(targetIndex)
    val currentScrollToPosition = rememberUpdatedState(scrollToPosition)

    LaunchedEffect(Unit) {
        snapshotFlow { requestedTarget }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { normalizedPosition ->
                currentScrollToPosition.value(
                    normalizedPosition,
                    currentTargetIndex.value(normalizedPosition),
                )
            }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val trackLengthPxState = remember { mutableStateOf(IntSize.Zero) }
    val trackLengthPx = trackLengthPxState.value.height.toFloat()
    val thumbLengthPx =
        libraryScrollbarThumbLength(
            trackLengthPx = trackLengthPx,
            fixedThumbLengthPx =
                with(density) {
                    Dimensions.libraryScrollbarThumbHeight.toPx()
                },
        )
    val currentMetricsProvider = rememberUpdatedState(metricsProvider)
    val currentGeometryProvider =
        rememberUpdatedState<() -> LibraryScrollbarGeometry>(
            newValue = {
                val metrics = currentMetricsProvider.value()
                LibraryScrollbarGeometry(
                    trackLengthPx = trackLengthPx,
                    thumbLengthPx = thumbLengthPx,
                    thumbOffsetPx =
                        libraryScrollbarThumbOffset(
                            trackLengthPx = trackLengthPx,
                            thumbLengthPx = thumbLengthPx,
                            normalizedPosition = metrics.normalizedLeadingPosition,
                        ),
                )
            },
        )
    val thumbLength = with(density) { thumbLengthPx.toDp() }
    val scrollbarShape = RoundedCornerShape(percent = 50)

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .width(Dimensions.libraryScrollbarInteractionWidth + endPadding)
                .padding(
                    top = topPadding,
                    end = endPadding,
                    bottom = bottomPadding,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { size -> trackLengthPxState.value = size }
                    .semantics {
                        val metrics = currentMetricsProvider.value()
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(
                                current = metrics.normalizedLeadingPosition,
                                range = 0f..1f,
                                steps = 0,
                            )
                        setProgress { normalizedPosition ->
                            requestedTarget =
                                if (normalizedPosition.isFinite()) {
                                    normalizedPosition.coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            true
                        }
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val geometry = currentGeometryProvider.value()
                            if (geometry.trackLengthPx <= 0f || geometry.thumbLengthPx <= 0f) {
                                return@awaitEachGesture
                            }

                            val downPosition =
                                down.position.y.coerceIn(0f, geometry.trackLengthPx)
                            val thumbBottom = geometry.thumbOffsetPx + geometry.thumbLengthPx
                            val dragOffset =
                                if (downPosition in geometry.thumbOffsetPx..thumbBottom) {
                                    downPosition - geometry.thumbOffsetPx
                                } else {
                                    geometry.thumbLengthPx / 2f
                                }
                            requestedTarget =
                                libraryScrollbarPositionForThumbTop(
                                    thumbTopPx = downPosition - dragOffset,
                                    trackLengthPx = geometry.trackLengthPx,
                                    thumbLengthPx = geometry.thumbLengthPx,
                                )
                            down.consume()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { pointer -> pointer.id == down.id }
                                        ?: break
                                if (!change.pressed) {
                                    break
                                }
                                requestedTarget =
                                    libraryScrollbarPositionForThumbTop(
                                        thumbTopPx = change.position.y - dragOffset,
                                        trackLengthPx = geometry.trackLengthPx,
                                        thumbLengthPx = geometry.thumbLengthPx,
                                    )
                                change.consume()
                            }
                            requestedTarget = null
                        }
                    },
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .width(Dimensions.libraryScrollbarThumbWidth)
                        .height(thumbLength)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = currentGeometryProvider.value().thumbOffsetPx.roundToInt(),
                            )
                        }.background(
                            color =
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = Dimensions.libraryScrollbarThumbAlpha,
                                ),
                            shape = scrollbarShape,
                        ),
            )
        }
    }
}

private data class LibraryScrollbarGeometry(
    val trackLengthPx: Float,
    val thumbLengthPx: Float,
    val thumbOffsetPx: Float,
)

private fun LazyListLayoutInfo.libraryScrollbarMetrics(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): LibraryScrollbarMetrics {
    val visibleItems = visibleItemsInfo
    val leadingItem = visibleItems.minByOrNull { item -> item.index }
    return libraryScrollbarMetrics(
        totalItems = totalItemsCount,
        firstVisibleIndex = visibleItems.minOfOrNull { item -> item.index } ?: -1,
        lastVisibleIndex = visibleItems.maxOfOrNull { item -> item.index } ?: -1,
        visibleExtentInRows =
            visibleItems.fold(0f) { extent, item ->
                extent +
                    visibleFraction(
                        start = item.offset,
                        end = item.offset + item.size,
                    )
            },
        firstVisibleRowScrollFraction =
            leadingItem?.let { item ->
                scrollFractionPastViewportStart(
                    start = item.offset,
                    end = item.offset + item.size,
                )
            } ?: 0f,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
    )
}

private fun LazyGridLayoutInfo.libraryScrollbarMetrics(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): LibraryScrollbarMetrics {
    val visibleItems = visibleItemsInfo
    val visibleRows = visibleItems.groupBy { item -> item.offset.y }
    val leadingRow = visibleRows.minByOrNull { (rowStart, _) -> rowStart }?.value
    return libraryScrollbarMetrics(
        totalItems = totalItemsCount,
        firstVisibleIndex = visibleItems.minOfOrNull { item -> item.index } ?: -1,
        lastVisibleIndex = visibleItems.maxOfOrNull { item -> item.index } ?: -1,
        itemsPerRow = observedGridColumnCount(visibleItems),
        visibleExtentInRows =
            visibleRows.values
                .fold(0f) { extent, row ->
                    val rowStart = row.minOf { item -> item.offset.y }
                    val rowEnd = row.maxOf { item -> item.offset.y + item.size.height }
                    extent + visibleFraction(start = rowStart, end = rowEnd)
                },
        firstVisibleRowScrollFraction =
            leadingRow?.let { row ->
                scrollFractionPastViewportStart(
                    start = row.minOf { item -> item.offset.y },
                    end = row.maxOf { item -> item.offset.y + item.size.height },
                )
            } ?: 0f,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward,
    )
}

private fun LazyListLayoutInfo.singleRowMaxScrollOffset(): Int {
    val itemSize = visibleItemsInfo.maxOfOrNull { item -> item.size } ?: 0
    val viewportExtent = viewportSize.height.coerceAtLeast(0)
    return (beforeContentPadding + itemSize + afterContentPadding - viewportExtent)
        .coerceAtLeast(0)
}

private fun LazyGridLayoutInfo.singleRowMaxScrollOffset(): Int {
    val rowHeight = visibleItemsInfo.maxOfOrNull { item -> item.size.height } ?: 0
    val viewportExtent = viewportSize.height.coerceAtLeast(0)
    return (beforeContentPadding + rowHeight + afterContentPadding - viewportExtent)
        .coerceAtLeast(0)
}

private fun LazyListLayoutInfo.visibleFraction(
    start: Int,
    end: Int,
): Float {
    val size = end - start
    if (size <= 0) return 0f
    val visibleStart = maxOf(start, viewportStartOffset)
    val visibleEnd = minOf(end, viewportEndOffset)
    return ((visibleEnd - visibleStart).coerceAtLeast(0).toFloat() / size).coerceIn(0f, 1f)
}

private fun LazyListLayoutInfo.scrollFractionPastViewportStart(
    start: Int,
    end: Int,
): Float {
    val size = end - start
    if (size <= 0) return 0f
    return ((viewportStartOffset - start).coerceAtLeast(0).toFloat() / size).coerceIn(0f, 1f)
}

private fun LazyGridLayoutInfo.visibleFraction(
    start: Int,
    end: Int,
): Float {
    val size = end - start
    if (size <= 0) return 0f
    val visibleStart = maxOf(start, viewportStartOffset)
    val visibleEnd = minOf(end, viewportEndOffset)
    return ((visibleEnd - visibleStart).coerceAtLeast(0).toFloat() / size).coerceIn(0f, 1f)
}

private fun LazyGridLayoutInfo.scrollFractionPastViewportStart(
    start: Int,
    end: Int,
): Float {
    val size = end - start
    if (size <= 0) return 0f
    return ((viewportStartOffset - start).coerceAtLeast(0).toFloat() / size).coerceIn(0f, 1f)
}

private fun observedGridColumnCount(visibleItems: List<LazyGridItemInfo>): Int {
    if (visibleItems.isEmpty()) {
        return 1
    }
    return visibleItems
        .groupingBy { item -> item.offset.y }
        .eachCount()
        .maxOf { (_, count) -> count }
        .coerceAtLeast(1)
}
