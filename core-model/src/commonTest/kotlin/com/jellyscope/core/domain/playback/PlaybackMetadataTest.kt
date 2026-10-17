// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackMetadataTest {
    @Test
    fun currentChapterIndexReturnsMissingForEmptyChapters() {
        assertEquals(-1, emptyList<Chapter>().currentChapterIndex(positionMs = 12_000L))
    }

    @Test
    fun currentChapterIndexReturnsMissingBeforeFirstChapter() {
        val chapters =
            listOf(
                Chapter("Opening", startTicks = 10_000_000L),
                Chapter("Act Two", startTicks = 30_000_000L),
            )

        assertEquals(-1, chapters.currentChapterIndex(positionMs = 500L))
    }

    @Test
    fun currentChapterIndexSelectsExactBoundary() {
        val chapters =
            listOf(
                Chapter("Opening", startTicks = 0L),
                Chapter("Act Two", startTicks = 30_000_000L),
            )

        assertEquals(1, chapters.currentChapterIndex(positionMs = 3_000L))
    }

    @Test
    fun currentChapterIndexSelectsPreviousChapterBetweenBoundaries() {
        val chapters =
            listOf(
                Chapter("Opening", startTicks = 0L),
                Chapter("Act Two", startTicks = 30_000_000L),
                Chapter("Finale", startTicks = 60_000_000L),
            )

        assertEquals(1, chapters.currentChapterIndex(positionMs = 4_500L))
    }

    @Test
    fun currentChapterIndexSelectsFinalChapterAfterFinalBoundary() {
        val chapters =
            listOf(
                Chapter("Opening", startTicks = 0L),
                Chapter("Finale", startTicks = 60_000_000L),
            )

        assertEquals(1, chapters.currentChapterIndex(positionMs = 120_000L))
    }

    @Test
    fun currentChapterIndexUsesLatestTimestampWhenInputIsUnsorted() {
        val chapters =
            listOf(
                Chapter(name = "Middle", startTicks = 20_000_000L),
                Chapter(name = "Opening", startTicks = 0L),
                Chapter(name = "Later", startTicks = 50_000_000L),
            )

        assertEquals(0, chapters.currentChapterIndex(positionMs = 3_000L))
    }
}
