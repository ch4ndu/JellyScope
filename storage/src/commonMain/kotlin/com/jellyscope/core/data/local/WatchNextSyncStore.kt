// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

data class WatchNextSyncState(
    val itemId: String,
    val playbackPositionTicks: Long?,
    val played: Boolean,
    val lastSyncedAtEpochMs: Long,
)

interface WatchNextSyncStore : AccountScopedClearableStore {
    suspend fun get(
        serverId: String,
        itemId: String,
    ): WatchNextSyncState?

    suspend fun upsert(
        serverId: String,
        state: WatchNextSyncState,
    )

    suspend fun list(serverId: String): List<WatchNextSyncState>

    suspend fun clear(serverId: String)

    suspend fun get(
        serverId: String,
        userId: String,
        itemId: String,
    ): WatchNextSyncState? = get(serverId = serverId, itemId = itemId)

    suspend fun upsert(
        serverId: String,
        userId: String,
        state: WatchNextSyncState,
    ): Unit = upsert(serverId = serverId, state = state)

    suspend fun list(
        serverId: String,
        userId: String,
    ): List<WatchNextSyncState> = list(serverId)

    suspend fun clear(
        serverId: String,
        userId: String,
    ): Unit = clear(serverId)

    override suspend fun clearAccount(accountIdentity: com.jellyscope.core.domain.model.AccountIdentity) {
        clear(accountIdentity.serverId, accountIdentity.userId)
    }

    override suspend fun clearServerScoped(serverId: String) {
        clear(serverId)
    }
}
