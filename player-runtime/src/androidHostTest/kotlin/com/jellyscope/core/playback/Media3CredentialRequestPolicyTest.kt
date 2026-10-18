// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.playback

import com.jellyscope.core.security.CredentialOriginGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class Media3CredentialRequestPolicyTest {
    private val policy =
        Media3CredentialRequestPolicy(
            credentialOriginGuard = CredentialOriginGuard("https://jellyfin.example:443/base"),
            authorizationHeader = "current-authorization",
        )

    @Test
    fun sameOriginInitialRequestIsSanitizedAndReceivesCurrentAuthorization() {
        val request =
            policy.resolve(
                resourceUrl = "https://jellyfin.example/base/video?api_key=stale&part=1",
                requestHeaders = listOf("Range" to "bytes=10-", "authorization" to "inherited"),
            )

        assertEquals("https://jellyfin.example/base/video?part=1", request.sanitizedUrl)
        assertEquals(listOf("Range" to "bytes=10-", "Authorization" to "current-authorization"), request.headers)
    }

    @Test
    fun sameOriginRedirectReplacesEveryInheritedCredentialHeaderSpelling() {
        val request =
            policy.resolve(
                resourceUrl = "HTTPS://JELLYFIN.EXAMPLE/base/redirected?TOKEN=stale",
                requestHeaders =
                    listOf(
                        "AUTHORIZATION" to "old-authorization",
                        "x-emby-token" to "old-emby",
                        "X-MEDIABROWSER-TOKEN" to "old-media-browser",
                        "Accept" to "video/*",
                    ),
            )

        assertEquals("HTTPS://JELLYFIN.EXAMPLE/base/redirected", request.sanitizedUrl)
        assertEquals(listOf("Accept" to "video/*", "Authorization" to "current-authorization"), request.headers)
    }

    @Test
    fun untrustedRedirectsLoseInheritedCredentialsAndKeepSafeHeaders() {
        val redirects =
            listOf(
                "https://other.example/base/video?api_key=stale",
                "https://jellyfin.example:444/base/video?token=stale",
                "http://jellyfin.example/base/video?access_token=stale",
            )

        redirects.forEach { redirect ->
            val request =
                policy.resolve(
                    resourceUrl = redirect,
                    requestHeaders =
                        listOf(
                            "Authorization" to "inherited",
                            "x-Emby-token" to "inherited",
                            "X-MediaBrowser-Token" to "inherited",
                            "Range" to "bytes=20-",
                        ),
                )

            assertFalse(request.sanitizedUrl.contains("stale"), redirect)
            assertEquals(listOf("Range" to "bytes=20-"), request.headers, redirect)
        }
    }

    @Test
    fun localResourceKeepsItsUrlAndSafeHeadersButNeverCredentials() {
        val request =
            policy.resolve(
                resourceUrl = "file:///tmp/subtitle.srt?token=local-name",
                requestHeaders = listOf("Authorization" to "inherited", "Cache-Control" to "no-cache"),
            )

        assertEquals("file:///tmp/subtitle.srt?token=local-name", request.sanitizedUrl)
        assertEquals(listOf("Cache-Control" to "no-cache"), request.headers)
    }
}
