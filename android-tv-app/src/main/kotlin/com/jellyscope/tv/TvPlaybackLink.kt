// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import android.content.Intent
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger

internal val tvPlaybackLinkLogger = diagnosticLogger(DiagnosticTag.TvPlaybackLink)

internal enum class TvPlaybackLinkRejection { LoginRequired, AccountMismatch, PlayerActive }

internal enum class TvPlaybackLinkKind { Play, Details, WatchNext }

// A parsed instance is one delivery. Equal URIs must still retrigger the one-shot
// effect when a second intent arrives before Compose observes the cleared value.
internal class TvPlaybackLink(
    val kind: TvPlaybackLinkKind,
    val serverId: String,
    val userId: String,
    val itemId: String,
)

// Receiver enforcement copy of android-launcher-app/JellyScopePlaybackContract.kt.
// Keep the wire and identifier rules synchronized with docs/engineering/tv-ux-behaviors.md.
internal fun Intent.playbackLink(): TvPlaybackLink? {
    val uri = data ?: return null
    if (action != Intent.ACTION_VIEW || uri.scheme != "jellyscope") return null
    val kind =
        when (uri.host) {
            "play" -> TvPlaybackLinkKind.Play
            "details" -> TvPlaybackLinkKind.Details
            else -> return null
        }
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
    return TvPlaybackLink(kind = kind, serverId = serverId, userId = userId, itemId = itemId)
}

private fun String.isPlaybackLinkId(): Boolean =
    length in 1..128 &&
        all { character ->
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '-' || character == '_'
        }
