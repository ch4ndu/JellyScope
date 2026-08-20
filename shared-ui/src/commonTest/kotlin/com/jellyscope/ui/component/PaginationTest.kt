// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaginationTest {
    @Test
    fun shouldLoadMoreWhenReadyAndNearEnd() {
        assertTrue(
            shouldLoadMore(
                itemCount = 20,
                hasMore = true,
                isLoading = false,
                isLoadingMore = false,
                lastVisibleIndex = 12,
                threshold = 8,
            ),
        )
    }

    @Test
    fun shouldLoadMoreRejectsLoadingAndNonTerminalStates() {
        assertFalse(
            shouldLoadMore(
                itemCount = 20,
                hasMore = false,
                isLoading = false,
                isLoadingMore = false,
                lastVisibleIndex = 19,
                threshold = 8,
            ),
        )
        assertFalse(
            shouldLoadMore(
                itemCount = 20,
                hasMore = true,
                isLoading = true,
                isLoadingMore = false,
                lastVisibleIndex = 19,
                threshold = 8,
            ),
        )
        assertFalse(
            shouldLoadMore(
                itemCount = 20,
                hasMore = true,
                isLoading = false,
                isLoadingMore = true,
                lastVisibleIndex = 19,
                threshold = 8,
            ),
        )
        assertFalse(
            shouldLoadMore(
                itemCount = 0,
                hasMore = true,
                isLoading = false,
                isLoadingMore = false,
                lastVisibleIndex = 0,
                threshold = 8,
            ),
        )
        assertFalse(
            shouldLoadMore(
                itemCount = 20,
                hasMore = true,
                isLoading = false,
                isLoadingMore = false,
                lastVisibleIndex = 10,
                threshold = 8,
            ),
        )
    }
}
