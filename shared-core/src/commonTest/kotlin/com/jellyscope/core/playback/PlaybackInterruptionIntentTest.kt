// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackInterruptionIntentTest {
    @Test
    fun resumeIntentIsConsumedAfterOneSuccessfulEnd() {
        val intent = PlaybackInterruptionIntent()

        intent.onInterruptionBegan(playWhenReady = true)

        assertTrue(intent.onInterruptionEnded(shouldResume = true))
        assertFalse(intent.onInterruptionEnded(shouldResume = true))
    }

    @Test
    fun pausedPlaybackDoesNotResumeAfterInterruption() {
        val intent = PlaybackInterruptionIntent()

        intent.onInterruptionBegan(playWhenReady = false)

        assertFalse(intent.onInterruptionEnded(shouldResume = true))
    }

    @Test
    fun nonResumingEndConsumesTheCapturedIntent() {
        val intent = PlaybackInterruptionIntent()

        intent.onInterruptionBegan(playWhenReady = true)

        assertFalse(intent.onInterruptionEnded(shouldResume = false))
        assertFalse(intent.onInterruptionEnded(shouldResume = true))
    }

    @Test
    fun resetDropsAnIntentCapturedForThePreviousItem() {
        val intent = PlaybackInterruptionIntent()

        intent.onInterruptionBegan(playWhenReady = true)
        intent.reset()

        assertFalse(intent.onInterruptionEnded(shouldResume = true))
    }
}
