// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.LocalSubtitleAssetStore
import com.jellyscope.core.data.local.LocalSubtitleFileStore
import com.jellyscope.core.data.local.SubtitleSelectionKey
import com.jellyscope.core.data.local.SubtitleSelectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class LocalSubtitleStorageReconciler(
    private val assetStore: LocalSubtitleAssetStore,
    private val fileStore: LocalSubtitleFileStore,
    private val selectionStore: SubtitleSelectionStore,
    scope: CoroutineScope,
) {
    init {
        scope.launch { reconcile() }
    }

    internal suspend fun reconcile() {
        val assets = assetStore.all()
        val referencedFiles = mutableSetOf<String>()
        assets.forEach { asset ->
            if (fileStore.exists(asset.fileId)) {
                referencedFiles += asset.fileId
            } else {
                assetStore.delete(asset.id)
                val key = SubtitleSelectionKey(asset.serverId, asset.userId, asset.itemId, asset.mediaSourceId)
                if (selectionStore.get(key) ==
                    com.jellyscope.core.domain.playback.SubtitleSelectionIntent
                        .LocalAsset(asset.id)
                ) {
                    selectionStore.delete(key)
                }
            }
        }
        (fileStore.listFileIds() - referencedFiles).forEach { fileId -> fileStore.delete(fileId) }
    }
}
