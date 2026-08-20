// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import com.jellyscope.core.domain.model.Session
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AuthenticatedImageRequestTest {
    @Test
    fun cacheKeySeparatesAccountsAndDecodeSizesWithoutCredentials() {
        val first = session(serverId = "server-1", userId = "user-1", token = "secret-token-1")
        val sibling = session(serverId = "server-1", userId = "user-2", token = "secret-token-2")
        val url = "https://images.example/Items/item-1/Images/Primary"

        val firstPoster = authenticatedImageCacheKey(url, first, ImageDecode.Poster)
        val siblingPoster = authenticatedImageCacheKey(url, sibling, ImageDecode.Poster)
        val firstThumb = authenticatedImageCacheKey(url, first, ImageDecode.Thumb)

        assertNotEquals(firstPoster, siblingPoster)
        assertNotEquals(firstPoster, firstThumb)
        assertFalse("secret-token-1" in firstPoster)
        assertFalse("Authorization" in firstPoster)
    }
}

private fun session(
    serverId: String,
    userId: String,
    token: String,
): Session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = serverId,
        serverName = "Server",
        userId = userId,
        userName = "User",
        accessToken = token,
        deviceId = "device-1",
    )
