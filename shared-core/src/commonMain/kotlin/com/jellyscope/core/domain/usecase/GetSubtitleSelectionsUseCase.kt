// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.SubtitleSelectionStore
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent

/**
 * Bulk variant of [GetSubtitleSelectionUseCase]: fetches stored subtitle
 * selections for many items in one round trip, so pre-seeding a whole season's
 * episode strip is a single query instead of N sequential reads. Keyed by
 * (itemId, mediaSourceId).
 */
class GetSubtitleSelectionsUseCase(
    private val store: SubtitleSelectionStore,
) {
    suspend operator fun invoke(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, SubtitleSelectionIntent> =
        if (itemIds.isEmpty()) {
            emptyMap()
        } else {
            store.getForItems(serverId = serverId, userId = userId, itemIds = itemIds)
        }
}
