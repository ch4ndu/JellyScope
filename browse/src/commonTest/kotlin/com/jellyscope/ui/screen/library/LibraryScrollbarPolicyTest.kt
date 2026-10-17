// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryScrollbarPolicyTest {
    @Test
    fun emptyAndFullyVisibleContentDoNotScroll() {
        val empty = libraryScrollbarMetrics(0, -1, -1)
        val one = libraryScrollbarMetrics(1, 0, 0)
        val fewerThanViewport = libraryScrollbarMetrics(3, 0, 2)
        val visible = libraryScrollbarMetrics(4, 0, 3)

        assertFalse(empty.hasMoreContent)
        assertFalse(one.hasMoreContent)
        assertFalse(fewerThanViewport.hasMoreContent)
        assertFalse(visible.hasMoreContent)
        assertEquals(0, libraryScrollbarTargetIndex(empty, 1f))
    }

    @Test
    fun listMetricsMapFirstMiddleAndLastMonotonically() {
        val metrics = libraryScrollbarMetrics(100, 40, 49)

        assertTrue(metrics.hasMoreContent)
        assertEquals(0.1f, metrics.visibleFraction)
        assertEquals(0, libraryScrollbarTargetIndex(metrics, 0f))
        assertEquals(45, libraryScrollbarTargetIndex(metrics, 0.5f))
        assertEquals(90, libraryScrollbarTargetIndex(metrics, 1f))
        val targets = (0..10).map { step -> libraryScrollbarTargetIndex(metrics, step / 10f) }
        assertTrue(targets.zipWithNext().all { (before, after) -> before <= after })
    }

    @Test
    fun fractionalScrollWithinLeadingRowMovesThumbContinuously() {
        val rowStart = libraryScrollbarMetrics(100, 40, 49)
        val rowMiddle =
            libraryScrollbarMetrics(
                totalItems = 100,
                firstVisibleIndex = 40,
                lastVisibleIndex = 49,
                firstVisibleRowScrollFraction = 0.5f,
            )
        val nextRow = libraryScrollbarMetrics(100, 41, 50)

        assertTrue(rowStart.normalizedLeadingPosition < rowMiddle.normalizedLeadingPosition)
        assertTrue(rowMiddle.normalizedLeadingPosition < nextRow.normalizedLeadingPosition)
        assertEquals(
            (40.5f / rowMiddle.scrollableRange),
            rowMiddle.normalizedLeadingPosition,
        )
    }

    @Test
    fun gridTargetsFirstItemOfRowsAndClampsPartialFinalRow() {
        val metrics =
            libraryScrollbarMetrics(
                totalItems = 10,
                firstVisibleIndex = 3,
                lastVisibleIndex = 8,
                itemsPerRow = 3,
            )

        assertEquals(4, metrics.totalRows)
        assertEquals(2, metrics.visibleRows)
        assertEquals(3, libraryScrollbarTargetIndex(metrics, 0.5f))
        assertEquals(6, libraryScrollbarTargetIndex(metrics, 1f))
    }

    @Test
    fun partiallyVisibleFinalItemKeepsTheScrollbarAndProgressActionAvailable() {
        val top =
            libraryScrollbarMetrics(
                totalItems = 2,
                firstVisibleIndex = 0,
                lastVisibleIndex = 1,
                visibleExtentInRows = 1.8f,
                canScrollBackward = false,
                canScrollForward = true,
            )
        val bottom =
            libraryScrollbarMetrics(
                totalItems = 2,
                firstVisibleIndex = 0,
                lastVisibleIndex = 1,
                visibleExtentInRows = 1.8f,
                canScrollBackward = true,
                canScrollForward = false,
            )

        assertTrue(top.hasMoreContent)
        assertEquals(1, top.scrollableRange)
        assertEquals(0.9f, top.visibleFraction)
        assertEquals(0f, top.normalizedLeadingPosition)
        assertEquals(1, libraryScrollbarTargetIndex(top, 1f))
        assertEquals(1f, bottom.normalizedLeadingPosition)
    }

    @Test
    fun singleRowPartialOverflowMapsProgressToAnIntraRowOffset() {
        assertEquals(0, libraryScrollbarScrollOffset(maxScrollOffsetPx = 240, normalizedPosition = 0f))
        assertEquals(120, libraryScrollbarScrollOffset(maxScrollOffsetPx = 240, normalizedPosition = 0.5f))
        assertEquals(240, libraryScrollbarScrollOffset(maxScrollOffsetPx = 240, normalizedPosition = 1f))
        assertEquals(0, libraryScrollbarScrollOffset(maxScrollOffsetPx = 240, normalizedPosition = Float.NaN))
        assertEquals(0, libraryScrollbarScrollOffset(maxScrollOffsetPx = 0, normalizedPosition = 1f))
    }

    @Test
    fun thumbGeometryUsesFixedLengthAndClampsOutOfRangeInputs() {
        assertEquals(96f, libraryScrollbarThumbLength(100f, 96f))
        assertEquals(50f, libraryScrollbarThumbLength(50f, 96f))
        assertEquals(0f, libraryScrollbarThumbLength(100f, Float.NaN))
        assertEquals(0f, libraryScrollbarThumbLength(100f, -1f))
        assertEquals(0f, libraryScrollbarThumbLength(0f, 96f))
        assertEquals(4f, libraryScrollbarThumbOffset(100f, 96f, 2f))
        assertEquals(0f, libraryScrollbarThumbOffset(100f, 96f, Float.NaN))
        assertEquals(1f, libraryScrollbarPositionForThumbTop(80f, 100f, 96f))
        assertEquals(0f, libraryScrollbarPositionForThumbTop(Float.NaN, 100f, 96f))
    }

    @Test
    fun targetMappingClampsNonFiniteAndOutOfRangePositions() {
        val metrics = libraryScrollbarMetrics(20, 0, 4)

        assertEquals(0, libraryScrollbarTargetIndex(metrics, Float.NaN))
        assertEquals(0, libraryScrollbarTargetIndex(metrics, -1f))
        assertEquals(15, libraryScrollbarTargetIndex(metrics, 2f))
    }
}
