// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

interface RecentSearchStore : AccountScopedClearableStore {
    suspend fun add(
        serverId: String,
        query: String,
    )

    suspend fun list(serverId: String): List<String>

    suspend fun clear(serverId: String)

    suspend fun add(
        serverId: String,
        userId: String,
        query: String,
    ): Unit = add(serverId = serverId, query = query)

    suspend fun list(
        serverId: String,
        userId: String,
    ): List<String> = list(serverId)

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

const val RECENT_SEARCH_LIMIT = 8
