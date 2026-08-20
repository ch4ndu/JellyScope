// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivationRetryTest {
    @Test
    fun attemptsOneThroughNineReturnNextRetry() {
        (1 until MAX_ACTIVATION_ATTEMPTS).forEach { attempt ->
            assertEquals(attempt + 1, nextActivationAttempt(attempt))
        }
    }

    @Test
    fun attemptTenIsExhausted() {
        assertNull(nextActivationAttempt(MAX_ACTIVATION_ATTEMPTS))
    }
}
