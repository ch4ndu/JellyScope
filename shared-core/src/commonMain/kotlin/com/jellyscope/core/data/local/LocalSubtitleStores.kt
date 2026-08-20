// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.LocalSubtitleAsset
import com.jellyscope.core.domain.model.LocalSubtitleContext
import kotlinx.coroutines.flow.Flow

interface LocalSubtitleFileStore {
    suspend fun writeAtomically(
        fileId: String,
        bytes: ByteArray,
    )

    suspend fun read(fileId: String): ByteArray?

    /**
     * Reads at most [maxBytes] and rejects a larger installed asset before allocating its full
     * contents.  The default keeps existing test/store implementations source-compatible; the
     * platform stores override it with a length-checked streaming read.
     */
    suspend fun readBounded(
        fileId: String,
        maxBytes: Int,
    ): ByteArray? {
        require(maxBytes >= 0) { "Maximum subtitle bytes must be non-negative." }
        val bytes = read(fileId) ?: return null
        if (bytes.size > maxBytes) throw LocalSubtitlePayloadTooLargeException
        return bytes
    }

    suspend fun exists(fileId: String): Boolean

    suspend fun delete(fileId: String)

    suspend fun listFileIds(): Set<String>

    /**
     * Resolves the stored path for [fileId], or `null` when no file is installed.
     *
     * Deliberately synchronous: it performs one bounded path-existence check —
     * a single stat, no network and no byte transfer — so the platform players'
     * non-suspending `prepare` paths may call it directly. The suspending
     * operations above own their own IO dispatch; this one does not need it.
     * If resolution ever becomes non-trivial, this is the seam to revisit.
     */
    fun resolvePath(fileId: String): String?
}

/** Payload-free distinction used by the bounded Original sidecar admission path. */
data object LocalSubtitlePayloadTooLargeException : IllegalArgumentException(
    "Local subtitle asset exceeds the bounded download sidecar size.",
)

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
