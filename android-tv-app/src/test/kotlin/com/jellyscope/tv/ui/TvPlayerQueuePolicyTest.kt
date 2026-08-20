// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.ui.input.key.Key
import com.jellyscope.ui.screen.player.AutoplayPolicySnapshot
import com.jellyscope.ui.screen.player.countdownDelayMs
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TvPlayerQueuePolicyTest {
    @Test
    fun dedicatedTransportKeysRemainDistinctFromToggle() {
        assertEquals(TvPlayerTransportAction.Play, tvPlayerTransportActionForKey(Key.MediaPlay))
        assertEquals(TvPlayerTransportAction.Pause, tvPlayerTransportActionForKey(Key.MediaPause))
        assertEquals(TvPlayerTransportAction.Toggle, tvPlayerTransportActionForKey(Key.MediaPlayPause))
        assertEquals(TvPlayerTransportAction.Next, tvPlayerTransportActionForKey(Key.MediaNext))
        assertEquals(TvPlayerTransportAction.Previous, tvPlayerTransportActionForKey(Key.MediaPrevious))
        assertEquals(TvPlayerTransportAction.Stop, tvPlayerTransportActionForKey(Key.MediaStop))
        assertNull(tvPlayerTransportActionForKey(Key.DirectionCenter))
    }

    @Test
    fun playNowAdvancesWithoutInvokingStillWatchingConfirmation() {
        val playNextRequests = mutableListOf<Boolean>()
        var confirmationCount = 0

        performTvUpNextPrimaryAction(
            stillWatchingPrompt = false,
            onPlayNext = { auto, _ -> playNextRequests.add(auto) },
            onConfirmStillWatching = { confirmationCount += 1 },
        )

        assertEquals(listOf(false), playNextRequests)
        assertEquals(0, confirmationCount)
    }

    @Test
    fun stillWatchingPrimaryActionConfirmsWithoutAdvancingAgain() {
        val playNextRequests = mutableListOf<Boolean>()
        var confirmationCount = 0

        performTvUpNextPrimaryAction(
            stillWatchingPrompt = true,
            onPlayNext = { auto, _ -> playNextRequests.add(auto) },
            onConfirmStillWatching = { confirmationCount += 1 },
        )

        assertEquals(emptyList(), playNextRequests)
        assertEquals(1, confirmationCount)
    }

    @Test
    fun autoplayPolicyCancelsWhenQueueIdentityOrGenerationChanges() {
        val policy =
            AutoplayPolicySnapshot(
                queueIdentity = "first|second",
                itemId = "second",
                playbackGeneration = 4L,
            )

        assertEquals(10_000L, policy.countdownDelayMs(countdownStarted = true))
        val stale = policy.copy(queueIdentity = "first|third", itemId = "third", playbackGeneration = 5L)
        assertNotEquals(policy.countdownKey, stale.countdownKey)
        assertNull(policy.countdownDelayMs(countdownStarted = false))
    }
}
