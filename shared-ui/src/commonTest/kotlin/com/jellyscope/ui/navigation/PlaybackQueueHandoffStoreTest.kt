// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackQueueHandoffStoreTest {
    @Test
    fun queueIsSinglePendingAndConsumedOnce() {
        val store = PlaybackQueueHandoffStore()
        val staleKey = store.put(listOf("old"))
        val key = store.put(listOf("movie-1", "movie-2", "movie-1", ""))

        assertEquals(emptyList(), store.take(staleKey))
        assertEquals(listOf("movie-1", "movie-2"), store.take(key))
        assertEquals(emptyList(), store.take(key))
    }

    @Test
    fun clearDropsPendingQueue() {
        val store = PlaybackQueueHandoffStore()
        val key = store.put(listOf("movie-1"))

        store.clear()

        assertEquals(emptyList(), store.take(key))
    }
}
