// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackRuntimeDiagnosticsTest {
    @Test
    fun sumsDroppedFramesAcrossAccessLogPlaybackPeriods() {
        assertEquals(7L, sumValidDroppedFrames(listOf(2L, 5L)))
    }

    @Test
    fun skipsNegativeAccessLogSentinels() {
        assertEquals(3L, sumValidDroppedFrames(listOf(-1L, 3L, -1L)))
    }

    @Test
    fun returnsNullWhenEveryAccessLogCountIsUnknown() {
        assertNull(sumValidDroppedFrames(listOf(-1L, -5L)))
    }

    @Test
    fun preservesZeroWhenFirstValidAccessLogCountHasNoDrops() {
        assertEquals(0L, sumValidDroppedFrames(listOf(0L)))
    }
}
