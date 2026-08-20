// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.jellyscope.core.domain.model.AccountIdentity
import java.io.FileNotFoundException

class WatchNextPosterProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = POSTER_MIME_TYPE

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != READ_MODE) {
            throw FileNotFoundException("Watch Next posters are read-only")
        }

        val providerContext = context ?: throw FileNotFoundException("Watch Next poster provider unavailable")
        val pathSegments = uri.pathSegments
        if (pathSegments.size != 4 || pathSegments.firstOrNull() != POSTER_PATH) {
            throw FileNotFoundException("Watch Next poster path unavailable")
        }
        val serverId = pathSegments.getOrNull(POSTER_SERVER_ID_SEGMENT_INDEX)
        val userId = pathSegments.getOrNull(POSTER_USER_ID_SEGMENT_INDEX)
        val cacheKey =
            pathSegments.getOrNull(POSTER_ITEM_ID_SEGMENT_INDEX)
                ?: throw FileNotFoundException("Watch Next poster item missing")
        val accountIdentity =
            runCatching { AccountIdentity(serverId = serverId.orEmpty(), userId = userId.orEmpty()) }
                .getOrElse { throw FileNotFoundException("Watch Next poster account missing") }
        val file = WatchNextContract.posterFileForCacheKey(providerContext, accountIdentity, cacheKey)

        if (!file.exists() || file.length() <= 0L) {
            throw FileNotFoundException("Watch Next poster unavailable")
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

private const val POSTER_MIME_TYPE = "image/jpeg"
private const val READ_MODE = "r"
private const val POSTER_SERVER_ID_SEGMENT_INDEX = 1
private const val POSTER_USER_ID_SEGMENT_INDEX = 2
private const val POSTER_ITEM_ID_SEGMENT_INDEX = 3
