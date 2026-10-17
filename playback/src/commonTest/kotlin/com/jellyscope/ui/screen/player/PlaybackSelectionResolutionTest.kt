// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSelectionResolutionTest {
    @Test
    fun durableSelectionWinsOverSourceAndLegacyCaches() {
        val durable = PlaybackSelection(audioStreamIndex = 1)
        val source = PlaybackSelection(audioStreamIndex = 3)
        val legacy = PlaybackSelection(audioStreamIndex = 5)

        assertEquals(
            durable,
            resolvePlaybackSelection(durable, source, legacy, durableStoreAvailable = true),
        )
    }

    @Test
    fun legacyCacheIsOnlyFallbackWhenDurableStoreIsUnavailable() {
        val legacy = PlaybackSelection(audioStreamIndex = 5)

        assertEquals(
            legacy,
            resolvePlaybackSelection(null, null, legacy, durableStoreAvailable = false),
        )
        assertNull(resolvePlaybackSelection(null, null, legacy, durableStoreAvailable = true))
    }

    @Test
    fun rememberedDurableWinsOverBothMemoryCaches() {
        val durable = PlaybackSelection(audioStreamIndex = 1)
        val source = PlaybackSelection(audioStreamIndex = 3)
        val legacy = PlaybackSelection(audioStreamIndex = 5)

        assertEquals(
            durable,
            resolveRememberedSelection(durable, source, legacy, durableStoreAvailable = true),
        )
    }

    @Test
    fun rememberedUsesSourceMemoryWhenDurableStoreAvailableElseLegacy() {
        val source = PlaybackSelection(audioStreamIndex = 3)
        val legacy = PlaybackSelection(audioStreamIndex = 5)

        assertEquals(
            source,
            resolveRememberedSelection(null, source, legacy, durableStoreAvailable = true),
        )
        assertEquals(
            legacy,
            resolveRememberedSelection(null, source, legacy, durableStoreAvailable = false),
        )
    }
}
