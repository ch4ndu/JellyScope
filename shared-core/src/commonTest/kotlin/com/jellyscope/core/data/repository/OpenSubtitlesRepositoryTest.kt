// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.FakeSecureStore
import com.jellyscope.core.data.local.OpenSubtitlesSettingsStore
import com.jellyscope.core.data.remote.OpenSubtitlesApi
import com.jellyscope.core.domain.action.SetOpenSubtitleResultPreferenceAction
import com.jellyscope.core.domain.model.LocalSubtitleContext
import com.jellyscope.core.domain.model.OpenSubtitleResultPreference
import com.jellyscope.core.domain.model.OpenSubtitleSearchRequest
import com.jellyscope.core.domain.model.OpenSubtitleSearchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenSubtitlesRepositoryTest {
    @Test
    fun normalizesPreferredLanguagesForOpenSubtitles() {
        assertEquals("en", "eng".toOpenSubtitlesLanguages())
        assertEquals("es,fr", "Spanish; fra".toOpenSubtitlesLanguages())
        assertEquals("en", "unsupported language name".toOpenSubtitlesLanguages())
    }

    @Test
    fun exactReleaseMatchNormalizesAllowlistedExtensionsPunctuationAndTechnicalAliases() {
        val aliases =
            listOf(
                "Movie Name 2024 WEB DL H264 AAC 7.1.srt",
                "Movie.Name.2024.WEB-DL.H.265.AAC5.1.vtt",
                "Movie-Name-2024-WEB_DL-X.264-AAC2.0.ass",
                "Movie Name 2024 webrip X265 AAC7.1.ssa",
                "Movie.Name.2024.1080p.10bit.HDR10.AV1.SUB",
            )
        aliases.forEach { releaseName ->
            val ranked =
                rankOpenSubtitles(
                    candidates =
                        listOf(
                            candidate(
                                0,
                                releaseName = "Movie Other 2024 Group",
                                trusted = true,
                                rating = 10.0,
                                downloads = 100L,
                            ),
                            candidate(1, releaseName = releaseName),
                        ),
                    preference = OpenSubtitleResultPreference.NoPreference,
                    sourceReleaseBasename = "MOVIE.NAME.2024.WEB-DL.H.264.AAC5.1.mkv",
                )

            assertEquals("1", ranked.first().fileId, releaseName)
        }
    }

    @Test
    fun everyAllowlistedMediaExtensionIsStrippedFromSourceAndResult() {
        listOf("mkv", "mp4", "m4v", "mov", "avi", "ts", "m2ts", "webm", "mpg", "mpeg").forEach { extension ->
            val ranked =
                rankOpenSubtitles(
                    candidates =
                        listOf(
                            candidate(0, releaseName = "Movie.Other.Group"),
                            candidate(1, releaseName = "Movie.Release.Group.$extension"),
                        ),
                    preference = OpenSubtitleResultPreference.NoPreference,
                    sourceReleaseBasename = "Movie.Release.Group.$extension",
                )

            assertEquals("1", ranked.first().fileId, extension)
        }
    }

    @Test
    fun extensionlessDottedReleaseGroupRemainsMeaningful() {
        val ranked =
            rankOpenSubtitles(
                candidates =
                    listOf(
                        candidate(0, releaseName = "Movie.2024"),
                        candidate(1, releaseName = "movie.2024.releasegroup"),
                    ),
                preference = OpenSubtitleResultPreference.NoPreference,
                sourceReleaseBasename = "/ignored/Movie.2024.ReleaseGroup.mkv".substringAfterLast('/'),
            )

        assertEquals("1", ranked.first().fileId)
    }

    @Test
    fun jaccardRatioUsesCrossMultiplicationInsteadOfRawIntersectionCount() {
        val ranked =
            rankOpenSubtitles(
                candidates =
                    listOf(
                        candidate(0, releaseName = "alpha beta gamma delta epsilon zeta"),
                        candidate(1, releaseName = "alpha beta"),
                    ),
                preference = OpenSubtitleResultPreference.NoPreference,
                sourceReleaseBasename = "alpha beta gamma.mkv",
            )

        assertEquals("1", ranked.first().fileId)
    }

    @Test
    fun explicitPreferenceOutranksSimilarityWithoutFilteringNonmatches() {
        val exact = candidate(0, releaseName = "Movie.2024.Group.mkv")
        val hearing = candidate(1, releaseName = "Unrelated", hearingImpaired = true)
        val forced = candidate(2, releaseName = null, forced = true)
        val source = "Movie.2024.Group.mkv"

        assertEquals(
            listOf("0", "1", "2"),
            rankOpenSubtitles(listOf(exact, hearing, forced), OpenSubtitleResultPreference.NoPreference, source).map { it.fileId },
        )
        assertEquals(
            "1",
            rankOpenSubtitles(listOf(exact, hearing, forced), OpenSubtitleResultPreference.PreferHearingImpaired, source).first().fileId,
        )
        assertEquals(
            "2",
            rankOpenSubtitles(listOf(exact, hearing, forced), OpenSubtitleResultPreference.PreferForced, source).first().fileId,
        )
        assertEquals(
            "selectable",
            rankOpenSubtitles(
                listOf(
                    candidate(3, fileId = "forced", forced = true, selectable = false),
                    candidate(4, fileId = "selectable"),
                ),
                OpenSubtitleResultPreference.PreferForced,
                source,
            ).first().fileId,
        )
        assertEquals(3, rankOpenSubtitles(listOf(exact, hearing, forced), OpenSubtitleResultPreference.PreferForced, source).size)
    }

    @Test
    fun rankingPrecedenceIsQuerySelectableTrustRatingDownloadsThenEncounterOrder() {
        fun first(vararg candidates: OpenSubtitleRankingCandidate): String =
            rankOpenSubtitles(candidates.toList(), OpenSubtitleResultPreference.NoPreference, null).first().fileId

        assertEquals("query", first(candidate(0, fileId = "trusted", trusted = true, queryPriority = 1), candidate(1, fileId = "query")))
        assertEquals(
            "selectable",
            first(candidate(0, fileId = "unavailable", selectable = false, trusted = true), candidate(1, fileId = "selectable")),
        )
        assertEquals("trusted", first(candidate(0, fileId = "plain", rating = 10.0), candidate(1, fileId = "trusted", trusted = true)))
        assertEquals("rating", first(candidate(0, fileId = "downloads", downloads = 100L), candidate(1, fileId = "rating", rating = 8.0)))
        assertEquals("downloads", first(candidate(0, fileId = "few", downloads = 1L), candidate(1, fileId = "downloads", downloads = 2L)))
        assertEquals("early", first(candidate(8, fileId = "late"), candidate(2, fileId = "early")))
    }

    @Test
    fun missingAndUnrelatedReleaseNamesAreNeutral() {
        val ranked =
            rankOpenSubtitles(
                candidates =
                    listOf(
                        candidate(0, releaseName = null),
                        candidate(1, releaseName = "Completely.Unrelated", trusted = true),
                    ),
                preference = OpenSubtitleResultPreference.NoPreference,
                sourceReleaseBasename = null,
            )

        assertEquals("1", ranked.first().fileId)
        assertTrue(ranked.any { it.fileId == "0" })
    }

    @Test
    fun repositoryUsesCommittedPreferenceAndRollsBackAfterFailedPersistence() =
        runTest {
            val secureStore = FakeSecureStore()
            val settings = OpenSubtitlesSettingsStore(secureStore)
            settings.setApiKey("consumer-key")
            val repository = repository(settings, StandardTestDispatcher(testScheduler))
            val action = SetOpenSubtitleResultPreferenceAction(settings)

            action(OpenSubtitleResultPreference.PreferForced)
            assertEquals("2", repository.search(searchRequest()).first().fileId)

            secureStore.failOnWriteKey = "opensubtitles_result_preference"
            assertFailsWith<IllegalStateException> {
                action(OpenSubtitleResultPreference.PreferHearingImpaired)
            }
            assertEquals("2", repository.search(searchRequest()).first().fileId)
        }

    @Test
    fun persistedApiKeyOverridesDeveloperFallback() =
        runTest {
            val secureStore = FakeSecureStore()
            val settings = OpenSubtitlesSettingsStore(secureStore)
            val requestedApiKeys = mutableListOf<String>()
            val repository =
                repository(
                    settings = settings,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    requestedApiKeys = requestedApiKeys,
                    developerApiKey = "developer-key",
                )

            val request = searchRequest().copy(imdbId = null, seasonNumber = null, episodeNumber = null)
            assertNull(settings.apiKey())
            repository.search(request)
            settings.setApiKey("persisted-key")
            assertEquals("persisted-key", settings.apiKey())
            repository.search(request)
            settings.setApiKey(" ")
            assertNull(settings.apiKey())
            repository.search(request)

            assertEquals(listOf("developer-key", "persisted-key", "developer-key"), requestedApiKeys)
        }

    @Test
    fun repositoryPreservesQueryPriorityDeduplicatesFirstEncounterAndKeepsBasenameLocal() =
        runTest {
            val settings = OpenSubtitlesSettingsStore(FakeSecureStore())
            settings.setApiKey("consumer-key")
            val requestedUrls = mutableListOf<String>()
            val repository =
                repository(
                    settings = settings,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    requestedUrls = requestedUrls,
                    querySpecificResponses = true,
                )

            val results =
                repository.search(
                    searchRequest(sourceReleaseBasename = "Private.Release.Name.mkv").copy(
                        title = "Anime Episode Name",
                        seriesTitle = "Anime Series Name",
                    ),
                )
            val titleQuery = requestedUrls.map(::Url).single { url -> url.parameters["query"] != null }

            assertEquals(1, results.count { it.fileId == "10" })
            assertEquals("10", results.first().fileId)
            assertEquals("Anime Series Name", titleQuery.parameters["query"])
            assertEquals(null, titleQuery.parameters["year"])
            assertTrue(requestedUrls.isNotEmpty())
            assertFalse(requestedUrls.any { url -> "Private.Release.Name" in url || "sourceReleaseBasename" in url })
        }
}

private fun repository(
    settings: OpenSubtitlesSettingsStore,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    requestedUrls: MutableList<String> = mutableListOf(),
    requestedApiKeys: MutableList<String> = mutableListOf(),
    querySpecificResponses: Boolean = false,
    developerApiKey: String? = null,
): DefaultOpenSubtitlesRepository {
    val engine =
        MockEngine { request ->
            requestedUrls += request.url.toString()
            requestedApiKeys += request.headers["Api-Key"].orEmpty()
            val isTitleQuery = request.url.parameters["query"] != null
            val body =
                if (querySpecificResponses) {
                    if (isTitleQuery) duplicateAndLateResponse else duplicateOnlyResponse
                } else {
                    preferenceResponse
                }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    val apiClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    val api =
        OpenSubtitlesApi(
            apiClient = apiClient,
            downloadClient = HttpClient(MockEngine { error("download is not expected") }),
            userAgent = "JellyScope test",
        )
    return DefaultOpenSubtitlesRepository(
        api = api,
        settings = settings,
        dispatcher = dispatcher,
        developerApiKey = developerApiKey,
    )
}

private fun searchRequest(sourceReleaseBasename: String? = "Movie.Release.mkv") =
    OpenSubtitleSearchRequest(
        context = LocalSubtitleContext("server", "user", "item", "source"),
        title = "Movie",
        year = 2024,
        imdbId = "tt123",
        seasonNumber = 1,
        episodeNumber = 2,
        language = "en",
        sourceReleaseBasename = sourceReleaseBasename,
    )

private val preferenceResponse =
    """
    {"data":[
      null,
      {"id":null,"attributes":null},
      {"id":"normal-subtitle","attributes":{"language":"en","release":"Movie.Release","hearing_impaired":null,"from_trusted":null,"files":[null,{"file_id":null,"file_name":"incomplete.srt"},{"file_id":1,"file_name":"normal.srt"}]}},
      {"id":"forced-subtitle","attributes":{"language":"en","release":"Unrelated","foreign_parts_only":true,"files":[{"file_id":2,"file_name":"forced.srt"}]}}
    ]}
    """.trimIndent()

private val duplicateOnlyResponse =
    """{"data":[{"id":"duplicate-subtitle","attributes":{"language":"en","files":[{"file_id":10,"file_name":"duplicate.srt"}]}}]}"""

private val duplicateAndLateResponse =
    """
    {"data":[
      {"id":"duplicate-subtitle","attributes":{"language":"en","files":[{"file_id":10,"file_name":"duplicate.srt"}]}},
      {"id":"late-subtitle","attributes":{"language":"en","from_trusted":true,"files":[{"file_id":20,"file_name":"late.srt"}]}}
    ]}
    """.trimIndent()

private fun candidate(
    encounterOrder: Int,
    fileId: String = encounterOrder.toString(),
    queryPriority: Int = 0,
    selectable: Boolean = true,
    releaseName: String? = null,
    hearingImpaired: Boolean = false,
    forced: Boolean = false,
    trusted: Boolean = false,
    rating: Double? = null,
    downloads: Long? = null,
): OpenSubtitleRankingCandidate =
    OpenSubtitleRankingCandidate(
        queryPriority = queryPriority,
        encounterOrder = encounterOrder,
        result =
            OpenSubtitleSearchResult(
                subtitleId = "subtitle-$fileId",
                fileId = fileId,
                fileName = "$fileId.srt",
                language = "en",
                releaseName = releaseName,
                hearingImpaired = hearingImpaired,
                forced = forced,
                trusted = trusted,
                rating = rating,
                downloadCount = downloads,
                fps = null,
                format = "srt",
                selectable = selectable,
            ),
    )
