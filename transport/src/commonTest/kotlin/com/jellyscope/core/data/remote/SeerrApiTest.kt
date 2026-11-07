// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.model.SeerrError
import com.jellyscope.core.domain.model.SeerrLoginMethod
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeerrApiTest {
    @Test
    fun credentialsStayOnValidatedEndpointAndRedirectCannotRotateCookies() =
        runTest {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine.create {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            calls += 1
                            assertEquals("seerr.example", request.url.host)
                            assertTrue(request.url.encodedPath.startsWith("/requests/api/v1/"))
                            assertNull(request.headers[HttpHeaders.Authorization])
                            assertNull(request.headers["X-Api-Key"])
                            assertNull(request.headers["X-API-User"])
                            when (calls) {
                                1 ->
                                    respond(
                                        """{"initialized":true,"mediaServerType":2,"mediaServerLogin":true}""",
                                        headers =
                                            headersOf(
                                                HttpHeaders.SetCookie to
                                                    listOf(
                                                        "connect.sid=session; Path=/; Secure",
                                                        "XSRF-TOKEN=csrf; Path=/; Secure",
                                                        "foreign=leak; Domain=elsewhere.example; Path=/",
                                                        "private=hidden; Path=/different; Secure",
                                                    ),
                                            ),
                                    )
                                2 -> {
                                    assertEquals("connect.sid=session; XSRF-TOKEN=csrf", request.headers[HttpHeaders.Cookie])
                                    assertEquals("csrf", request.headers["X-XSRF-TOKEN"])
                                    respond(
                                        "",
                                        HttpStatusCode.Found,
                                        headersOf(
                                            HttpHeaders.Location to listOf("https://elsewhere.example/steal"),
                                            HttpHeaders.SetCookie to listOf("connect.sid=poison; Path=/; Secure"),
                                        ),
                                    )
                                }
                                else -> {
                                    assertEquals("connect.sid=session; XSRF-TOKEN=csrf", request.headers[HttpHeaders.Cookie])
                                    respond("""{"id":1,"permissions":32,"displayName":"Member"}""")
                                }
                            }
                        }
                    },
                ) { followRedirects = false }
            try {
                val api = SeerrApi(client, Json)
                val endpoint = normalizeSeerrEndpoint("https://seerr.example/requests/")
                val bootstrap = api.publicSettings(SeerrTransportSession(endpoint), "3.5.1")
                assertNull(bootstrap.error)
                val login = api.login(SeerrTransportSession(endpoint, bootstrap.cookies), SeerrLoginMethod.Jellyfin, "member", " secret ")
                assertEquals(SeerrError.RedirectRejected, login.error)
                assertEquals(bootstrap.cookies, login.cookies)
                assertNull(api.me(SeerrTransportSession(endpoint, login.cookies)).error)
                assertEquals(3, calls)
            } finally {
                client.close()
            }
        }
}
