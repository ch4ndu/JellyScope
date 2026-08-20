// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.PlaybackTimingKey
import com.jellyscope.core.domain.model.PlaybackTimingOffset

interface PlaybackTimingStore : AccountScopedClearableStore {
    suspend fun get(key: PlaybackTimingKey): PlaybackTimingOffset?

    suspend fun save(offset: PlaybackTimingOffset)

    suspend fun delete(key: PlaybackTimingKey)

    override suspend fun clearAccount(accountIdentity: AccountIdentity) {
        clearAccount(accountIdentity.serverId, accountIdentity.userId)
    }

    suspend fun clearAccount(
        serverId: String,
        userId: String,
    )
}
