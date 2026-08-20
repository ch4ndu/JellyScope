// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import org.junit.Test
import kotlin.test.assertEquals

class TvBringIntoViewSpecsTest {
    @Test
    fun trailingCardMetadataIsScrolledFullyIntoViewInsideCenterDeadZone() {
        assertEquals(
            expected = 40f,
            actual =
                libraryGridScrollDistance(
                    offset = 220f,
                    size = 300f,
                    containerSize = 600f,
                    metadataReserve = 120f,
                    sameRowSlack = 0f,
                ),
        )
    }

    @Test
    fun clippedLeadingEdgeIsRevealed() {
        assertEquals(
            expected = -20f,
            actual =
                libraryGridScrollDistance(
                    offset = -20f,
                    size = 300f,
                    containerSize = 600f,
                    metadataReserve = 80f,
                    sameRowSlack = 0f,
                ),
        )
    }

    @Test
    fun fullyVisibleCardInsideCenterDeadZoneDoesNotScroll() {
        assertEquals(
            expected = 0f,
            actual =
                libraryGridScrollDistance(
                    offset = 100f,
                    size = 300f,
                    containerSize = 600f,
                    metadataReserve = 80f,
                    sameRowSlack = 0f,
                ),
        )
    }

    @Test
    fun fullyVisibleCardOutsideCenterDeadZoneUsesPosterCenterDistance() {
        assertEquals(
            expected = -200f,
            actual =
                libraryGridScrollDistance(
                    offset = 0f,
                    size = 100f,
                    containerSize = 500f,
                    metadataReserve = 40f,
                    sameRowSlack = 0f,
                ),
        )
    }

    @Test
    fun minorScaleOverflowInsideCenterDeadZoneDoesNotMoveSameRow() {
        assertEquals(
            expected = 0f,
            actual =
                libraryGridScrollDistance(
                    offset = 110f,
                    size = 300f,
                    containerSize = 500f,
                    metadataReserve = 100f,
                    sameRowSlack = 12f,
                ),
        )
    }
}
