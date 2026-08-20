// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.action

import com.jellyscope.core.data.local.PlayerBackendOverrideStore
import com.jellyscope.core.domain.playback.PlayerBackend

class SavePlayerBackendOverrideAction(
    private val store: PlayerBackendOverrideStore,
) {
    suspend operator fun invoke(
        serverId: String,
        itemId: String,
        backend: PlayerBackend,
    ) = store.save(serverId, itemId, backend)
}

class DeletePlayerBackendOverrideAction(
    private val store: PlayerBackendOverrideStore,
) {
    suspend operator fun invoke(
        serverId: String,
        itemId: String,
    ) = store.delete(serverId, itemId)
}
