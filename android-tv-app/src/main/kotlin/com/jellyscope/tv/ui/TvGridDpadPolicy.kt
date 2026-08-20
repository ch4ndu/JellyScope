// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

internal enum class TvGridDpadDirection {
    Up,
    Down,
}

internal data class TvGridDpadDecision(
    val consumed: Boolean,
    val acceptedAtMs: Long? = null,
    val targetIndex: Int? = null,
    val loadMore: Boolean = false,
)

internal object TvGridDpadPolicy {
    fun decide(
        direction: TvGridDpadDirection,
        eventTimeMs: Long,
        lastAcceptedAtMs: Long,
        focusedIndex: Int,
        itemCount: Int,
        columnCount: Int,
        hasMore: Boolean,
    ): TvGridDpadDecision {
        if (itemCount <= 0) return TvGridDpadDecision(consumed = false)
        if (eventTimeMs - lastAcceptedAtMs < NAV_THROTTLE_MS) {
            return TvGridDpadDecision(consumed = true)
        }

        val lastItemIndex = itemCount - 1
        val currentIndex = focusedIndex.coerceIn(0, lastItemIndex)
        val columns = columnCount.coerceAtLeast(1)
        return when (direction) {
            TvGridDpadDirection.Down -> {
                val target = currentIndex + columns
                if (target <= lastItemIndex) {
                    TvGridDpadDecision(true, eventTimeMs, targetIndex = target)
                } else {
                    val firstIndexInLastRow = (lastItemIndex / columns) * columns
                    when {
                        currentIndex < firstIndexInLastRow ->
                            TvGridDpadDecision(true, eventTimeMs, targetIndex = lastItemIndex)
                        hasMore -> TvGridDpadDecision(true, eventTimeMs, loadMore = true)
                        else -> TvGridDpadDecision(true, eventTimeMs)
                    }
                }
            }
            TvGridDpadDirection.Up -> {
                val target = currentIndex - columns
                if (target < 0) {
                    TvGridDpadDecision(consumed = false, acceptedAtMs = eventTimeMs)
                } else {
                    TvGridDpadDecision(true, eventTimeMs, targetIndex = target)
                }
            }
        }
    }
}

private const val NAV_THROTTLE_MS = 75L
