// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.tv.MainActivity
import java.io.File
import java.util.UUID

const val EXTRA_ITEM_ID = "ItemId"
const val EXTRA_ACCOUNT_PAYLOAD = "AccountPayload"

internal const val POSTER_PATH = "posters"

private const val POSTER_PROVIDER_AUTHORITY_SUFFIX = ".watchnext.poster"
private const val POSTER_CACHE_DIR = "watch-next-posters"
internal const val POSTER_FILE_EXTENSION = ".jpg"

internal object WatchNextContract {
    fun accountPayload(
        accountIdentity: AccountIdentity,
        itemId: String,
    ): String =
        listOf(accountIdentity.serverId, accountIdentity.userId, itemId)
            .joinToString(PAYLOAD_SEPARATOR, prefix = "$PAYLOAD_PREFIX$PAYLOAD_SEPARATOR") { value -> Uri.encode(value) }

    fun parseAccountPayload(payload: String?): WatchNextPayload? {
        val parts = payload?.split(PAYLOAD_SEPARATOR, limit = 4) ?: return null
        if (parts.size != 4 || parts.first() != PAYLOAD_PREFIX || parts.drop(1).any { part -> part.isBlank() }) {
            return null
        }
        val values = parts.drop(1).map(Uri::decode)
        if (values.any { value -> value.isBlank() }) {
            return null
        }
        return WatchNextPayload(
            serverId = values[0],
            userId = values[1],
            itemId = values[2],
        )
    }

    fun launchIntent(
        context: Context,
        accountIdentity: AccountIdentity,
        itemId: String,
    ): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_ITEM_ID, itemId)
            .putExtra(EXTRA_ACCOUNT_PAYLOAD, accountPayload(accountIdentity = accountIdentity, itemId = itemId))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun posterUri(
        context: Context,
        accountIdentity: AccountIdentity,
        itemId: String,
        imageSource: PosterImageSource? = null,
    ): Uri =
        Uri
            .Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(posterAuthority(context))
            .appendPath(POSTER_PATH)
            .appendPath(safePathSegment(accountIdentity.serverId))
            .appendPath(safePathSegment(accountIdentity.userId))
            .appendPath(posterCacheKey(itemId, imageSource))
            .build()

    fun posterFile(
        context: Context,
        accountIdentity: AccountIdentity,
        itemId: String,
        imageSource: PosterImageSource? = null,
    ): File = File(posterDirectory(context, accountIdentity), "${posterCacheKey(itemId, imageSource)}$POSTER_FILE_EXTENSION")

    fun posterFileForCacheKey(
        context: Context,
        accountIdentity: AccountIdentity,
        cacheKey: String,
    ): File = File(posterDirectory(context, accountIdentity), "${safePathSegment(cacheKey)}$POSTER_FILE_EXTENSION")

    fun posterDirectory(
        context: Context,
        accountIdentity: AccountIdentity,
    ): File =
        File(
            File(
                File(context.applicationContext.cacheDir, POSTER_CACHE_DIR),
                safePathSegment(accountIdentity.serverId),
            ),
            safePathSegment(accountIdentity.userId),
        )

    fun serverPosterDirectory(
        context: Context,
        serverId: String,
    ): File = File(File(context.applicationContext.cacheDir, POSTER_CACHE_DIR), safePathSegment(serverId))

    fun posterRootDirectory(context: Context): File = File(context.applicationContext.cacheDir, POSTER_CACHE_DIR)

    fun stagingDirectory(
        context: Context,
        accountIdentity: AccountIdentity,
    ): File =
        File(
            File(
                File(context.applicationContext.cacheDir, POSTER_STAGING_DIR),
                safePathSegment(accountIdentity.serverId),
            ),
            "${safePathSegment(accountIdentity.userId)}-${UUID.randomUUID()}",
        )

    private fun posterAuthority(context: Context): String = "${context.packageName}$POSTER_PROVIDER_AUTHORITY_SUFFIX"

    private fun safeFileStem(itemId: String): String =
        itemId
            .map { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_') {
                    character
                } else {
                    '_'
                }
            }.joinToString(separator = "")
            .ifBlank { "poster" }

    private fun safePathSegment(value: String): String =
        value
            .map { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
            }.joinToString(separator = "")
            .ifBlank { "value" }

    private fun posterCacheKey(
        itemId: String,
        imageSource: PosterImageSource?,
    ): String {
        if (imageSource == null) return safeFileStem(itemId)
        val sourceKey = "${imageSource.type.apiValue}:${imageSource.tag}"
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest("$itemId|$sourceKey".encodeToByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "${safeFileStem(itemId)}-$digest"
    }
}

internal data class WatchNextPayload(
    val serverId: String,
    val userId: String,
    val itemId: String,
)

private const val PAYLOAD_SEPARATOR = "|"
private const val PAYLOAD_PREFIX = "jellyscope-watch-next"
private const val POSTER_STAGING_DIR = "watch-next-staging"
