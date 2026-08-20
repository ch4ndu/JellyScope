// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.remote

import com.jellyscope.core.domain.model.DownloadSubtitleSelection
import com.jellyscope.core.domain.model.estimateFixedDownloadBytes
import com.jellyscope.core.domain.playback.qualityRungs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readFully
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedDownloadTransportTest {
    @Test
    fun subtitleOffRequestsExactFixedHlsWithoutBurnInOrCredentialPersistence() =
        runTest {
            var requestBody = ""
            var resourceAuthorization: String? = null
            var resourceUrl: String? = null
            var cleanupDeviceId: String? = null
            var cleanupPlaySessionId: String? = null
            val api =
                apiWith(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/Users/user-1" -> respondJson(ALLOWED_USER_JSON)
                            "/Items/item-1" -> respondJson(ITEM_JSON)
                            "/Items/item-1/PlaybackInfo" -> {
                                requestBody = (request.body as TextContent).text
                                respondJson(
                                    playbackInfoJson("/Videos/item-1/master.m3u8?SubtitleStreamIndex=99&SubtitleMethod=Encode&ApiKey=old"),
                                )
                            }
                            "/Videos/item-1/master.m3u8" -> {
                                resourceAuthorization = request.headers[HttpHeaders.Authorization]
                                resourceUrl = request.url.toString()
                                respond(
                                    content = FIXED_HLS_RESOURCE_BODY,
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentLength,
                                            FIXED_HLS_RESOURCE_BODY.encodeToByteArray().size.toString(),
                                        ),
                                )
                            }
                            "/Videos/ActiveEncodings" -> {
                                cleanupDeviceId = request.url.parameters["deviceId"]
                                cleanupPlaySessionId = request.url.parameters["playSessionId"]
                                respond(content = "", status = HttpStatusCode.NoContent)
                            }
                            else -> error("Unexpected fixed-download request: ${request.url.encodedPath}")
                        }
                    },
                )
            val request =
                FixedDownloadRequest(
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                    quality = qualityRungs[6],
                    audioStreamIndex = null,
                    subtitleSelection = DownloadSubtitleSelection.Off,
                )

            val result = api.preflightFixedDownload(context, request)
            val source = assertIs<FixedDownloadPreflightResult.Ready>(result).source
            val requestJson = Json.parseToJsonElement(requestBody).jsonObject
            assertEquals(false, requestJson["EnableDirectPlay"]?.jsonPrimitive?.boolean)
            assertEquals(false, requestJson["EnableDirectStream"]?.jsonPrimitive?.boolean)
            assertEquals(true, requestJson["EnableTranscoding"]?.jsonPrimitive?.boolean)
            assertEquals(false, requestJson["AllowAudioStreamCopy"]?.jsonPrimitive?.boolean)
            assertEquals(false, requestJson["AllowVideoStreamCopy"]?.jsonPrimitive?.boolean)
            assertEquals(1, requestJson["AudioStreamIndex"]?.jsonPrimitive?.int)
            assertNull(requestJson["SubtitleStreamIndex"])
            assertNull(requestJson["AlwaysBurnInSubtitleWhenTranscoding"])
            assertEquals(2_000_000L, requestJson["MaxStreamingBitrate"]?.jsonPrimitive?.long)
            assertTrue(requestJson["DeviceProfile"].toString().contains("\"Protocol\":\"hls\""))
            assertFalse(requestJson.toString().contains("ApiKey"))
            assertEquals(
                "https://jellyfin.example/Videos/item-1/master.m3u8?DeviceId=device-1",
                source.transcodingUrl,
            )
            assertEquals(60_000L, source.durationMs)
            assertEquals(estimateFixedDownloadBytes(2_000_000L, 60_000L), source.estimatedBytes)

            val streamed =
                api.streamFixedDownloadResource(
                    context = context,
                    source = source,
                    resourceUrl = source.transcodingUrl,
                    maxBytes = 128L,
                ) { resource ->
                    val bytes = ByteArray(FIXED_HLS_RESOURCE_BODY.encodeToByteArray().size)
                    resource.body.readFully(bytes)
                    bytes.decodeToString()
                }
            assertEquals(FIXED_HLS_RESOURCE_BODY, assertIs<FixedDownloadResourceResult.Success<String>>(streamed).value)
            assertEquals(AUTHORIZATION, resourceAuthorization)
            assertEquals("https://jellyfin.example/Videos/item-1/master.m3u8?DeviceId=device-1", resourceUrl)
            assertEquals("device-1", source.deviceId)
            assertEquals("play-session-1", source.playSessionId)
            assertEquals(FixedDownloadCleanupResult.Stopped, api.stopFixedDownloadEncoding(context, source))
            assertEquals("device-1", cleanupDeviceId)
            assertEquals("play-session-1", cleanupPlaySessionId)
        }

    @Test
    fun confirmedEmbeddedTextSubtitleRequestsExactIndexAndBurnInOnly() =
        runTest {
            var requestBody = ""
            val api =
                apiWith(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            "/Users/user-1" -> respondJson(ALLOWED_USER_JSON)
                            "/Items/item-1" -> respondJson(ITEM_JSON)
                            "/Items/item-1/PlaybackInfo" -> {
                                requestBody = (request.body as TextContent).text
                                respondJson(
                                    playbackInfoJson("/Videos/item-1/master.m3u8?SubtitleStreamIndex=2&SubtitleMethod=Encode&ApiKey=old"),
                                )
                            }
                            else -> error("Unexpected fixed-download request: ${request.url.encodedPath}")
                        }
                    },
                )
            val request =
                FixedDownloadRequest(
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                    quality = qualityRungs[6],
                    audioStreamIndex = 1,
                    subtitleSelection = DownloadSubtitleSelection.Embedded(streamIndex = 2, burnInConfirmed = true),
                )

            val result = api.preflightFixedDownload(context, request)
            val source = assertIs<FixedDownloadPreflightResult.Ready>(result).source
            val requestJson = Json.parseToJsonElement(requestBody).jsonObject
            assertEquals(1, requestJson["AudioStreamIndex"]?.jsonPrimitive?.int)
            assertEquals(2, requestJson["SubtitleStreamIndex"]?.jsonPrimitive?.int)
            assertEquals(true, requestJson["AlwaysBurnInSubtitleWhenTranscoding"]?.jsonPrimitive?.boolean)
            assertTrue(requestJson.toString().contains("\"Method\":\"Encode\""))
            assertEquals(
                "https://jellyfin.example/Videos/item-1/master.m3u8?SubtitleStreamIndex=2&SubtitleMethod=Encode&DeviceId=device-1",
                source.transcodingUrl,
            )
            assertEquals("device-1", source.deviceId)
            assertEquals("play-session-1", source.playSessionId)
        }

    private val context =
        AuthenticatedRequestContext(
            serverUrl = "https://jellyfin.example",
            userId = "user-1",
            accessToken = "token-1",
        )

    private fun apiWith(engine: MockEngine): KtorJellyfinApi {
        val client =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
            }
        return KtorJellyfinApi(
            client = client,
            downloadClient = client,
            authHeaderProvider =
                object : AuthHeaderProvider {
                    override suspend fun authHeader(token: String?): String = AUTHORIZATION
                },
        )
    }
}

private fun MockRequestHandleScope.respondJson(content: String) =
    respond(
        content = content,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

private const val AUTHORIZATION = "MediaBrowser Token=\"token-1\""
private const val FIXED_HLS_RESOURCE_BODY = "#EXTM3U\n#EXT-X-ENDLIST"
private const val ALLOWED_USER_JSON =
    """{"Id":"user-1","Name":"Download Test User","Policy":{"EnableContentDownloading":true}}"""
private const val ITEM_JSON =
    """{"Id":"item-1","Type":"Movie","RunTimeTicks":600000000,"IsLive":false,"MediaSources":[{"Id":"source-1","RunTimeTicks":600000000,"IsInfiniteStream":false,"MediaStreams":[{"Type":"Audio","Index":1,"IsDefault":true},{"Type":"Subtitle","Index":2,"Codec":"srt","IsExternal":false}]}]}"""

private fun playbackInfoJson(transcodingUrl: String): String {
    val separator = if ('?' in transcodingUrl) '&' else '?'
    return "{\"PlaySessionId\":\"play-session-1\",\"MediaSources\":[{\"Id\":\"source-1\"," +
        "\"SupportsTranscoding\":true,\"TranscodingUrl\":\"$transcodingUrl${separator}DeviceId=device-1\"," +
        "\"TranscodingContainer\":\"ts\",\"TranscodingSubProtocol\":\"hls\"}]}"
}
