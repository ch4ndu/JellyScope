// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.Chapter
import com.jellyscope.core.domain.playback.JELLYFIN_TICKS_PER_MILLISECOND
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterTicksTest {
    @Test
    fun eachChapterBecomesOneLineSegment() {
        val points = chapterTickPoints(chaptersAt(0L, 30_000L, 60_000L), 60_000L, width = 600f, top = 2f, bottom = 8f)

        assertEquals(6, points.size) // 3 ticks, 2 endpoints each
        assertEquals(0f, points[0].x)
        assertEquals(2f, points[0].y)
        assertEquals(8f, points[1].y)
        assertEquals(300f, points[2].x)
        assertEquals(600f, points[4].x)
    }

    @Test
    fun ticksSharingAPixelColumnAreDroppedBecauseTheyOverdraw() {
        // The case that caused the lag: an auto-chaptered asset with one chapter
        // every 10s. Two hours at 10s is 720 chapters on a bar a few hundred pixels
        // wide, so most ticks land on a column another tick already occupies.
        val twoHoursMs = 2 * 60 * 60 * 1000L
        val everyTenSeconds = (0 until 720).map { index -> chapterAtMs(index * 10_000L, "c$index") }

        val points = chapterTickPoints(everyTenSeconds, twoHoursMs, width = 400f, top = 0f, bottom = 10f)

        // At most one tick per pixel column, so the op count is bounded by width.
        assertTrue(points.size / 2 <= 401, "expected <=401 ticks for a 400px bar, got ${points.size / 2}")
        assertTrue(points.size / 2 < 720, "dense ticks should collapse, got ${points.size / 2}")
        // Every retained x is distinct once rounded to a column — rounded, matching
        // how the dedupe decides, not truncated.
        val columns = points.filterIndexed { index, _ -> index % 2 == 0 }.map { offset -> offset.x.roundToInt() }
        assertEquals(columns.size, columns.distinct().size)
    }

    @Test
    fun chaptersPastTheDurationAreIgnored() {
        // Only the past-the-end case is expressible: `Chapter.startMs` is derived
        // from ticks and clamps at zero, so a negative start cannot be constructed.
        val points = chapterTickPoints(chaptersAt(30_000L, 120_000L), 60_000L, 600f, 0f, 10f)

        assertEquals(2, points.size)
        assertEquals(300f, points[0].x)
    }

    @Test
    fun degenerateInputsProduceNothingRatherThanDividingByZero() {
        assertTrue(chapterTickPoints(emptyList(), 60_000L, 600f, 0f, 10f).isEmpty())
        assertTrue(chapterTickPoints(chaptersAt(0L), 0L, 600f, 0f, 10f).isEmpty())
        assertTrue(chapterTickPoints(chaptersAt(0L), 60_000L, 0f, 0f, 10f).isEmpty())
    }

    private fun chaptersAt(vararg startsMs: Long): List<Chapter> =
        startsMs.mapIndexed { index, start -> chapterAtMs(start, "chapter $index") }

    private fun chapterAtMs(
        startMs: Long,
        name: String,
    ): Chapter = Chapter(name = name, startTicks = startMs * JELLYFIN_TICKS_PER_MILLISECOND)
}
