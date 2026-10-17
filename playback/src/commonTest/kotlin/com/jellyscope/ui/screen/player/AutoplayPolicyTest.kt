// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AutoplayPolicyTest {
    @Test
    fun disabledAutoplayNeverStartsCountdown() {
        val policy = AutoplayPolicySnapshot(enabled = false, itemId = "next")

        assertNull(policy.countdownDelayMs(countdownStarted = true))
    }

    @Test
    fun zeroAndCustomDelaysStayAtTheirPreferenceBoundaries() {
        val zero = AutoplayPolicySnapshot(enabled = true, delayMs = 0L, itemId = "next")
        val custom = AutoplayPolicySnapshot(enabled = true, delayMs = 15_000L, itemId = "next")

        assertEquals(0L, zero.countdownDelayMs(countdownStarted = true))
        assertEquals(15_000L, custom.countdownDelayMs(countdownStarted = true))
        assertNull(custom.countdownDelayMs(countdownStarted = false))
    }

    @Test
    fun countdownKeyChangesForQueueItemAndPlaybackGeneration() {
        val base = AutoplayPolicySnapshot(queueIdentity = "a|b", itemId = "b", playbackGeneration = 1L)

        assertNotEquals(base.countdownKey, base.copy(itemId = "c").countdownKey)
        assertNotEquals(base.countdownKey, base.copy(queueIdentity = "a|c").countdownKey)
        assertNotEquals(base.countdownKey, base.copy(playbackGeneration = 2L).countdownKey)
    }
}
