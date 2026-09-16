// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.data.local.DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES
import com.jellyscope.core.data.local.DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES
import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.OfflineArtworkReference
import com.jellyscope.core.domain.model.OfflineArtworkRole
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

/** Bounded, authenticated image-byte transport used only by the retained download writer. */
internal class DownloadArtworkTransport(
    private val downloadClient: HttpClient,
    private val authHeaderProvider: AuthHeaderProvider,
    private val imageUrlBuilder: JellyfinImageUrlBuilder = JellyfinImageUrlBuilder(),
) {
    suspend fun fetch(
        context: AuthenticatedRequestContext,
        reference: OfflineArtworkReference,
    ): ByteArray? {
        val authorization = authHeaderProvider.authHeader(context.accessToken)
        return downloadClient
            .prepareGet(
                imageUrlBuilder.build(
                    serverUrl = context.serverUrl,
                    itemId = reference.owningItemId,
                    type = reference.role.toJellyfinImageType(),
                    tag = reference.imageTag,
                    maxWidth = reference.role.maxWidth(),
                ),
            ) {
                header(HttpHeaders.Authorization, authorization)
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute null
                val channel = response.bodyAsChannel()
                val chunk = ByteArray(DOWNLOAD_ARTIFACT_MAX_WRITE_CHUNK_BYTES)
                val chunks = mutableListOf<ByteArray>()
                var totalBytes = 0
                while (true) {
                    val maximumRead =
                        minOf(
                            chunk.size,
                            DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES - totalBytes + 1,
                        )
                    val read = channel.readAvailable(chunk, 0, maximumRead)
                    if (read < 0) break
                    if (read == 0) continue
                    val nextSize = totalBytes + read
                    if (nextSize > DOWNLOAD_PRESENTATION_IMAGE_MAX_BYTES) return@execute null
                    chunks += chunk.copyOf(read)
                    totalBytes = nextSize
                }
                if (totalBytes == 0) return@execute null
                ByteArray(totalBytes).also { bytes ->
                    var offset = 0
                    chunks.forEach { part ->
                        part.copyInto(bytes, destinationOffset = offset)
                        offset += part.size
                    }
                }
            }
    }

    private fun OfflineArtworkRole.toJellyfinImageType(): JellyfinImageType =
        when (this) {
            OfflineArtworkRole.Poster -> JellyfinImageType.Primary
            OfflineArtworkRole.Backdrop -> JellyfinImageType.Backdrop
            OfflineArtworkRole.Logo -> JellyfinImageType.Logo
        }

    private fun OfflineArtworkRole.maxWidth(): Int =
        when (this) {
            OfflineArtworkRole.Poster -> 720
            OfflineArtworkRole.Backdrop -> 1280
            OfflineArtworkRole.Logo -> 900
        }
}
