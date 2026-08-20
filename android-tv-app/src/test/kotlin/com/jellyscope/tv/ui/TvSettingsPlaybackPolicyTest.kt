// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import com.jellyscope.core.domain.playback.PlaybackQualityChoice
import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvSettingsPlaybackPolicyTest {
    @Test
    fun defaultQualityChoicesKeepAutoOriginalAndStoredCustomPolicyDistinct() {
        val custom = PlaybackQualityPolicy.fixed(13_000_000L)

        val selected = settingsQualityChoice(custom)

        assertEquals(PlaybackQualityMode.Fixed, selected.mode)
        assertEquals(13_000_000L, selected.maxBitrateBps)
        assertTrue(selected.isCustom)
        assertEquals(custom, selected.toPlaybackQualityPolicy())
        assertEquals(
            PlaybackQualityPolicy.Auto,
            PlaybackQualityChoice(null, mode = PlaybackQualityMode.Auto).toPlaybackQualityPolicy(),
        )
        assertEquals(
            PlaybackQualityPolicy.Original,
            PlaybackQualityChoice(null, mode = PlaybackQualityMode.Original).toPlaybackQualityPolicy(),
        )
    }
}
