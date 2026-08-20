// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import com.jellyscope.ui.focus.FocusRestoreRequest
import com.jellyscope.ui.focus.FocusRestoreTarget
import org.junit.Test
import kotlin.test.assertEquals

class TvPersonFocusRestoreTest {
    @Test
    fun exactItemOnFirstPageDoesNotPage() {
        assertEquals(
            FocusRestoreTarget.Exact("item-5", 5),
            personRowRestoreAction(
                request = FocusRestoreRequest(key = "item-5", fallbackSemanticIndex = 30),
                itemIds = itemIds(20),
                hasMore = true,
            ),
        )
    }

    @Test
    fun uncoveredFallbackPositionDefersWhileMorePagesExist() {
        assertEquals(
            FocusRestoreTarget.Deferred,
            personRowRestoreAction(
                request = FocusRestoreRequest(key = "missing", fallbackSemanticIndex = 30),
                itemIds = itemIds(20),
                hasMore = true,
            ),
        )
    }

    @Test
    fun exhaustedPagingClampsTheFallbackPosition() {
        assertEquals(
            FocusRestoreTarget.Fallback("item-19", 19),
            personRowRestoreAction(
                request = FocusRestoreRequest(key = "missing", fallbackSemanticIndex = 30),
                itemIds = itemIds(20),
                hasMore = false,
            ),
        )
    }

    @Test
    fun coveredFallbackPositionDoesNotPage() {
        assertEquals(
            FocusRestoreTarget.Fallback("item-5", 5),
            personRowRestoreAction(
                request = FocusRestoreRequest(key = "missing", fallbackSemanticIndex = 5),
                itemIds = itemIds(20),
                hasMore = true,
            ),
        )
    }

    private fun itemIds(count: Int): List<String> = List(count) { index -> "item-$index" }
}
