// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.launcher

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

/**
 * Public handoff contract v1, vendored here because the two APKs are independent.
 *
 * The v1 wire is deliberately unversioned: ACTION_VIEW with one item path segment
 * and exactly serverId/userId parameters. A version parameter would be rejected.
 * Keep the receiver enforcement copy in android-tv-app/TvPlaybackLink.kt and the
 * Playback Links contract in docs/engineering/tv-ux-behaviors.md synchronized.
 * A successful dispatch is a handoff, not a playback acknowledgement.
 */
internal object JellyScopePlaybackContract {
    const val VERSION = 1
    const val PLAYER_PACKAGE = "com.udnahc.jellyscope"
    const val PLAYER_ACTIVITY = "com.jellyscope.tv.MainActivity"
    const val SCHEME = "jellyscope"
    const val AUTHORITY = "play"
    const val SERVER_ID_PARAMETER = "serverId"
    const val USER_ID_PARAMETER = "userId"

    fun isValidIdentifier(value: String): Boolean =
        value.length in 1..128 &&
            value.all { character ->
                character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '-' || character == '_'
            }

    fun intent(
        serverId: String,
        userId: String,
        itemId: String,
    ): Intent? {
        if (!isValidIdentifier(serverId) || !isValidIdentifier(userId) || !isValidIdentifier(itemId)) return null
        val uri =
            Uri
                .Builder()
                .scheme(SCHEME)
                .authority(AUTHORITY)
                .appendPath(itemId)
                .appendQueryParameter(SERVER_ID_PARAMETER, serverId)
                .appendQueryParameter(USER_ID_PARAMETER, userId)
                .build()
        return Intent(Intent.ACTION_VIEW, uri)
            .setComponent(ComponentName(PLAYER_PACKAGE, PLAYER_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
