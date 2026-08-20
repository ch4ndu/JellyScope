// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.ui.theme.Dimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFeedbackPolicyTest {
    @Test
    fun desktopDebugOverlayUsesSixtyPercentOfBothDimensions() {
        assertEquals(0.6f, PLAYER_DEBUG_OVERLAY_WIDTH_FRACTION)
        assertEquals(0.6f, PLAYER_DEBUG_OVERLAY_HEIGHT_FRACTION)
    }

    @Test
    fun seekThumbRemainsLargerThanVolumeThumbAcrossInteractionStates() {
        assertTrue(Dimensions.playerSeekThumbSize > Dimensions.playerSliderThumbSize)
        assertTrue(Dimensions.playerSeekThumbActiveSize > Dimensions.playerSliderThumbActiveSize)
        assertEquals(18, Dimensions.playerSeekThumbSize.value.toInt())
        assertEquals(22, Dimensions.playerSeekThumbActiveSize.value.toInt())
        assertEquals(12, Dimensions.playerSliderThumbSize.value.toInt())
        assertEquals(16, Dimensions.playerSliderThumbActiveSize.value.toInt())
    }

    @Test
    fun upNextDismissalIdentityRearmsForTargetQueueOrPlaybackGenerationChanges() {
        val upNext = UpNextInfo(itemId = "episode-2", title = "Episode 2", imageUrl = null, index = 1)
        val key = AutoplayCountdownKey(queueIdentity = "episode-1|episode-2", itemId = "episode-2", playbackGeneration = 7)
        val identity = upNext.dismissalIdentity(key)

        assertEquals(identity, upNext.dismissalIdentity(key))
        assertNotEquals(identity, upNext.copy(itemId = "episode-3").dismissalIdentity(key))
        assertNotEquals(identity, upNext.copy(index = 2).dismissalIdentity(key))
        assertNotEquals(identity, upNext.dismissalIdentity(key.copy(queueIdentity = "replacement")))
        assertNotEquals(identity, upNext.dismissalIdentity(key.copy(playbackGeneration = 8)))
    }

    @Test
    fun desktopQueueCombinesEpisodeIdentityAndNowPlayingState() {
        assertEquals("S2 · E4 · Now Playing", queueSecondaryLabel(2, 4, "Now Playing", selected = true))
        assertEquals("E4", queueSecondaryLabel(null, 4, "Now Playing", selected = false))
        assertEquals("Now Playing", queueSecondaryLabel(null, null, "Now Playing", selected = true))
        assertNull(queueSecondaryLabel(null, null, "Now Playing", selected = false))
    }
}
