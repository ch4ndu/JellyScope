// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.MediaSegmentType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvPlayerSkipActionTest {
    @Test
    fun introAndRecapMapToSkipIntro() {
        assertEquals(SkipSegmentAction.Intro, segment(MediaSegmentType.Intro).skipAction())
        assertEquals(SkipSegmentAction.Intro, segment(MediaSegmentType.Recap).skipAction())
    }

    @Test
    fun outroAndPreviewMapToSkipCredits() {
        assertEquals(SkipSegmentAction.Credits, segment(MediaSegmentType.Outro).skipAction())
        assertEquals(SkipSegmentAction.Credits, segment(MediaSegmentType.Preview).skipAction())
    }

    @Test
    fun commercialMapsToGenericSkipAndUnknownStaysHidden() {
        assertEquals(SkipSegmentAction.Generic, segment(MediaSegmentType.Commercial).skipAction())
        assertNull(segment(MediaSegmentType.Unknown).skipAction())
    }

    private fun segment(type: MediaSegmentType) =
        MediaSegment(
            type = type,
            startTicks = 10_000_000L,
            endTicks = 30_000_000L,
        )
}
