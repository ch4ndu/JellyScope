// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import com.jellyscope.core.data.local.AccountScopedClearableStore
import com.jellyscope.core.domain.model.AccountIdentity

typealias PlaybackSelection = com.jellyscope.core.domain.model.PlaybackSelection

data class PlaybackSelectionSnapshot(
    val itemSelections: Map<String, PlaybackSelection>,
    val sourceSelections: Map<Pair<String, String>, PlaybackSelection>,
)

// Account clearing prevents track and bitrate choices from crossing sessions.
class PlaybackSelectionMemory : AccountScopedClearableStore {
    private val selections = mutableMapOf<SelectionKey, PlaybackSelection>()

    fun selectionFor(
        accountIdentity: AccountIdentity,
        itemId: String,
    ): PlaybackSelection? = selections[SelectionKey(accountIdentity, itemId)]

    fun selectionFor(
        accountIdentity: AccountIdentity,
        itemId: String,
        mediaSourceId: String,
    ): PlaybackSelection? =
        sourceSelectionFor(accountIdentity, itemId, mediaSourceId)
            ?: selections[SelectionKey(accountIdentity, itemId)]

    fun sourceSelectionFor(
        accountIdentity: AccountIdentity,
        itemId: String,
        mediaSourceId: String,
    ): PlaybackSelection? = selections[SelectionKey(accountIdentity, itemId, mediaSourceId)]

    fun snapshotFor(
        accountIdentity: AccountIdentity,
        itemIds: Collection<String>,
    ): PlaybackSelectionSnapshot {
        val requested = itemIds.toSet()
        val itemSelections = mutableMapOf<String, PlaybackSelection>()
        val sourceSelections = mutableMapOf<Pair<String, String>, PlaybackSelection>()
        selections.forEach { (key, selection) ->
            if (key.accountIdentity != accountIdentity || key.itemId !in requested) return@forEach
            if (key.mediaSourceId.isBlank()) {
                itemSelections[key.itemId] = selection
            } else {
                sourceSelections[key.itemId to key.mediaSourceId] = selection
            }
        }
        return PlaybackSelectionSnapshot(
            itemSelections = itemSelections.toMap(),
            sourceSelections = sourceSelections.toMap(),
        )
    }

    fun remember(
        accountIdentity: AccountIdentity,
        itemId: String,
        selection: PlaybackSelection,
    ) {
        rememberInCache(SelectionKey(accountIdentity, itemId), selection)
    }

    fun remember(
        accountIdentity: AccountIdentity,
        itemId: String,
        mediaSourceId: String,
        selection: PlaybackSelection,
    ) {
        if (mediaSourceId.isBlank()) return
        rememberInCache(SelectionKey(accountIdentity, itemId, mediaSourceId), selection)
    }

    override suspend fun clearServerScoped() {
        selections.clear()
    }

    override suspend fun clearServerScoped(serverId: String) {
        selections.keys.removeAll { key -> key.accountIdentity.serverId == serverId }
    }

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        selections.keys.removeAll { key -> key.accountIdentity == accountIdentity }
    }

    private data class SelectionKey(
        val accountIdentity: AccountIdentity,
        val itemId: String,
        val mediaSourceId: String = "",
    )

    private fun rememberInCache(
        key: SelectionKey,
        selection: PlaybackSelection,
    ) {
        if (!selections.containsKey(key) && selections.size >= MAX_CACHED_SELECTIONS) {
            selections.keys.firstOrNull()?.let(selections::remove)
        }
        selections[key] = selection
    }
}

private const val MAX_CACHED_SELECTIONS = 256
