// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.playback.BackendSourceDescriptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.readFully
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OriginalDownloadTransportTest {
    @Test
    fun preflightInitialAndResumeRevalidateAndStreamExactSource() =
        runTest {
            val transferRequests = mutableListOf<CapturedTransferRequest>()
            var policyRequests = 0
            var sourceRequests = 0
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/Users/user-1" -> {
                            policyRequests += 1
                            assertEquals(AUTHORIZATION, request.headers[HttpHeaders.Authorization])
                            respondJson(ALLOWED_USER_JSON)
                        }

                        "/Items/item-1" -> {
                            sourceRequests += 1
                            assertEquals("user-1", request.url.parameters["userId"])
                            assertEquals(AUTHORIZATION, request.headers[HttpHeaders.Authorization])
                            respondJson(itemJson(size = 4L))
                        }

                        "/Videos/item-1/stream" -> {
                            val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
                            transferRequests +=
                                CapturedTransferRequest(
                                    mediaSourceId = request.url.parameters["MediaSourceId"],
                                    static = request.url.parameters["static"],
                                    range = request.headers[HttpHeaders.Range],
                                    ifRange = request.headers[HttpHeaders.IfRange],
                                    authorization = request.headers[HttpHeaders.Authorization],
                                    acceptEncoding = request.headers[HttpHeaders.AcceptEncoding],
                                    requestTimeoutMillis = timeout?.requestTimeoutMillis,
                                    socketTimeoutMillis = timeout?.socketTimeoutMillis,
                                )
                            when (request.headers[HttpHeaders.Range]) {
                                "bytes=0-0" -> respondPartial("a", "bytes 0-0/4")
                                "bytes=0-" -> respondPartial("data", "bytes 0-3/4")
                                "bytes=2-" -> respondPartial("ta", "bytes 2-3/4")
                                else -> error("Unexpected range")
                            }
                        }

                        else -> error("Unexpected request path")
                    }
                }
            val fixture = apiFixture(engine)
            try {
                val preflight =
                    assertIs<OriginalDownloadPreflightResult.Ready>(
                        fixture.api.preflightOriginalDownload(context, "item-1", "source-1"),
                    )
                assertNotNull(preflight.source.backendSource)
                assertEquals(
                    OriginalDownloadSource(
                        itemId = "item-1",
                        mediaSourceId = "source-1",
                        totalBytes = 4L,
                        lastModified = LAST_MODIFIED,
                        backendSource = BackendSourceDescriptor(null, null, null, false),
                    ),
                    preflight.source,
                )

                val initial =
                    assertIs<OriginalDownloadStreamResult.Success<String>>(
                        fixture.api.streamOriginalDownload(context, preflight.source, startByte = 0L) { stream ->
                            assertEquals(0L, stream.startByte)
                            assertEquals(3L, stream.endByteInclusive)
                            stream.close()
                            stream.close()
                            "closed-without-reading"
                        },
                    )
                assertEquals("closed-without-reading", initial.value)

                val resumed =
                    assertIs<OriginalDownloadStreamResult.Success<String>>(
                        fixture.api.streamOriginalDownload(context, preflight.source, startByte = 2L) { stream ->
                            val bytes = ByteArray(2)
                            stream.body.readFully(bytes)
                            bytes.decodeToString()
                        },
                    )
                assertEquals("ta", resumed.value)
            } finally {
                fixture.client.close()
            }

            assertEquals(3, policyRequests)
            assertEquals(3, sourceRequests)
            assertEquals(listOf("bytes=0-0", "bytes=0-", "bytes=2-"), transferRequests.map { it.range })
            assertEquals(listOf(null, LAST_MODIFIED, LAST_MODIFIED), transferRequests.map { it.ifRange })
            transferRequests.forEach { request ->
                assertEquals("source-1", request.mediaSourceId)
                assertEquals("true", request.static)
                assertEquals(AUTHORIZATION, request.authorization)
                assertEquals("identity", request.acceptEncoding)
                assertEquals(Long.MAX_VALUE, request.requestTimeoutMillis)
                assertEquals(120_000L, request.socketTimeoutMillis)
            }
        }

    @Test
    fun missingAuthorizationPolicyOrSourceFailsBeforeStaticTransfer() =
        runTest {
            var networkRequests = 0
            val noCredentialFixture =
                apiFixture(
                    MockEngine {
                        networkRequests += 1
                        error("Network must not be reached")
                    },
                )
            try {
                val missingCredential =
                    noCredentialFixture.api.preflightOriginalDownload(
                        context.copy(accessToken = ""),
                        "item-1",
                        "source-1",
                    )
                assertRejected(missingCredential, OriginalDownloadFailure.AccountUnauthorized)
                assertEquals(0, networkRequests)
            } finally {
                noCredentialFixture.client.close()
            }

            val deniedFixture =
                apiFixture(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/Users/user-1" -> respondJson(DENIED_USER_JSON)
                            else -> error("Source and static requests must not run")
                        }
                    },
                )
            try {
                assertRejected(
                    deniedFixture.api.preflightOriginalDownload(context, "item-1", "source-1"),
                    OriginalDownloadFailure.PermissionDenied,
                )
            } finally {
                deniedFixture.client.close()
            }

            val missingSourceFixture =
                apiFixture(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/Users/user-1" -> respondJson(ALLOWED_USER_JSON)
                            "/Items/item-1" -> respondJson(itemJson(size = 4L, sourceId = "other-source"))
                            else -> error("Static request must not run")
                        }
                    },
                )
            try {
                assertRejected(
                    missingSourceFixture.api.preflightOriginalDownload(context, "item-1", "source-1"),
                    OriginalDownloadFailure.SourceUnavailable,
                )
            } finally {
                missingSourceFixture.client.close()
            }
        }

    @Test
    fun boundedServerSubtitleReadRejectsAfterMaximumPlusOneBytes() =
        runTest {
            val fixture =
                apiFixture(
                    MockEngine { request ->
                        assertEquals("/Videos/item-1/source-1/Subtitles/0/Stream.vtt", request.url.encodedPath)
                        respond(
                            content = "abcd",
                            headers = headersOf(HttpHeaders.ContentType, "text/vtt"),
                        )
                    },
                )
            try {
                kotlin.test.assertFailsWith<JellyfinApiException.PayloadTooLarge> {
                    fixture.api.getSubtitleTextBounded(
                        context = context,
                        itemId = "item-1",
                        mediaSourceId = "source-1",
                        streamIndex = 0,
                        maxBytes = 3,
                    )
                }
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun resumedFullOrMismatchedResponsesFailSourceChangedWithoutConsumingBody() =
        runTest {
            val cases =
                listOf(
                    RejectedResponse(HttpStatusCode.OK, "bytes 0-3/4", LAST_MODIFIED),
                    RejectedResponse(HttpStatusCode.PartialContent, "malformed", LAST_MODIFIED),
                    RejectedResponse(HttpStatusCode.PartialContent, "bytes 2-4/5", LAST_MODIFIED),
                    RejectedResponse(HttpStatusCode.PartialContent, "bytes 2-3/4", "Wed, 22 Oct 2015 07:28:00 GMT"),
                    RejectedResponse(HttpStatusCode.PartialContent, "bytes 2-3/4", "not-a-date"),
                )

            cases.forEach { rejected ->
                var consumerCalled = false
                val fixture =
                    apiFixture(
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/Users/user-1" -> respondJson(ALLOWED_USER_JSON)
                                "/Items/item-1" -> respondJson(itemJson(size = 4L))
                                "/Videos/item-1/stream" ->
                                    respond(
                                        content = "body-that-must-not-be-consumed",
                                        status = rejected.status,
                                        headers =
                                            headersOf(
                                                HttpHeaders.ContentRange to listOf(rejected.contentRange),
                                                HttpHeaders.LastModified to listOf(rejected.lastModified),
                                            ),
                                    )

                                else -> error("Unexpected request")
                            }
                        },
                    )
                try {
                    val result =
                        fixture.api.streamOriginalDownload(
                            context = context,
                            source = OriginalDownloadSource("item-1", "source-1", 4L, LAST_MODIFIED),
                            startByte = 2L,
                        ) {
                            consumerCalled = true
                        }
                    val rejectedResult = assertIs<OriginalDownloadStreamResult.Rejected>(result)
                    assertEquals(OriginalDownloadFailure.SourceChanged, rejectedResult.failure)
                    assertTrue(!consumerCalled)
                } finally {
                    fixture.client.close()
                }
            }
        }

    private val context =
        AuthenticatedRequestContext(
            serverUrl = "https://jellyfin.example",
            userId = "user-1",
            accessToken = "token-1",
        )

    private fun apiFixture(engine: MockEngine): ApiFixture {
        val client = JellyfinClientFactory(enableHttpLogging = true).createDownloadTransfer(engine)
        return ApiFixture(
            api =
                KtorJellyfinApi(
                    client = client,
                    downloadClient = client,
                    authHeaderProvider =
                        object : AuthHeaderProvider {
                            override suspend fun authHeader(token: String?): String = AUTHORIZATION
                        },
                ),
            client = client,
        )
    }

    private fun assertRejected(
        result: OriginalDownloadPreflightResult,
        expectedFailure: OriginalDownloadFailure,
    ) {
        assertEquals(expectedFailure, assertIs<OriginalDownloadPreflightResult.Rejected>(result).failure)
    }
}

private data class ApiFixture(
    val api: KtorJellyfinApi,
    val client: HttpClient,
)

private data class CapturedTransferRequest(
    val mediaSourceId: String?,
    val static: String?,
    val range: String?,
    val ifRange: String?,
    val authorization: String?,
    val acceptEncoding: String?,
    val requestTimeoutMillis: Long?,
    val socketTimeoutMillis: Long?,
)

private data class RejectedResponse(
    val status: HttpStatusCode,
    val contentRange: String,
    val lastModified: String,
)

private fun MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

private fun MockRequestHandleScope.respondPartial(
    content: String,
    contentRange: String,
) = respond(
    content = content,
    status = HttpStatusCode.PartialContent,
    headers =
        headersOf(
            HttpHeaders.ContentRange to listOf(contentRange),
            HttpHeaders.LastModified to listOf(LAST_MODIFIED),
            HttpHeaders.ContentLength to listOf(content.encodeToByteArray().size.toString()),
        ),
)

private fun itemJson(
    size: Long,
    sourceId: String = "source-1",
): String = """{"Id":"item-1","Type":"Movie","IsLive":false,"MediaSources":[{"Id":"$sourceId","Size":$size,"IsInfiniteStream":false}]}"""

private const val AUTHORIZATION = "MediaBrowser Token=\"token-1\""
private const val LAST_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT"
private const val ALLOWED_USER_JSON =
    """{"Id":"user-1","Name":"User","Policy":{"EnableContentDownloading":true}}"""
private const val DENIED_USER_JSON =
    """{"Id":"user-1","Name":"User","Policy":{"EnableContentDownloading":false}}"""
