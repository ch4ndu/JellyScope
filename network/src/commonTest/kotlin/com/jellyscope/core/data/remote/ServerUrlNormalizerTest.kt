// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServerUrlNormalizerTest {
    @Test
    fun prependsHttpsWhenSchemeIsMissing() {
        val result = ServerUrlNormalizer.normalize("jellyfin.example")

        assertEquals(
            ServerUrlNormalizationResult.Normalized("https://jellyfin.example"),
            result,
        )
    }

    @Test
    fun trimsWhitespaceAndTrailingSlashes() {
        val result = ServerUrlNormalizer.normalize("  https://jellyfin.example///  ")

        assertEquals(
            ServerUrlNormalizationResult.Normalized("https://jellyfin.example"),
            result,
        )
    }

    @Test
    fun preservesExplicitHttp() {
        val result = ServerUrlNormalizer.normalize("http://192.168.1.10:8096/")

        assertEquals(
            ServerUrlNormalizationResult.Normalized("http://192.168.1.10:8096"),
            result,
        )
    }

    @Test
    fun rejectsEmptyInput() {
        assertIs<ServerUrlNormalizationResult.Invalid>(ServerUrlNormalizer.normalize(" "))
    }

    @Test
    fun rejectsInvalidInput() {
        assertIs<ServerUrlNormalizationResult.Invalid>(ServerUrlNormalizer.normalize("http://"))
    }
}
