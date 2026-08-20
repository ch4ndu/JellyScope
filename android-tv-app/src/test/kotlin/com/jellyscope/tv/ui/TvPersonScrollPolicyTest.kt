// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import org.junit.Test
import kotlin.test.assertEquals

class TvPersonScrollPolicyTest {
    @Test
    fun sectionBelowTheViewportScrollsJustFarEnoughToRevealItsBottom() {
        assertEquals(
            220,
            personSectionScrollTarget(
                sectionTopInViewport = 500,
                sectionHeight = 260,
                viewportHeight = 540,
                scrollValue = 0,
                maxValue = 310,
            ),
        )
    }

    @Test
    fun sectionAboveTheViewportScrollsBackToItsTop() {
        assertEquals(
            190,
            personSectionScrollTarget(
                sectionTopInViewport = -30,
                sectionHeight = 260,
                viewportHeight = 540,
                scrollValue = 220,
                maxValue = 310,
            ),
        )
    }

    @Test
    fun fullyVisibleSectionKeepsTheCurrentScroll() {
        assertEquals(
            120,
            personSectionScrollTarget(
                sectionTopInViewport = 100,
                sectionHeight = 200,
                viewportHeight = 540,
                scrollValue = 120,
                maxValue = 310,
            ),
        )
    }

    @Test
    fun revealClampsAtTheEndOfTheScrollRange() {
        assertEquals(
            310,
            personSectionScrollTarget(
                sectionTopInViewport = 500,
                sectionHeight = 300,
                viewportHeight = 540,
                scrollValue = 100,
                maxValue = 310,
            ),
        )
    }

    @Test
    fun revealClampsAtTheStartOfTheScrollRange() {
        assertEquals(
            0,
            personSectionScrollTarget(
                sectionTopInViewport = -100,
                sectionHeight = 100,
                viewportHeight = 540,
                scrollValue = 10,
                maxValue = 310,
            ),
        )
    }

    @Test
    fun unmeasuredViewportKeepsTheCurrentScroll() {
        assertEquals(
            42,
            personSectionScrollTarget(
                sectionTopInViewport = 500,
                sectionHeight = 260,
                viewportHeight = 0,
                scrollValue = 42,
                maxValue = 310,
            ),
        )
    }

    @Test
    fun contentShorterThanTheViewportNeverScrolls() {
        assertEquals(
            0,
            personSectionScrollTarget(
                sectionTopInViewport = 300,
                sectionHeight = 200,
                viewportHeight = 540,
                scrollValue = 0,
                maxValue = 0,
            ),
        )
    }
}
