// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.PlaybackSelectionStore
import com.jellyscope.core.domain.model.PlaybackSelection
import com.jellyscope.core.domain.model.PlaybackSelectionKey

class GetPlaybackSelectionUseCase(
    private val store: PlaybackSelectionStore,
) {
    suspend operator fun invoke(key: PlaybackSelectionKey): PlaybackSelection? = store.get(key)
}

class GetPlaybackSelectionsUseCase(
    private val store: PlaybackSelectionStore,
) {
    suspend operator fun invoke(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, PlaybackSelection> = store.getForItems(serverId, userId, itemIds)
}
