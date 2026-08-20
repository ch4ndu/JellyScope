// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSelectionTest {
    @Test
    fun negativeAudioIndexNormalizesToNull() {
        assertNull(PlaybackSelection(audioStreamIndex = -1).normalized().audioStreamIndex)
        assertEquals(2, PlaybackSelection(audioStreamIndex = 2).normalized().audioStreamIndex)
    }

    @Test
    fun playbackSelectionContainsOnlyDurableAudioChoice() {
        assertEquals(PlaybackSelection(audioStreamIndex = 2), PlaybackSelection(audioStreamIndex = 2).normalized())
    }
}
