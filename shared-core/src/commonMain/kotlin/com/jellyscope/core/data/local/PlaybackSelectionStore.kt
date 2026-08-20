// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey

interface PlaybackSelectionStore : AccountScopedClearableStore {
    suspend fun get(key: PlaybackSelectionKey): PlaybackSelection?

    /** Returns rows keyed by item/source for one account in a bounded batch. */
    suspend fun getForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, PlaybackSelection> = emptyMap()

    suspend fun save(
        key: PlaybackSelectionKey,
        selection: PlaybackSelection,
    )

    suspend fun delete(key: PlaybackSelectionKey)

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        clearAccount(accountIdentity.serverId, accountIdentity.userId)
    }

    suspend fun clearAccount(
        serverId: String,
        userId: String,
    )
}
