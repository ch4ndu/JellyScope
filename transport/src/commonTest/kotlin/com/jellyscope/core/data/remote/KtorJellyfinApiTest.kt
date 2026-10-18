// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KtorJellyfinApiTest {
    @Test
    fun authenticatedMutationsShareAuthorizationAndMethodHandling() =
        runTest {
            val methods = mutableListOf<HttpMethod>()
            val authorizations = mutableListOf<String>()
            val api =
                apiWith(
                    MockEngine { request ->
                        methods += request.method
                        authorizations += request.headers[HttpHeaders.Authorization].orEmpty()
                        respond("", HttpStatusCode.NoContent)
                    },
                )

            api.markItemPlayed(context, "item-1")
            api.unsetItemFavorite(context, "item-1")

            assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), methods)
            assertEquals(listOf("MediaBrowser Token=\"token-1\"", "MediaBrowser Token=\"token-1\""), authorizations)
        }

    @Test
    fun postClientLogDocumentUsesAuthenticatedPlainTextAndMapsForbidden() =
        runTest {
            var contentType: ContentType? = null
            var authorization = ""
            var body = ""
            val api =
                apiWith(
                    MockEngine { request ->
                        authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                        (request.body as TextContent).also { textContent ->
                            contentType = textContent.contentType
                            body = textContent.text
                        }
                        respond(
                            """{"FileName":"client-logs.txt"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            val result = api.postClientLogDocument(context, "stage=planner event=failed")

            assertEquals("client-logs.txt", result.filename)
            assertEquals(ContentType.Text.Plain.contentType, contentType?.contentType)
            assertEquals(ContentType.Text.Plain.contentSubtype, contentType?.contentSubtype)
            assertEquals("MediaBrowser Token=\"token-1\"", authorization)
            assertEquals("stage=planner event=failed", body)

            val forbiddenApi = apiWith(MockEngine { respond("denied", HttpStatusCode.Forbidden) })
            assertFailsWith<JellyfinApiException.ClientLogUploadDisallowed> {
                forbiddenApi.postClientLogDocument(context, "stage=planner event=failed")
            }
        }

    @Test
    fun getMediaSegmentsReturnsSegmentsFromSuccessfulResponse() =
        runTest {
            val api =
                apiWith(
                    MockEngine {
                        respond(
                            content = """[{"Type":"Intro","StartTicks":0,"EndTicks":900000000}]""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            val segments = api.getMediaSegments(context, "item-1")

            assertEquals(listOf(MediaSegmentDto(type = "Intro", startTicks = 0, endTicks = 900_000_000)), segments)
        }

    @Test
    fun getMediaSegmentsTreatsNotFoundAsNoSegments() =
        runTest {
            // The MediaSegments API only exists on Jellyfin 10.10+; older
            // servers 404 and that must not surface as a reachability error.
            val api = apiWith(MockEngine { respond("Not Found", HttpStatusCode.NotFound) })

            assertEquals(emptyList(), api.getMediaSegments(context, "item-1"))
        }

    @Test
    fun getMediaSegmentsDegradesAllFailuresToEmpty() =
        runTest {
            // Media segments (skip intro/outro markers) are an optional, best-effort
            // feature: ANY fetch failure — server error, auth, other client error, or
            // a transient IO — degrades to "no segments" rather than propagating a
            // (mislabeled) failure that would spam a repository warning on playback.
            val serverErrorApi = apiWith(MockEngine { respond("boom", HttpStatusCode.InternalServerError) })
            assertEquals(emptyList(), serverErrorApi.getMediaSegments(context, "item-1"))

            val unauthorizedApi = apiWith(MockEngine { respond("denied", HttpStatusCode.Unauthorized) })
            assertEquals(emptyList(), unauthorizedApi.getMediaSegments(context, "item-1"))

            val forbiddenApi = apiWith(MockEngine { respond("nope", HttpStatusCode.Forbidden) })
            assertEquals(emptyList(), forbiddenApi.getMediaSegments(context, "item-1"))
        }

    private val context =
        AuthenticatedRequestContext(
            serverUrl = "https://jellyfin.example",
            userId = "user-1",
            accessToken = "token-1",
        )

    private fun apiWith(engine: MockEngine): KtorJellyfinApi =
        KtorJellyfinApi(
            client =
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                },
            authHeaderProvider =
                object : AuthHeaderProvider {
                    override suspend fun authHeader(token: String?): String = "MediaBrowser Token=\"${token.orEmpty()}\""
                },
        )
}
