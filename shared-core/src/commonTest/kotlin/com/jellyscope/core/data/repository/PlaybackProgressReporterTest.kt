// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.data.remote.KtorJellyfinApi
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.PlaybackPlan
import com.jellyscope.core.domain.playback.PlaybackProgressEvent
import com.jellyscope.core.domain.playback.ProgressReportingPolicy
import com.jellyscope.core.domain.playback.StreamMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackProgressReporterTest {
    @Test
    fun mapsPlaybackReportsToJellyfinSessionPayloads() =
        runTest {
            val requests = mutableListOf<CapturedRequest>()
            val reporter =
                DefaultPlaybackProgressReporter(
                    jellyfinApi =
                        KtorJellyfinApi(
                            client =
                                HttpClient(
                                    MockEngine { request ->
                                        requests +=
                                            CapturedRequest(
                                                path = request.url.encodedPath,
                                                method = request.method,
                                                authHeader = request.headers[HttpHeaders.Authorization],
                                                body = request.body.asText(),
                                            )
                                        respondJson("{}")
                                    },
                                ) {
                                    install(ContentNegotiation) {
                                        json(
                                            Json {
                                                ignoreUnknownKeys = true
                                                explicitNulls = false
                                            },
                                        )
                                    }
                                },
                            authHeaderProvider =
                                object : AuthHeaderProvider {
                                    override suspend fun authHeader(token: String?) = "MediaBrowser Token=\"$token\""
                                },
                        ),
                )

            reporter.reportStart(session, plan, playSessionId = "play-session-1", positionMs = 1_000L)
            reporter.reportProgress(
                session = session,
                plan = plan,
                playSessionId = "play-session-1",
                positionMs = 2_000L,
                isPaused = true,
                eventName = PlaybackProgressEvent.Pause,
            )
            reporter.reportStopped(session, plan, playSessionId = "play-session-1", positionMs = 3_000L)

            assertEquals(
                listOf(
                    "/Sessions/Playing",
                    "/Sessions/Playing/Progress",
                    "/Sessions/Playing/Stopped",
                ),
                requests.map { request -> request.path },
            )
            assertTrue(requests.all { request -> request.method == HttpMethod.Post })
            assertTrue(requests.all { request -> request.authHeader == "MediaBrowser Token=\"token-1\"" })

            assertTrue(requests[0].body.contains(""""ItemId":"item-1""""))
            assertTrue(requests[0].body.contains(""""MediaSourceId":"source-1""""))
            assertTrue(requests[0].body.contains(""""PositionTicks":10000000"""))
            assertTrue(requests[0].body.contains(""""PlayMethod":"DirectPlay""""))
            assertTrue(requests[0].body.contains(""""PlaySessionId":"play-session-1""""))
            assertTrue(requests[0].body.contains(""""CanSeek":true"""))

            assertTrue(requests[1].body.contains(""""PositionTicks":20000000"""))
            assertTrue(requests[1].body.contains(""""IsPaused":true"""))
            assertTrue(requests[1].body.contains(""""EventName":"Pause""""))
            assertTrue(requests[1].body.contains(""""PlayMethod":"DirectPlay""""))

            assertTrue(requests[2].body.contains(""""PositionTicks":30000000"""))
            assertTrue(requests[2].body.contains(""""PlaySessionId":"play-session-1""""))
        }

    @Test
    fun mapsTranscodeStreamModeToPlaybackProgressPlayMethod() =
        runTest {
            val requests = mutableListOf<CapturedRequest>()
            val reporter =
                DefaultPlaybackProgressReporter(
                    jellyfinApi =
                        KtorJellyfinApi(
                            client =
                                HttpClient(
                                    MockEngine { request ->
                                        requests +=
                                            CapturedRequest(
                                                path = request.url.encodedPath,
                                                method = request.method,
                                                authHeader = request.headers[HttpHeaders.Authorization],
                                                body = request.body.asText(),
                                            )
                                        respondJson("{}")
                                    },
                                ) {
                                    install(ContentNegotiation) {
                                        json(
                                            Json {
                                                ignoreUnknownKeys = true
                                                explicitNulls = false
                                            },
                                        )
                                    }
                                },
                            authHeaderProvider =
                                object : AuthHeaderProvider {
                                    override suspend fun authHeader(token: String?) = "MediaBrowser Token=\"$token\""
                                },
                        ),
                )

            reporter.reportProgress(
                session = session,
                plan = plan.copy(streamMode = StreamMode.Transcode),
                playSessionId = "play-session-1",
                positionMs = 2_000L,
                isPaused = false,
                eventName = PlaybackProgressEvent.TimeUpdate,
            )

            assertTrue(requests.single().body.contains(""""PlayMethod":"Transcode""""))
        }
}

private data class CapturedRequest(
    val path: String,
    val method: HttpMethod,
    val authHeader: String?,
    val body: String,
)

private fun OutgoingContent.asText(): String =
    when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> toString()
    }

private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private val plan =
    PlaybackPlan(
        itemId = "item-1",
        mediaSourceId = "source-1",
        startPositionMs = 0L,
        streamMode = StreamMode.DirectPlay,
        streamUrl = "https://jellyfin.example/Videos/item-1/stream?static=true",
        progressReportingPolicy = ProgressReportingPolicy(10_000L),
    )

private val session =
    Session(
        serverUrl = "https://jellyfin.example",
        serverId = "server-1",
        serverName = "Home Jellyfin",
        userId = "user-1",
        userName = "Demo User",
        accessToken = "token-1",
        deviceId = "device-1",
    )
