// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvGridDpadPolicyTest {
    @Test
    fun heldRemoteRepeatIsConsumedWithoutAdvancing() {
        val decision = decide(eventTimeMs = 150, lastAcceptedAtMs = 100)

        assertTrue(decision.consumed)
        assertNull(decision.acceptedAtMs)
        assertNull(decision.targetIndex)
    }

    @Test
    fun downClampsAColumnPastThePartialLastRowToTheLastItem() {
        val decision = decide(focusedIndex = 3, itemCount = 7, columnCount = 4)

        assertEquals(6, decision.targetIndex)
    }

    @Test
    fun downAtTheLoadedEndRequestsPagingWithoutMovingFocus() {
        val decision = decide(focusedIndex = 7, itemCount = 8, columnCount = 4, hasMore = true)

        assertTrue(decision.loadMore)
        assertNull(decision.targetIndex)
    }

    @Test
    fun upFromTheFirstRowLeavesTraversalToTheOwningGroupBoundary() {
        val decision =
            decide(
                direction = TvGridDpadDirection.Up,
                focusedIndex = 2,
                itemCount = 8,
                columnCount = 4,
            )

        assertFalse(decision.consumed)
    }

    private fun decide(
        direction: TvGridDpadDirection = TvGridDpadDirection.Down,
        eventTimeMs: Long = 200,
        lastAcceptedAtMs: Long = 100,
        focusedIndex: Int = 0,
        itemCount: Int = 12,
        columnCount: Int = 4,
        hasMore: Boolean = false,
    ): TvGridDpadDecision =
        TvGridDpadPolicy.decide(
            direction = direction,
            eventTimeMs = eventTimeMs,
            lastAcceptedAtMs = lastAcceptedAtMs,
            focusedIndex = focusedIndex,
            itemCount = itemCount,
            columnCount = columnCount,
            hasMore = hasMore,
        )
}
