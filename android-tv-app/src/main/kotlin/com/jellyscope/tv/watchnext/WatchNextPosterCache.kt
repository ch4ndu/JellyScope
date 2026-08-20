// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.watchnext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.core.content.ContextCompat
import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.util.runCatchingCancellable
import com.jellyscope.tv.R
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WatchNextPosterCache(
    context: Context,
    private val imageUrlBuilder: JellyfinImageUrlBuilder,
    private val httpClient: HttpClient,
    private val authHeaderProvider: AuthHeaderProvider,
) {
    private val appContext = context.applicationContext

    suspend fun stagePoster(
        session: Session,
        item: MediaItem,
        stagingDirectory: java.io.File,
    ): StagedPoster? =
        withContext(Dispatchers.IO) {
            val accountIdentity = session.accountIdentity()
            val imageSource = item.posterImageSource()
            val destination = WatchNextContract.posterFile(appContext, accountIdentity, item.id, imageSource)
            val uri = WatchNextContract.posterUri(appContext, accountIdentity, item.id, imageSource)
            if (destination.exists() && destination.length() > 0L) {
                return@withContext StagedPoster(uri = uri, stagedFile = null, destination = destination)
            }

            if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
                return@withContext null
            }

            val imageUrl = imageSource?.let { source -> item.posterSourceUrl(session, source) }
            val stagedFile = java.io.File(stagingDirectory, "${safeStageName(item.id, imageSource)}$POSTER_FILE_EXTENSION")
            val wroteRemote = imageUrl?.let { url -> writeRemotePoster(url, session, stagedFile) } == true
            if (!wroteRemote) {
                writeFallbackPoster(stagedFile, landscape = item.kind == MediaKind.Episode)
            }

            if (stagedFile.exists() && stagedFile.length() > 0L) {
                StagedPoster(uri = uri, stagedFile = stagedFile, destination = destination)
            } else {
                null
            }
        }

    fun commitPoster(stagedPoster: StagedPoster) {
        val stagedFile = stagedPoster.stagedFile ?: return
        val directory = stagedPoster.destination.parentFile ?: return
        if (!directory.exists() && !directory.mkdirs()) {
            return
        }
        if (!stagedFile.renameTo(stagedPoster.destination)) {
            stagedFile.copyTo(stagedPoster.destination, overwrite = true)
            stagedFile.delete()
        }
    }

    private fun MediaItem.posterSourceUrl(
        session: Session,
        source: PosterImageSource,
    ): String =
        imageUrlBuilder.build(
            serverUrl = session.serverUrl,
            itemId = id,
            type = source.type,
            tag = source.tag,
            maxWidth = source.maxWidth,
        )

    private fun MediaItem.posterImageSource(): PosterImageSource? {
        val backdropTag = imageRefs.backdropTag
        val primaryTag = imageRefs.primaryTag
        return when {
            kind == MediaKind.Episode && backdropTag != null ->
                PosterImageSource(JellyfinImageType.Backdrop, backdropTag, THUMB_WIDTH_PX)
            primaryTag != null ->
                PosterImageSource(JellyfinImageType.Primary, primaryTag, POSTER_WIDTH_PX)
            backdropTag != null ->
                PosterImageSource(JellyfinImageType.Backdrop, backdropTag, THUMB_WIDTH_PX)
            else -> null
        }
    }

    private suspend fun writeRemotePoster(
        imageUrl: String,
        session: Session,
        file: java.io.File,
    ): Boolean {
        val response =
            runCatchingCancellable {
                httpClient.get(imageUrl) {
                    header(HttpHeaders.Authorization, authHeaderProvider.authHeader(session.accessToken))
                }
            }.getOrElse { return false }

        if (response.status.value !in HTTP_SUCCESS_STATUS_RANGE) {
            return false
        }

        val bytes = runCatchingCancellable { response.bodyAsBytes() }.getOrElse { return false }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        return writeBitmap(bitmap, file)
    }

    private fun writeFallbackPoster(
        file: java.io.File,
        landscape: Boolean,
    ): Boolean {
        val drawable = ContextCompat.getDrawable(appContext, R.drawable.tv_banner) ?: return false
        val width = if (landscape) THUMB_WIDTH_PX else POSTER_WIDTH_PX
        val height = if (landscape) THUMB_HEIGHT_PX else POSTER_HEIGHT_PX
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return writeBitmap(bitmap, file)
    }

    private fun writeBitmap(
        bitmap: Bitmap,
        file: java.io.File,
    ): Boolean {
        val directory = file.parentFile ?: return false
        val tempFile = java.io.File(directory, "${file.name}.tmp")
        return try {
            tempFile.outputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, POSTER_JPEG_QUALITY, outputStream)
            }
            if (tempFile.renameTo(file)) {
                true
            } else {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
                file.exists() && file.length() > 0L
            }
        } finally {
            bitmap.recycle()
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun safeStageName(
        itemId: String,
        source: PosterImageSource?,
    ): String =
        "$itemId|${source?.type?.apiValue ?: "fallback"}|${source?.tag.orEmpty()}"
            .map { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_') character else '_'
            }.joinToString(separator = "")
            .ifBlank { "poster" }
}

internal data class StagedPoster(
    val uri: Uri,
    val stagedFile: java.io.File?,
    val destination: java.io.File,
)
