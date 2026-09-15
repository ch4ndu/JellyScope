// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import android.content.Intent
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger

internal val tvPlaybackLinkLogger = diagnosticLogger(DiagnosticTag.TvPlaybackLink)

internal data class TvPlaybackLink(
    val serverId: String,
    val userId: String,
    val itemId: String,
)

internal fun Intent.playbackLink(): TvPlaybackLink? {
    val uri = data ?: return null
    if (action != Intent.ACTION_VIEW || uri.scheme != "jellyscope" || uri.host != "play") return null
    val validShape =
        uri.isHierarchical &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.pathSegments.size == 1 &&
            uri.queryParameterNames == setOf("serverId", "userId") &&
            uri.getQueryParameters("serverId").size == 1 &&
            uri.getQueryParameters("userId").size == 1
    if (!validShape) {
        tvPlaybackLinkLogger.i { "stage=playback-link event=rejected reason=invalid-link" }
        return null
    }
    val serverId = uri.getQueryParameter("serverId").orEmpty()
    val userId = uri.getQueryParameter("userId").orEmpty()
    val itemId = uri.pathSegments.single()
    if (listOf(serverId, userId, itemId).any { id -> !id.isPlaybackLinkId() }) {
        tvPlaybackLinkLogger.i { "stage=playback-link event=rejected reason=invalid-link" }
        return null
    }
    tvPlaybackLinkLogger.i { "stage=playback-link event=received" }
    return TvPlaybackLink(serverId = serverId, userId = userId, itemId = itemId)
}

private fun String.isPlaybackLinkId(): Boolean =
    length in 1..128 &&
        all { character ->
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '-' || character == '_'
        }
