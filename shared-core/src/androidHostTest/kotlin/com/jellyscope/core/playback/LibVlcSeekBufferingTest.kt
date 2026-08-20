// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibVlcSeekBufferingTest {
    @Test
    fun initialTargetJumpDoesNotEndBufferingUntilPlaybackClockAdvances() {
        val arrivalPositionMs = 120_000L

        assertFalse(hasLibVlcSeekPlaybackResumed(arrivalPositionMs = null, currentPositionMs = arrivalPositionMs))
        assertFalse(hasLibVlcSeekPlaybackResumed(arrivalPositionMs, currentPositionMs = arrivalPositionMs))
        assertFalse(
            hasLibVlcSeekPlaybackResumed(
                arrivalPositionMs,
                currentPositionMs = arrivalPositionMs + SEEK_RESUME_ADVANCE_MS - 1L,
            ),
        )
        assertFalse(hasLibVlcSeekPlaybackResumed(arrivalPositionMs, currentPositionMs = arrivalPositionMs - 1L))
        assertTrue(
            hasLibVlcSeekPlaybackResumed(
                arrivalPositionMs,
                currentPositionMs = arrivalPositionMs + SEEK_RESUME_ADVANCE_MS,
            ),
        )
    }
}
