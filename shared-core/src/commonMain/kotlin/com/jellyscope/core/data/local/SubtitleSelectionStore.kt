// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.playback.SubtitleSelectionIntent

data class SubtitleSelectionKey(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val mediaSourceId: String,
)

interface SubtitleSelectionStore : AccountScopedClearableStore {
    suspend fun get(key: SubtitleSelectionKey): SubtitleSelectionIntent?

    /**
     * Bulk read for many items in one round trip. Returns a map keyed by
     * (itemId, mediaSourceId) — `serverId`/`userId` are fixed by the query args.
     * Absent entries mean "no stored selection" for that pair. Defaults to empty
     * (the Room-backed store overrides with a real batched query); non-Room/fake
     * stores fall back to "no pre-seeded selection", which is safe (playback
     * re-resolves subtitles independently).
     */
    suspend fun getForItems(
        serverId: String,
        userId: String,
        itemIds: List<String>,
    ): Map<Pair<String, String>, SubtitleSelectionIntent> = emptyMap()

    suspend fun save(
        key: SubtitleSelectionKey,
        selection: SubtitleSelectionIntent,
    )

    suspend fun delete(key: SubtitleSelectionKey)

    suspend fun clearLocalAssetSelections() = Unit

    override suspend fun clearAccount(accountIdentity: com.jellyscope.core.domain.model.AccountIdentity) {
        clearNonLocalAccountSelections(accountIdentity.serverId, accountIdentity.userId)
    }

    suspend fun clearNonLocalAccountSelections(
        serverId: String,
        userId: String,
    ) = Unit
}
