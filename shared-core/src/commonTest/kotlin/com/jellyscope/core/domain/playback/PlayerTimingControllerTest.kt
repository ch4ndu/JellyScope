// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import com.jellyscope.core.domain.model.PLAYBACK_TIMING_OFFSET_LIMIT_MS
import com.jellyscope.core.domain.model.PlaybackTimingKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTimingControllerTest {
    @Test
    fun timingValuesClampSymmetricallyAndPreserveSupport() {
        val value =
            PlayerTimingValue(
                offsetMs = Long.MAX_VALUE,
                support = PlayerTimingSupport.Supported,
            ).normalized()

        // Derived from the domain limit rather than hardcoded, so widening the
        // range does not silently leave this asserting the old bound.
        assertEquals(PLAYBACK_TIMING_OFFSET_LIMIT_MS, value.offsetMs)
        assertEquals(
            -PLAYBACK_TIMING_OFFSET_LIMIT_MS,
            PlayerTimingValue(
                offsetMs = Long.MIN_VALUE,
                support = PlayerTimingSupport.Supported,
            ).normalized().offsetMs,
        )
        assertTrue(value.isSupported)
        assertFalse(PlayerTimingState.Unsupported.value(PlaybackTimingKind.Audio).isSupported)
    }

    @Test
    fun timingStateKeepsAudioAndSubtitleValuesIndependent() {
        val state =
            PlayerTimingState(
                audio = PlayerTimingValue(250L, PlayerTimingSupport.Supported),
                subtitle = PlayerTimingValue(-1_000L, PlayerTimingSupport.Supported),
            )

        assertEquals(250L, state.value(PlaybackTimingKind.Audio).offsetMs)
        assertEquals(-1_000L, state.value(PlaybackTimingKind.Subtitle).offsetMs)
    }

    @Test
    fun positiveOnlyIsSupportedButRejectsNegativeOffsets() {
        val value = PlayerTimingValue(support = PlayerTimingSupport.PositiveOnly)

        assertTrue(value.isSupported)
        assertFalse(value.supportsNegative)
    }

    @Test
    fun supportedAllowsNegativeOffsetsAndUnsupportedDoesNot() {
        assertTrue(PlayerTimingValue(support = PlayerTimingSupport.Supported).supportsNegative)
        assertFalse(PlayerTimingValue(support = PlayerTimingSupport.Unsupported).isSupported)
        assertFalse(PlayerTimingValue(support = PlayerTimingSupport.Unsupported).supportsNegative)
    }
}
