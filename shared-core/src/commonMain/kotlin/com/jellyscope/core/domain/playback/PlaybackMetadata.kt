// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.math.ceil

data class MediaSegment(
    val type: MediaSegmentType,
    val startTicks: Long,
    val endTicks: Long,
) {
    val startMs: Long = ticksToMilliseconds(startTicks)
    val endMs: Long = ticksToMilliseconds(endTicks)
}

enum class MediaSegmentType(
    val apiValue: String,
) {
    Intro("Intro"),
    Outro("Outro"),
    Recap("Recap"),
    Preview("Preview"),
    Commercial("Commercial"),
    Unknown("Unknown"),
}

data class TrickplayInfo(
    val resolutionKey: String,
    val width: Int,
    val height: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val thumbnailCount: Int,
    val intervalMs: Long,
) {
    val thumbnailsPerTile: Int = (tileWidth * tileHeight).coerceAtLeast(1)
    val tileCount: Int = ceil(thumbnailCount.coerceAtLeast(0).toDouble() / thumbnailsPerTile.toDouble()).toInt()

    fun tileIndexForPositionMs(positionMs: Long): Int {
        if (intervalMs <= 0L || thumbnailCount <= 0) {
            return 0
        }

        val thumbnailIndex =
            positionMs
                .coerceAtLeast(0L)
                .div(intervalMs)
                .coerceAtMost((thumbnailCount - 1).toLong())
                .toInt()
        return thumbnailIndex.div(thumbnailsPerTile)
    }
}

data class Chapter(
    val name: String,
    val startTicks: Long,
) {
    val startMs: Long = ticksToMilliseconds(startTicks)
}

fun List<Chapter>.currentChapterIndex(positionMs: Long): Int {
    if (isEmpty()) {
        return -1
    }
    val normalizedPositionMs = positionMs.coerceAtLeast(0L)
    var currentIndex = -1
    var currentStartMs = Long.MIN_VALUE
    forEachIndexed { index, chapter ->
        if (chapter.startMs <= normalizedPositionMs && chapter.startMs >= currentStartMs) {
            currentIndex = index
            currentStartMs = chapter.startMs
        }
    }
    return currentIndex
}

data class SubtitleStyle(
    val fontScale: Float = 1f,
    val foregroundColor: String? = null,
    val backgroundColor: String? = null,
    val edgeStyle: SubtitleEdgeStyle = SubtitleEdgeStyle.None,
)

enum class SubtitleEdgeStyle {
    None,
    Outline,
    DropShadow,
}

fun List<MediaSegment>.currentSegment(positionMs: Long): MediaSegment? {
    val normalizedPositionMs = positionMs.coerceAtLeast(0L)
    return firstOrNull { segment ->
        normalizedPositionMs >= segment.startMs &&
            normalizedPositionMs < segment.endMs
    }
}

const val DEFAULT_PLAYBACK_SPEED = 1f
const val MIN_PLAYBACK_SPEED = 0.25f
const val MAX_PLAYBACK_SPEED = 3f
