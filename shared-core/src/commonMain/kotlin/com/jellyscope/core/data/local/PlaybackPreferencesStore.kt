// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.PlaybackPreferences

interface PlaybackPreferencesStore : ServerScopedClearableStore {
    suspend fun get(serverId: String): PlaybackPreferences

    suspend fun save(
        serverId: String,
        preferences: PlaybackPreferences,
    )

    suspend fun clear(serverId: String)

    override suspend fun clearServerScoped(serverId: String) {
        clear(serverId)
    }
}
