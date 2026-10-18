// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.domain.playback.PlayerBackend
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackFallbackPolicyTest {
    @Test
    fun fallbackPoliciesPreserveTheIncomingBackend() {
        PlaybackFallbackAttempt.entries.forEach { attempt ->
            assertEquals(PlayerBackend.VlcKit, attempt.requestPolicy(PlayerBackend.VlcKit).backend)
        }
    }
}
