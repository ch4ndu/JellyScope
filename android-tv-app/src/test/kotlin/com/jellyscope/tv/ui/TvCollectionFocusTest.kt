// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import com.jellyscope.tv.ui.focus.TvFocusResolution
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import org.junit.Test
import kotlin.test.assertEquals

class TvCollectionFocusTest {
    @Test
    fun restoresTheExactFocusedItemByStableId() {
        assertEquals(
            2,
            collectionFocusRestoreIndex(
                items = items("a", "b", "c", "d"),
                focusedItemId = "c",
                firstVisibleItemIndex = 0,
            ),
        )
    }

    @Test
    fun missingFocusedItemFallsBackToAClampedVisibleIndex() {
        assertEquals(
            2,
            collectionFocusRestoreIndex(
                items = items("a", "b", "c"),
                focusedItemId = "removed",
                firstVisibleItemIndex = 8,
            ),
        )
        assertEquals(
            0,
            collectionFocusRestoreIndex(
                items = emptyList(),
                focusedItemId = "removed",
                firstVisibleItemIndex = 8,
            ),
        )
    }

    @Test
    fun restoreResolutionFindsExactItemAfterStrippingPathPrefix() {
        assertEquals(
            TvFocusResolution.Exact(1),
            collectionRestoreResolution(
                targetKey = "item:b",
                fallbackIndex = 0,
                itemIds = listOf("a", "b", "c"),
                hasMore = true,
            ),
        )
    }

    @Test
    fun restoreResolutionUsesCoveredFallbackPosition() {
        assertEquals(
            TvFocusResolution.Fallback(2),
            collectionRestoreResolution(
                targetKey = "item:missing",
                fallbackIndex = 2,
                itemIds = listOf("a", "b", "c"),
                hasMore = true,
            ),
        )
    }

    @Test
    fun restoreResolutionDefersUntilUncoveredFallbackPositionLoads() {
        assertEquals(
            TvFocusResolution.Deferred,
            collectionRestoreResolution(
                targetKey = "item:missing",
                fallbackIndex = 8,
                itemIds = listOf("a", "b", "c"),
                hasMore = true,
            ),
        )
    }

    @Test
    fun restoreResolutionClampsFallbackAfterPagingExhausts() {
        assertEquals(
            TvFocusResolution.Fallback(2),
            collectionRestoreResolution(
                targetKey = "item:missing",
                fallbackIndex = 8,
                itemIds = listOf("a", "b", "c"),
                hasMore = false,
            ),
        )
    }
}

private fun items(vararg ids: String): List<MediaCardUi> =
    ids.map { id ->
        MediaCardUi(
            id = id,
            title = id,
            subtitle = null,
            progressFraction = null,
            watched = false,
            unplayedCount = null,
            imageUrl = null,
            kind = MediaCardKind.Movie,
        )
    }
