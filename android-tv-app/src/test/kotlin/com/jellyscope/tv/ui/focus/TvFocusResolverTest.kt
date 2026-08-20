// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui.focus

import org.junit.Test
import kotlin.test.assertEquals

class TvFocusResolverTest {
    @Test
    fun exactStableKeyWinsOverPositionHint() {
        assertEquals(
            TvFocusResolution.Exact(2),
            TvFocusResolver.resolveItem(
                targetKey = "c",
                fallbackIndex = 0,
                itemKeys = listOf("a", "b", "c"),
                readiness = TvFocusContentReadiness.Ready,
                hasMore = false,
            ),
        )
    }

    @Test
    fun missingKeyFallsBackToClampedPositionInsideTheSameScope() {
        assertEquals(
            TvFocusResolution.Fallback(2),
            TvFocusResolver.resolveItem(
                targetKey = "removed",
                fallbackIndex = 8,
                itemKeys = listOf("a", "b", "c"),
                readiness = TvFocusContentReadiness.Ready,
                hasMore = false,
            ),
        )
    }

    @Test
    fun loadingOrUncoveredPagingPositionDefersFallback() {
        assertEquals(
            TvFocusResolution.Deferred,
            TvFocusResolver.resolveItem(
                targetKey = "later",
                fallbackIndex = 5,
                itemKeys = listOf("a", "b"),
                readiness = TvFocusContentReadiness.Loading,
                hasMore = true,
            ),
        )
        assertEquals(
            TvFocusResolution.Deferred,
            TvFocusResolver.resolveItem(
                targetKey = "later",
                fallbackIndex = 5,
                itemKeys = listOf("a", "b"),
                readiness = TvFocusContentReadiness.Ready,
                hasMore = true,
            ),
        )
    }
}
