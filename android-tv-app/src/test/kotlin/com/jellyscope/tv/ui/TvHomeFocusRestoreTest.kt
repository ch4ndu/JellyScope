// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import com.jellyscope.ui.screen.home.HomeRow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvHomeFocusRestoreTest {
    @Test
    fun interimFocusDoesNotCancelPendingExactItemRestore() {
        val target = TvHomeFocusTarget.Item(HomeRow.ContinueWatching, "target")
        val interim = TvHomeFocusTarget.Item(HomeRow.ContinueWatching, "first-visible")

        assertFalse(shouldExpireHomeRestore(target, interim, restoreConsumed = false))
        assertTrue(shouldExpireHomeRestore(target, interim, restoreConsumed = true))
    }

    @Test
    fun exactTargetNeverExpiresAndMissingTargetDoes() {
        val target = TvHomeFocusTarget.Item(HomeRow.NextUp, "episode")

        assertFalse(shouldExpireHomeRestore(target, target, restoreConsumed = true))
        assertTrue(shouldExpireHomeRestore(null, target, restoreConsumed = false))
    }

    @Test
    fun restorePrefersStableIdThenPreviousPositionThenFirstItem() {
        val items = listOf("first", "second", "third")

        assertEquals(2, homeRestoreItemIndex(items, targetItemId = "third", preferredIndex = 0))
        assertEquals(1, homeRestoreItemIndex(items, targetItemId = "gone", preferredIndex = 1))
        assertEquals(0, homeRestoreItemIndex(items, targetItemId = "gone", preferredIndex = 8))
        assertNull(homeRestoreItemIndex(emptyList(), targetItemId = "gone", preferredIndex = 1))
    }

    @Test
    fun semanticPositionMapsAcrossOptionalLoadingPlaceholderAndViewAll() {
        assertEquals(2, homeLazySlotIndex(2, hasLeadingLoadingPlaceholder = false))
        assertEquals(3, homeLazySlotIndex(2, hasLeadingLoadingPlaceholder = true))

        val contentCount = 5
        assertEquals(5, homeLazySlotIndex(contentCount, hasLeadingLoadingPlaceholder = false))
        assertEquals(6, homeLazySlotIndex(contentCount, hasLeadingLoadingPlaceholder = true))
    }
}
