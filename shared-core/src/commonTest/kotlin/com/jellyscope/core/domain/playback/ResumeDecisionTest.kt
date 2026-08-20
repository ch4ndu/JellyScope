// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ResumeDecisionTest {
    @Test
    fun zeroPositionStartsFresh() {
        val decision =
            resumeDecision(
                playbackPositionTicks = 0,
                runtime = 45.minutes,
                played = false,
            )

        assertEquals(ResumeDecision.Start, decision)
    }

    @Test
    fun midPositionResumesAndFormatsPosition() {
        val decision =
            resumeDecision(
                playbackPositionTicks = 72.minutes.inWholeMilliseconds * JELLYFIN_TICKS_PER_MILLISECOND,
                runtime = 90.minutes,
                played = false,
            )

        val resume = assertIs<ResumeDecision.Resume>(decision)
        assertEquals(72.minutes, resume.position)
        assertEquals("1 h 12 min", formattedResumePosition(resume.position))
    }

    @Test
    fun playedItemStartsOver() {
        val decision =
            resumeDecision(
                playbackPositionTicks = 12.minutes.inWholeMilliseconds * JELLYFIN_TICKS_PER_MILLISECOND,
                runtime = 45.minutes,
                played = true,
            )

        assertEquals(ResumeDecision.StartOver, decision)
    }

    @Test
    fun nearCompletePositionStartsOver() {
        val decision =
            resumeDecision(
                playbackPositionTicks = (45.minutes - 30.seconds).inWholeMilliseconds * JELLYFIN_TICKS_PER_MILLISECOND,
                runtime = 45.minutes,
                played = false,
            )

        assertEquals(ResumeDecision.StartOver, decision)
    }
}
