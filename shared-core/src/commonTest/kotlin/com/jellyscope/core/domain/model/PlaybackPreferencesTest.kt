// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.PlayerBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackPreferencesTest {
    private val preferences =
        PlaybackPreferences(
            defaultQualityPolicy = PlaybackQualityPolicy.Original,
            vlcTranscodeMaxBitrateBps = 8_000_000L,
        ).normalized()

    @Test
    fun vlcFamilyUsesConfiguredDefaultQuality() {
        val expected = PlaybackQualityPolicy.fixed(8_000_000L)

        assertEquals(expected, preferences.effectiveDefaultQualityPolicy(PlayerBackend.LibVlc))
        assertEquals(expected, preferences.effectiveDefaultQualityPolicy(PlayerBackend.VlcKit))
        assertTrue(preferences.usesVlcDefaultQuality(PlayerBackend.LibVlc))
        assertTrue(preferences.usesVlcDefaultQuality(PlayerBackend.VlcKit))
    }

    @Test
    fun nonVlcBackendsKeepGeneralPlaybackDefault() {
        listOf(PlayerBackend.ExoPlayer, PlayerBackend.AVPlayer, PlayerBackend.Mpv).forEach { backend ->
            assertEquals(PlaybackQualityPolicy.Original, preferences.effectiveDefaultQualityPolicy(backend))
            assertFalse(preferences.usesVlcDefaultQuality(backend))
        }
    }

    @Test
    fun disabledVlcDefaultInheritsGeneralPlaybackDefault() {
        val inherited =
            preferences
                .copy(vlcTranscodeMaxBitrateBps = null)
                .normalized()

        assertEquals(PlaybackQualityPolicy.Original, inherited.effectiveDefaultQualityPolicy(PlayerBackend.LibVlc))
        assertFalse(inherited.usesVlcDefaultQuality(PlayerBackend.LibVlc))
    }
}
