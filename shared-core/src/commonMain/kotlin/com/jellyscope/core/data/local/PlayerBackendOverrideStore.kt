// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.playback.PlayerBackend

interface PlayerBackendOverrideStore : ServerScopedClearableStore {
    suspend fun get(
        serverId: String,
        itemId: String,
    ): PlayerBackend?

    suspend fun save(
        serverId: String,
        itemId: String,
        backend: PlayerBackend?,
    )

    suspend fun delete(
        serverId: String,
        itemId: String,
    )
}
