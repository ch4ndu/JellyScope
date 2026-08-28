// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenSubtitlesApiTest {
    @Test
    fun searchSendsConsumerKeyAndEpisodeIdentity() =
        runTest {
            var requestedUrl = ""
            var apiKey: String? = null
            val api =
                OpenSubtitlesApi(
                    apiClient =
                        mockClient(
                            MockEngine { request ->
                                requestedUrl = request.url.toString()
                                apiKey = request.headers["Api-Key"]
                                respondJson(
                                    """{"data":[{"id":"sub-1","attributes":{"language":"en","files":[{"file_id":42,"file_name":"Movie.srt"}]}}]}""",
                                )
                            },
                        ),
                    downloadClient = mockClient(MockEngine { error("unused") }),
                    userAgent = "JellyScope v1",
                )

            val response =
                api.search(
                    "consumer-key",
                    OpenSubtitlesQuery(
                        imdbId = "tt123",
                        seasonNumber = 2,
                        episodeNumber = 3,
                        language = "en",
                    ),
                )

            assertEquals("consumer-key", apiKey)
            assertTrue("imdb_id=123" in requestedUrl)
            assertTrue("season_number=2" in requestedUrl)
            assertTrue("episode_number=3" in requestedUrl)
            assertEquals(
                42,
                response.data
                    .orEmpty()
                    .single()
                    ?.attributes
                    ?.files
                    .orEmpty()
                    .single()
                    ?.fileId,
            )
        }

    @Test
    fun searchFollowsOnlySameOriginCanonicalRedirectWithConsumerKey() =
        runTest {
            var canonicalCalls = 0
            val canonicalApi =
                OpenSubtitlesApi(
                    apiClient =
                        mockClient(
                            MockEngine { request ->
                                canonicalCalls += 1
                                assertEquals("consumer-key", request.headers["Api-Key"])
                                if (canonicalCalls == 1) {
                                    respond(
                                        content = "canonical",
                                        status = HttpStatusCode.MovedPermanently,
                                        headers =
                                            headersOf(
                                                HttpHeaders.Location,
                                                "/api/v1/subtitles?episode_number=3&languages=en&query=example",
                                            ),
                                    )
                                } else {
                                    assertEquals("/api/v1/subtitles", request.url.encodedPath)
                                    respondJson("""{"data":[]}""")
                                }
                            },
                        ),
                    downloadClient = mockClient(MockEngine { error("unused") }),
                    userAgent = "JellyScope v1",
                )

            canonicalApi.search("consumer-key", OpenSubtitlesQuery(title = "Example", language = "en"))
            assertEquals(2, canonicalCalls)

            var unsafeCalls = 0
            val unsafeApi =
                OpenSubtitlesApi(
                    apiClient =
                        mockClient(
                            MockEngine {
                                unsafeCalls += 1
                                respond(
                                    content = "redirect",
                                    status = HttpStatusCode.MovedPermanently,
                                    headers = headersOf(HttpHeaders.Location, "https://example.com/api/v1/subtitles"),
                                )
                            },
                        ),
                    downloadClient = mockClient(MockEngine { error("unused") }),
                    userAgent = "JellyScope v1",
                )

            assertFailsWith<OpenSubtitlesException.UnsafeRedirect> {
                unsafeApi.search("consumer-key", OpenSubtitlesQuery(title = "Example", language = "en"))
            }
            assertEquals(1, unsafeCalls)
        }

    @Test
    fun downloadFollowsOnlyBoundedOpenSubtitlesRedirectsWithoutApiCredentials() =
        runTest {
            var calls = 0
            val downloadClient =
                mockClient(
                    MockEngine { request ->
                        assertNull(request.headers["Api-Key"])
                        calls += 1
                        if (calls == 1) {
                            respond(
                                content = ByteArray(0),
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, "https://dl.opensubtitles.com/final.vtt"),
                            )
                        } else {
                            respond(
                                content = "WEBVTT\n\n".encodeToByteArray(),
                                headers = headersOf(HttpHeaders.ContentLength, "8"),
                            )
                        }
                    },
                )
            val api = OpenSubtitlesApi(mockClient(MockEngine { error("unused") }), downloadClient, "JellyScope v1")

            assertEquals("WEBVTT\n\n", api.download("https://dl.opensubtitles.com/start").decodeToString())
            assertEquals(2, calls)
        }

    @Test
    fun downloadResolvesRelativeRedirectsAgainstCurrentPath() =
        runTest {
            val requestedPaths = mutableListOf<String>()
            val downloadClient =
                mockClient(
                    MockEngine { request ->
                        requestedPaths += request.url.encodedPath
                        if (requestedPaths.size == 1) {
                            respond(
                                content = ByteArray(0),
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, "../final.vtt?format=srt"),
                            )
                        } else {
                            respond(content = "subtitle".encodeToByteArray())
                        }
                    },
                )
            val api = OpenSubtitlesApi(mockClient(MockEngine { error("unused") }), downloadClient, "JellyScope v1")

            assertEquals("subtitle", api.download("https://dl.opensubtitles.com/releases/current/start.vtt").decodeToString())
            assertEquals(
                listOf("/releases/current/start.vtt", "/releases/final.vtt"),
                requestedPaths,
            )
        }

    @Test
    fun downloadRejectsNonOpenSubtitlesHostsBeforeNetworkAccess() =
        runTest {
            val api =
                OpenSubtitlesApi(
                    mockClient(MockEngine { error("unused") }),
                    mockClient(MockEngine { error("network must not be reached") }),
                    "JellyScope v1",
                )

            assertFailsWith<IllegalArgumentException> { api.download("https://127.0.0.1/subtitle.srt") }
            assertFailsWith<IllegalArgumentException> { api.download("https://example.com/subtitle.srt") }
        }

    @Test
    fun apiErrorsAreClassifiedWithoutExposingResponseBodies() =
        runTest {
            val invalidKeyApi =
                OpenSubtitlesApi(
                    mockClient(MockEngine { respond("rejected", HttpStatusCode.Unauthorized) }),
                    mockClient(MockEngine { error("unused") }),
                    "JellyScope v1",
                )
            val rateLimitedApi =
                OpenSubtitlesApi(
                    mockClient(MockEngine { respond("quota", HttpStatusCode.TooManyRequests) }),
                    mockClient(MockEngine { error("unused") }),
                    "JellyScope v1",
                )

            assertFailsWith<OpenSubtitlesException.InvalidApiKey> {
                invalidKeyApi.search("bad-key", OpenSubtitlesQuery(title = "Movie", language = "en"))
            }
            assertFailsWith<OpenSubtitlesException.RateLimited> {
                rateLimitedApi.createDownload("key", "42")
            }
        }

    @Test
    fun malformedJsonAndOversizedDownloadsAreRejected() =
        runTest {
            val malformedApi =
                OpenSubtitlesApi(
                    mockClient(MockEngine { respondJson("not-json") }),
                    mockClient(MockEngine { error("unused") }),
                    "JellyScope v1",
                )
            val oversizedApi =
                OpenSubtitlesApi(
                    mockClient(MockEngine { error("unused") }),
                    mockClient(
                        MockEngine {
                            respond(
                                ByteArray(5 * 1024 * 1024 + 1),
                            )
                        },
                    ),
                    "JellyScope v1",
                )
            val nullableFieldsApi =
                OpenSubtitlesApi(
                    mockClient(
                        MockEngine {
                            respondJson(
                                """
                                {"data":[{"id":"sub-1","attributes":{"language":"en","hearing_impaired":null,
                                "foreign_parts_only":null,"from_trusted":null,
                                "files":[null,{"file_id":42,"file_name":"Movie.srt"}]}}]}
                                """.trimIndent(),
                            )
                        },
                    ),
                    mockClient(MockEngine { error("unused") }),
                    "JellyScope v1",
                )

            val malformedFailure =
                assertFailsWith<Throwable> {
                    malformedApi.search("key", OpenSubtitlesQuery(title = "Movie", language = "en"))
                }
            assertTrue(malformedFailure::class.simpleName?.contains("Json") == true)
            val nullableAttributes =
                nullableFieldsApi
                    .search("key", OpenSubtitlesQuery(imdbId = "tt123", language = "en"))
                    .data
                    .orEmpty()
                    .single()
                    ?.attributes
            assertNull(nullableAttributes?.trusted)
            assertEquals(2, nullableAttributes?.files?.size)
            assertFailsWith<IllegalArgumentException> {
                oversizedApi.download("https://dl.opensubtitles.com/movie.srt")
            }
        }
}

private fun mockClient(engine: MockEngine): HttpClient =
    HttpClient(engine) {
        followRedirects = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

private fun MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
