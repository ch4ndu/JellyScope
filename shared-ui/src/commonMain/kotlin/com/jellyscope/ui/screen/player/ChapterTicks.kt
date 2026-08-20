// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.ui.geometry.Offset
import com.jellyscope.core.domain.playback.Chapter
import kotlin.math.roundToInt

/** Batched tick endpoints with duplicate pixel columns removed. */
fun chapterTickPoints(
    chapters: List<Chapter>,
    durationMs: Long,
    width: Float,
    top: Float,
    bottom: Float,
): List<Offset> {
    if (chapters.isEmpty() || durationMs <= 0L || width <= 0f) {
        return emptyList()
    }
    val occupiedColumns = HashSet<Int>(chapters.size)
    val points = ArrayList<Offset>(chapters.size * 2)
    chapters.forEach { chapter ->
        val fraction = chapter.startMs.toFloat() / durationMs.toFloat()
        if (fraction in 0f..1f) {
            val x = width * fraction
            if (occupiedColumns.add(x.roundToInt())) {
                points.add(Offset(x, top))
                points.add(Offset(x, bottom))
            }
        }
    }
    return points
}
