// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialOriginGuardTest {
    private val guard = CredentialOriginGuard("https://jellyfin.example:443/base")

    @Test
    fun sameOriginAbsoluteAndServerRelativeUrlsAreAllowed() {
        assertTrue(guard.mayAttachCredentials("https://jellyfin.example/base/Videos/1/stream"))
        assertTrue(guard.mayAttachCredentials("HTTPS://JELLYFIN.EXAMPLE/base/Images/1"))
        assertTrue(guard.mayAttachCredentials("/Videos/1/subtitles/2.vtt"))
        assertTrue(guard.mayAttachCredentials("Videos/1/subtitles/2.vtt"))
    }

    @Test
    fun crossOriginDowngradeAndUntrustedAuthoritiesAreBlocked() {
        assertFalse(guard.mayAttachCredentials("https://attacker.example/base/Videos/1"))
        assertFalse(guard.mayAttachCredentials("https://jellyfin.example:444/base/Videos/1"))
        assertFalse(guard.mayAttachCredentials("http://jellyfin.example/base/Videos/1"))
        assertFalse(guard.mayAttachCredentials("https://user@jellyfin.example/base/Videos/1"))
        assertFalse(guard.mayAttachCredentials("https://jellyfín.example/base/Videos/1"))
        assertFalse(guard.mayAttachCredentials("https://xn--jellyfn-2za.example/base/Videos/1"))
    }

    @Test
    fun basePathPrefixIsRequiredOnAbsoluteUrls() {
        assertTrue(guard.mayAttachCredentials("https://jellyfin.example/base"))
        assertTrue(guard.mayAttachCredentials("https://jellyfin.example/base/Videos/1"))
        assertFalse(guard.mayAttachCredentials("https://jellyfin.example/base-other/Videos/1"))
        assertFalse(guard.mayAttachCredentials("https://jellyfin.example/Videos/1"))
        assertFalse(guard.mayAttachCredentials("https://jellyfin.example/base/../outside"))
    }

    @Test
    fun authQueryParamsAreStrippedWithoutChangingOtherQueryOrFragmentParts() {
        assertEquals(
            "https://jellyfin.example/base/subtitle.vtt?language=eng#track",
            CredentialOriginGuard.stripAuthQueryParams(
                "https://jellyfin.example/base/subtitle.vtt?api_key=secret&language=eng&TOKEN=other#track",
            ),
        )
        assertEquals(
            "https://jellyfin.example/base/subtitle.vtt#track",
            CredentialOriginGuard.stripAuthQueryParams(
                "https://jellyfin.example/base/subtitle.vtt?access_token=secret#track",
            ),
        )
    }

    @Test
    fun jellyfinApiKeySpellingIsStripped() {
        // Jellyfin emits `ApiKey` (capitalized, no underscore) in generated subtitle
        // DeliveryUrls; it must be stripped like `api_key`.
        assertEquals(
            "https://jellyfin.example/base/Videos/id/src/Subtitles/2/0/Stream.srt?language=eng",
            CredentialOriginGuard.stripAuthQueryParams(
                "https://jellyfin.example/base/Videos/id/src/Subtitles/2/0/Stream.srt?ApiKey=secret&language=eng",
            ),
        )
    }

    @Test
    fun encodedAndMalformedQueryNamesAreClassifiedWithoutDecodingValues() {
        assertEquals(
            "https://jellyfin.example/base/subtitle.vtt?language=api%5Fkey&empty=&=value#track",
            CredentialOriginGuard.stripAuthQueryParams(
                "https://jellyfin.example/base/subtitle.vtt?api%5Fkey=secret&language=api%5Fkey&bad%=drop&empty=&=value#track",
            ),
        )
    }

    @Test
    fun authorizedUrlIsSameOriginAndContainsExactlyOneCurrentApiKey() {
        assertEquals(
            "https://jellyfin.example/base/Videos/1/stream?language=eng&ApiKey=current",
            guard.authorizedUrl(
                "https://jellyfin.example/base/Videos/1/stream?api_key=old&language=eng&ApiKey=older",
                "current",
            ),
        )
        assertEquals(
            "https://jellyfin.example/base/Videos/1/stream?ApiKey=current",
            guard.authorizedUrl("/Videos/1/stream", "current"),
        )
        assertEquals(null, guard.authorizedUrl("https://attacker.example/base/Videos/1", "current"))
        assertEquals(null, guard.authorizedUrl("https://user@jellyfin.example/base/Videos/1", "current"))
    }

    @Test
    fun resourceDecisionSanitizesTrustedRelativeAndAbsoluteUrlsBeforeAttaching() {
        val cases =
            listOf(
                "/Videos/1/stream?api_key=old&quality=original#video" to
                    "/Videos/1/stream?quality=original#video",
                "Videos/1/subtitles/2.vtt?TOKEN=old" to "Videos/1/subtitles/2.vtt",
                "https://jellyfin.example/base/Videos/1/stream?ApiKey=old&media=1" to
                    "https://jellyfin.example/base/Videos/1/stream?media=1",
            )

        cases.forEach { (candidate, sanitized) ->
            assertEquals(
                CredentialResourceDecision(sanitizedUrl = sanitized, attachCredentials = true),
                guard.decideResourceCredentials(candidate),
                candidate,
            )
        }
    }

    @Test
    fun resourceDecisionSanitizesButNeverTrustsUntrustedHttpResources() {
        val cases =
            listOf(
                "https://attacker.example/base/Videos/1?api_key=old" to
                    "https://attacker.example/base/Videos/1",
                "https://jellyfin.example:444/base/Videos/1?token=old" to
                    "https://jellyfin.example:444/base/Videos/1",
                "http://jellyfin.example/base/Videos/1?access_token=old" to
                    "http://jellyfin.example/base/Videos/1",
                "https://user@jellyfin.example/base/Videos/1?api_key=old" to
                    "https://user@jellyfin.example/base/Videos/1",
                "https://jellyfin.example/base/../outside?api_key=old" to
                    "https://jellyfin.example/base/../outside",
                "https://jellyfín.example/base/Videos/1?api_key=old" to
                    "https://jellyfín.example/base/Videos/1",
                "https://xn--jellyfn-2za.example/base/Videos/1?api_key=old" to
                    "https://xn--jellyfn-2za.example/base/Videos/1",
                "https://[invalid?api_key=old" to "https://[invalid",
                "https://redirect.example/video?ApiKey=old#target" to
                    "https://redirect.example/video#target",
            )

        cases.forEach { (candidate, sanitized) ->
            assertEquals(
                CredentialResourceDecision(sanitizedUrl = sanitized, attachCredentials = false),
                guard.decideResourceCredentials(candidate),
                candidate,
            )
        }
    }

    @Test
    fun resourceDecisionPreservesUnsupportedLocalResourcesWithoutCredentials() {
        assertEquals(
            CredentialResourceDecision(
                sanitizedUrl = "file:///tmp/subtitle.srt?token=local-name",
                attachCredentials = false,
            ),
            guard.decideResourceCredentials("file:///tmp/subtitle.srt?token=local-name"),
        )
        assertEquals(
            CredentialResourceDecision(sanitizedUrl = "not a valid URL", attachCredentials = false),
            guard.decideResourceCredentials("not a valid URL"),
        )
    }
}
