// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFocusAdmissionTest {
    @Test
    fun focusGrantAfterDenialStartsExactlyOneRetry() {
        var starts = 0
        var requests = 0
        val requestFocus = { ++requests > 1 }

        val first = PlaybackFocusAdmission.admit(requestFocus) { starts += 1 }
        val retry = PlaybackFocusAdmission.admit(requestFocus) { starts += 1 }

        assertFalse(first)
        assertTrue(retry)
        assertEquals(1, starts)
    }
}
