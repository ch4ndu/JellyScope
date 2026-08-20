// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.domain.playback.PlaybackQualityMode
import com.jellyscope.core.domain.playback.PlaybackQualityPolicy
import com.jellyscope.core.domain.playback.playerQualityOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlayerQualityOptionIdentityTest {
    @Test
    fun inheritedDefaultAndExplicitAutoHaveDistinctStableIdentities() {
        val options = playerQualityOptions(sourceBitrateBps = 20_000_000L)
        val identities = options.map(::playerQualityOptionIdentity)

        assertEquals(identities.size, identities.toSet().size)
        assertEquals("quality-inherited-default", identities[0])
        assertEquals("quality-Auto-null", identities[1])
        assertEquals("quality-Original-null", identities[2])
        assertNotEquals(identities[0], identities[1])
        assertEquals(
            identities,
            playerQualityOptions(sourceBitrateBps = 20_000_000L).map(::playerQualityOptionIdentity),
        )
    }

    @Test
    fun offLadderFixedSelectionHasAUniqueStableIdentity() {
        val options =
            playerQualityOptions(
                sourceBitrateBps = 20_000_000L,
                selectedPolicy = PlaybackQualityPolicy.fixed(10_000_000L),
            )
        val identities = options.map(::playerQualityOptionIdentity)
        val custom =
            options.single { option ->
                option.mode == PlaybackQualityMode.Fixed &&
                    option.isCustom &&
                    option.maxBitrateBps == 10_000_000L
            }

        assertEquals(identities.size, identities.toSet().size)
        assertEquals("quality-Fixed-10000000", playerQualityOptionIdentity(custom))
        assertEquals(
            playerQualityOptionIdentity(custom),
            playerQualityOptionIdentity(custom.copy()),
        )
    }
}
