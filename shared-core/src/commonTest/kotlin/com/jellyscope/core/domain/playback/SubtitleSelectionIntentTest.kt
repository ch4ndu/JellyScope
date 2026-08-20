// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleSelectionIntentTest {
    @Test
    fun wireValuesPreserveUnspecifiedOffAndTrack() {
        assertEquals(SubtitleSelectionIntent.Unspecified, SubtitleSelectionIntent.fromWireIndex(null))
        assertEquals(SubtitleSelectionIntent.Off, SubtitleSelectionIntent.fromWireIndex(-1))
        assertEquals(SubtitleSelectionIntent.Track(4), SubtitleSelectionIntent.fromWireIndex(4))
        assertEquals(SubtitleSelectionIntent.Unspecified, SubtitleSelectionIntent.fromWireIndex(-2))

        assertEquals(null, SubtitleSelectionIntent.Unspecified.wireIndexOrNull())
        assertEquals(-1, SubtitleSelectionIntent.Off.wireIndexOrNull())
        assertEquals(4, SubtitleSelectionIntent.Track(4).wireIndexOrNull())
    }
}
