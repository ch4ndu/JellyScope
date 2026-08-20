// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackQualityPolicyTest {
    @Test
    fun playerChoicesExposeInheritanceSeparatelyFromExplicitAuto() {
        val choices = playerQualityOptions(sourceBitrateBps = 20_000_000L)

        assertEquals(true, choices[0].inheritsDefault)
        assertEquals(PlaybackQualityMode.Auto, choices[1].mode)
        assertEquals(false, choices[1].inheritsDefault)
        assertEquals(PlaybackQualityMode.Original, choices[2].mode)
    }

    @Test
    fun autoAndOriginalDiscardStrayBitrates() {
        assertEquals(PlaybackQualityPolicy.Auto, PlaybackQualityPolicy(PlaybackQualityMode.Auto, 8_000_000L).normalized())
        assertEquals(
            PlaybackQualityPolicy.Original,
            PlaybackQualityPolicy(PlaybackQualityMode.Original, 8_000_000L).normalized(),
        )
    }

    @Test
    fun invalidFixedFallsBackToAuto() {
        assertEquals(PlaybackQualityPolicy.Auto, PlaybackQualityPolicy(PlaybackQualityMode.Fixed, 0L).normalized())
        assertEquals(PlaybackQualityPolicy.Auto, PlaybackQualityPolicy.fixed(Int.MAX_VALUE.toLong() + 1L))
    }

    @Test
    fun customBitrateIsPreservedAndDoesNotInventResolution() {
        val choices = playbackQualityChoices(PlaybackQualityPolicy.fixed(10_000_000L))
        val custom = choices.single { choice -> choice.maxBitrateBps == 10_000_000L }

        assertEquals(PlaybackQualityMode.Fixed, custom.mode)
        assertEquals(10_000_000L, custom.maxBitrateBps)
        assertNull(custom.resolutionWidth)
        assertNull(custom.resolutionHeight)
        assertEquals(PlaybackQualityMode.Original, choices[1].mode)
    }

    @Test
    fun constraintsKeepProductPolicySeparateFromProtocolSentinel() {
        assertEquals(Int.MAX_VALUE.toLong(), PlaybackBitrateConstraint.NoClientLimit.bitrateBps)
        assertEquals(PlaybackBitrateConstraint.NoClientLimit, PlaybackQualityPolicy.Original.toBitrateConstraint())
        assertEquals(
            PlaybackBitrateConstraint.ExactUserLimit(8_000_000L),
            PlaybackQualityPolicy.fixed(8_000_000L).toBitrateConstraint(),
        )
    }
}
