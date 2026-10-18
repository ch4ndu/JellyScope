// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackStartPositionTest {
    @Test
    fun clampedStartPositionNeverFallsBelowZero() {
        assertEquals(0L, plan(-1L).clampedStartPositionMs())
        assertEquals(0L, plan(0L).clampedStartPositionMs())
        assertEquals(1_500L, plan(1_500L).clampedStartPositionMs())
    }

    @Test
    fun resumeSeekPositionTreatsZeroAndNegativeAsNoSeek() {
        assertNull(plan(-1L).resumeSeekPositionMs())
        assertNull(plan(0L).resumeSeekPositionMs())
        assertEquals(1_500L, plan(1_500L).resumeSeekPositionMs())
    }

    private companion object {
        fun plan(startPositionMs: Long): PlaybackPlan =
            PlaybackPlan(
                itemId = "item-1",
                mediaSourceId = "source-1",
                startPositionMs = startPositionMs,
                streamMode = StreamMode.DirectPlay,
                streamUrl = "https://jellyfin.example/Videos/item-1/stream",
                progressReportingPolicy = ProgressReportingPolicy(10_000L),
            )
    }
}
