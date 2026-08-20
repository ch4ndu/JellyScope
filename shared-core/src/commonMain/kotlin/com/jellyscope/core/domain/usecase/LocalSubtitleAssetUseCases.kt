// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import kotlinx.coroutines.flow.Flow

class ObserveLocalSubtitleAssetsUseCase(
    private val assetStore: LocalSubtitleAssetStore,
) {
    operator fun invoke(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>> = assetStore.observe(context)
}

class GetLocalSubtitleAssetUseCase(
    private val assetStore: LocalSubtitleAssetStore,
    private val fileStore: LocalSubtitleFileStore,
) {
    suspend operator fun invoke(
        assetId: String,
        expectedContext: LocalSubtitleContext? = null,
    ): LocalSubtitleAsset? {
        val asset = assetStore.get(assetId) ?: return null
        if (expectedContext != null && asset.context() != expectedContext) return null
        if (!fileStore.exists(asset.fileId)) {
            assetStore.delete(asset.id)
            return null
        }
        return asset
    }
}

internal fun LocalSubtitleAsset.context(): LocalSubtitleContext = LocalSubtitleContext(serverId, userId, itemId, mediaSourceId)
