// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The shared Jellyfin/Coil client authenticates only via the Authorization header.
// These tests lock in Ktor's built-in HttpRedirect behavior we rely on: the header
// is preserved on a same-origin redirect and dropped on a cross-authority redirect.
class JellyfinClientFactoryRedirectTest {
    @Test
    fun sameOriginRedirectPreservesAuthorization() =
        runTest {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    assertEquals(AUTHORIZATION, request.headers[HttpHeaders.Authorization])
                    if (requestCount == 1) {
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "/base/final"),
                        )
                    } else {
                        respond("ok")
                    }
                }
            val client = JellyfinClientFactory().create(engine)

            try {
                client.get("https://jellyfin.example/base/start") {
                    header(HttpHeaders.Authorization, AUTHORIZATION)
                }
                assertEquals(2, requestCount)
            } finally {
                client.close()
            }
        }

    @Test
    fun crossAuthorityRedirectDropsAuthorization() =
        runTest {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    if (requestCount == 1) {
                        assertEquals(AUTHORIZATION, request.headers[HttpHeaders.Authorization])
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "https://attacker.example/collect"),
                        )
                    } else {
                        assertNull(request.headers[HttpHeaders.Authorization])
                        respond("ok")
                    }
                }
            val client = JellyfinClientFactory().create(engine)

            try {
                client.get("https://jellyfin.example/base/start") {
                    header(HttpHeaders.Authorization, AUTHORIZATION)
                }
                assertEquals(2, requestCount)
            } finally {
                client.close()
            }
        }

    private companion object {
        const val AUTHORIZATION = "MediaBrowser Token=redacted-test-value"
    }
}
