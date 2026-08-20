// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

internal class PlaybackQueueHandoffStore {
    private var nextKey = 0L
    private var pending: PendingQueue? = null

    fun put(itemIds: List<String>): String {
        val key = (++nextKey).toString()
        pending = PendingQueue(key, itemIds.filter(String::isNotBlank).distinct())
        return key
    }

    fun take(key: String?): List<String> {
        val current = pending ?: return emptyList()
        if (key == null || current.key != key) return emptyList()
        pending = null
        return current.itemIds
    }

    fun clear() {
        pending = null
    }

    private data class PendingQueue(
        val key: String,
        val itemIds: List<String>,
    )
}
