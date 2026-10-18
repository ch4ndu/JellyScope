// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import kotlinx.coroutines.flow.Flow

private val SAFE_LOCAL_SUBTITLE_FILE_ID = Regex("[A-Za-z0-9._-]+")

internal fun requireSafeLocalSubtitleFileId(fileId: String): String {
    require(fileId.matches(SAFE_LOCAL_SUBTITLE_FILE_ID)) { "Invalid local subtitle file id." }
    return fileId
}

interface LocalSubtitleAssetStore {
    fun observe(context: LocalSubtitleContext): Flow<List<LocalSubtitleAsset>>

    fun observePendingSync(): Flow<List<LocalSubtitleAsset>>

    suspend fun get(assetId: String): LocalSubtitleAsset?

    suspend fun findByProviderFile(
        context: LocalSubtitleContext,
        provider: String,
        providerFileId: String,
    ): LocalSubtitleAsset?

    suspend fun upsert(asset: LocalSubtitleAsset)

    suspend fun delete(assetId: String)

    suspend fun all(): List<LocalSubtitleAsset>

    suspend fun clearAll()
}
