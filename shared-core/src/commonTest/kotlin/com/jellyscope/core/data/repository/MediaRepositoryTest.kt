// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.jellyscope.core.data.local.DetailRelatedCache
import com.jellyscope.core.data.local.DiscoveryCache
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.local.ServerScopedStoreRegistry
import com.jellyscope.core.data.remote.AuthHeaderProvider
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.data.remote.KtorJellyfinApi
import com.jellyscope.core.data.remote.MediaSegmentDto
import com.jellyscope.core.data.remote.PlaybackDeviceProfileDto
import com.jellyscope.core.data.remote.PlaybackInfoRequestDto
import com.jellyscope.core.data.remote.PlaybackInfoResponseDto
import com.jellyscope.core.data.remote.buildDeviceProfile
import com.jellyscope.core.data.remote.defaultItemFields
import com.jellyscope.core.data.remote.detailScreenItemFields
import com.jellyscope.core.data.remote.episodeStripFields
import com.jellyscope.core.data.remote.mediaSourceItemFields
import com.jellyscope.core.domain.action.SessionRemovalAuthorization
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemFilter
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibraryRecommendationReason
import com.jellyscope.core.domain.model.LibraryRecommendationRequest
import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.core.domain.model.LibrarySeriesStatus
import com.jellyscope.core.domain.model.LibraryShuffleRequest
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.RelatedGroup
import com.jellyscope.core.domain.model.RelatedGroupKind
import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.core.domain.playback.DeviceDecodingCapabilities
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.EffectivePlayerDevicePolicy
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackPlanningException
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerDeviceSettings
import com.jellyscope.core.domain.playback.RepositoryOperation
import com.jellyscope.core.domain.playback.toExactBitrateConstraint
import com.jellyscope.core.domain.usecase.visibleRelatedGroups
import com.jellyscope.core.util.LogScrubber
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
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaRepositoryTest {
    @Test
    fun visibleRelatedGroupsKeepsTheDisplayCapWithoutCancellingCacheBackedUpstream() =
        runTest {
            var upstreamCacheCommitted = false
            val fifthGroup = RelatedGroup(RelatedGroupKind.Studio, items = listOf(testRelatedItem("fifth")))
            val visible =
                flow {
                    repeat(4) { index ->
                        emit(RelatedGroup(RelatedGroupKind.Cast, items = listOf(testRelatedItem("cast-$index"))))
                    }
                    emit(fifthGroup)
                    upstreamCacheCommitted = true
                }.visibleRelatedGroups().toList()

            assertEquals(4, visible.size)
            assertTrue(upstreamCacheCommitted)
        }

    @Test
    fun repositoryHelpersPreserveCallerSpecificFailureOperations() =
        runTest {
            val capturedDiagnostics = mutableListOf<String>()
            Logger.setLogWriters(
                listOf(
                    object : LogWriter() {
                        override fun log(
                            severity: Severity,
                            message: String,
                            tag: String,
                            throwable: Throwable?,
                        ) {
                            if (tag == "MediaRepository") {
                                capturedDiagnostics += message
                            }
                        }
                    },
                ),
            )
            try {
                val fixture =
                    mediaRepositoryFixture(
                        engine = MockEngine { error("request failed") },
                    )

                fixture.repository
                    .getLibraryItems(
                        LibraryItemsRequest(
                            parentId = "library-1",
                            startIndex = 0,
                            limit = 1,
                        ),
                    )
                fixture.repository.getItemsByIds(listOf("movie-1"))
                fixture.repository.getFavorites()
                fixture.repository.search(FindQuery(text = "failure"))

                assertTrue(capturedDiagnostics.any { line -> line.contains("operation=getLibraryItems") })
                assertTrue(capturedDiagnostics.any { line -> line.contains("operation=getItemsByIds") })
                assertTrue(capturedDiagnostics.any { line -> line.contains("operation=getFavorites") })
                assertTrue(capturedDiagnostics.any { line -> line.contains("operation=search") })
            } finally {
                Logger.setLogWriters(emptyList())
            }
        }

    @Test
    fun everyRepositoryFailureBoundaryEmitsItsStableOperation() =
        runTest {
            val capturedDiagnostics = mutableListOf<String>()
            val capturedDiagnosticsLock = ReentrantLock()
            Logger.setLogWriters(
                listOf(
                    object : LogWriter() {
                        override fun log(
                            severity: Severity,
                            message: String,
                            tag: String,
                            throwable: Throwable?,
                        ) {
                            if (tag == "MediaRepository") {
                                LogScrubber
                                    .capture(tag, message)
                                    ?.let { diagnostic ->
                                        capturedDiagnosticsLock.withLock {
                                            capturedDiagnostics += diagnostic
                                        }
                                    }
                            }
                        }
                    },
                ),
            )
            try {
                val fixture =
                    mediaRepositoryFixture(
                        engine = MockEngine { error("request failed") },
                        apiDecorator = { delegate ->
                            object : JellyfinApi by delegate {
                                override suspend fun getMediaSegments(
                                    context: AuthenticatedRequestContext,
                                    itemId: String,
                                ): List<MediaSegmentDto> = error("media segments request failed")
                            }
                        },
                    )
                val relatedDetail =
                    MediaItemDetail(
                        item = MediaItem(id = "movie-1", name = "Movie", kind = MediaKind.Movie),
                    )
                val failureCases =
                    listOf(
                        RepositoryFailureCase(RepositoryOperation.UploadClientLogs) { repository ->
                            repository.uploadClientLogs("safe-content")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetLibraries) { repository ->
                            repository.getLibraries()
                        },
                        RepositoryFailureCase(RepositoryOperation.GetContinueWatching) { repository ->
                            repository.getContinueWatching()
                        },
                        RepositoryFailureCase(RepositoryOperation.GetNextUp) { repository ->
                            repository.getNextUp()
                        },
                        RepositoryFailureCase(RepositoryOperation.GetItemDetail) { repository ->
                            repository.getItemDetail("movie-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetRelated) { repository ->
                            repository.getRelated("movie-1", MediaKind.Movie, null)
                        },
                        RepositoryFailureCase(RepositoryOperation.GetRelatedGroups) { repository ->
                            val groups = repository.getRelatedGroups(relatedDetail).toList()
                            assertTrue(groups.isEmpty())
                            groups
                        },
                        RepositoryFailureCase(RepositoryOperation.GetSeasons) { repository ->
                            repository.getSeasons("series-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetEpisodes) { repository ->
                            repository.getEpisodes("series-1", "season-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetRecentlyAdded) { repository ->
                            repository.getRecentlyAdded()
                        },
                        RepositoryFailureCase(RepositoryOperation.GetUpcomingEpisodes) { repository ->
                            repository.getUpcomingEpisodes()
                        },
                        RepositoryFailureCase(RepositoryOperation.GetLibraryItems) { repository ->
                            repository.getLibraryItems(LibraryItemsRequest(parentId = "library-1", limit = 1))
                        },
                        RepositoryFailureCase(RepositoryOperation.GetLibraryShuffleQueue) { repository ->
                            repository.getLibraryShuffleQueue(LibraryShuffleRequest(parentId = "library-1"))
                        },
                        RepositoryFailureCase(RepositoryOperation.GetItemsByIds) { repository ->
                            repository.getItemsByIds(listOf("movie-1"))
                        },
                        RepositoryFailureCase(RepositoryOperation.GetGenres) { repository ->
                            repository.getGenres(parentId = "library-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetStudios) { repository ->
                            repository.getStudios(parentId = "library-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetLibraryFilters) { repository ->
                            repository.getLibraryFilters(parentId = "library-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetLibraryRecommendationSection) { repository ->
                            repository.getLibraryRecommendationSection(
                                LibraryRecommendationRequest(
                                    parentId = "library-1",
                                    collectionType = LibraryCollectionType.Movies,
                                    section = LibraryRecommendationSection.ContinueWatching,
                                    limit = 1,
                                ),
                            )
                        },
                        RepositoryFailureCase(RepositoryOperation.GetMediaSegments) { repository ->
                            repository.getMediaSegments("movie-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetCollections) { repository ->
                            repository.getCollections(parentId = "library-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetCollectionItems) { repository ->
                            repository.getCollectionItems("collection-1", limit = 1)
                        },
                        RepositoryFailureCase(RepositoryOperation.GetGenreItems) { repository ->
                            repository.getGenreItems("Drama", parentId = "library-1", limit = 1)
                        },
                        RepositoryFailureCase(RepositoryOperation.GetStudioItems) { repository ->
                            repository.getStudioItems("studio-1", parentId = "library-1", limit = 1)
                        },
                        RepositoryFailureCase(RepositoryOperation.GetPerson) { repository ->
                            repository.getPerson("person-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetPersonItems) { repository ->
                            repository.getPersonItems("person-1")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetPersonItemsPage) { repository ->
                            repository.getPersonItemsPage("person-1", limit = 1)
                        },
                        RepositoryFailureCase(RepositoryOperation.GetSuggestions) { repository ->
                            repository.getSuggestions()
                        },
                        RepositoryFailureCase(RepositoryOperation.GetRibbonItems) { repository ->
                            repository.getRibbonItems(MediaRibbon.ContinueWatching, limit = 1)
                        },
                        RepositoryFailureCase(RepositoryOperation.GetFavorites) { repository ->
                            repository.getFavorites()
                        },
                        RepositoryFailureCase(RepositoryOperation.Search) { repository ->
                            repository.search(FindQuery(text = "failure"))
                        },
                        RepositoryFailureCase(RepositoryOperation.FindPersons) { repository ->
                            repository.findPersons("person")
                        },
                        RepositoryFailureCase(RepositoryOperation.GetPlaybackInfo) { repository ->
                            repository.getPlaybackInfo(
                                itemId = "movie-1",
                                mediaSourceId = null,
                                startTimeTicks = 0L,
                            )
                        },
                        RepositoryFailureCase(RepositoryOperation.SetPlayed) { repository ->
                            repository.setPlayed("movie-1", played = true)
                        },
                        RepositoryFailureCase(RepositoryOperation.SetFavorite) { repository ->
                            repository.setFavorite("movie-1", favorite = true)
                        },
                    )

                assertEquals(RepositoryOperation.entries.toSet(), failureCases.map { it.operation }.toSet())
                failureCases.forEach { failureCase ->
                    val before = capturedDiagnosticsLock.withLock { capturedDiagnostics.size }
                    failureCase.invoke(fixture.repository)
                    val newDiagnostics = capturedDiagnosticsLock.withLock { capturedDiagnostics.drop(before) }
                    val operationDiagnostics =
                        newDiagnostics.filter { line ->
                            line.contains("operation=${failureCase.operation.diagnosticValue}")
                        }
                    assertTrue(
                        operationDiagnostics.isNotEmpty(),
                        "missing ${failureCase.operation.diagnosticValue} in $newDiagnostics",
                    )
                    assertEquals(1, operationDiagnostics.size, "duplicate ${failureCase.operation.diagnosticValue}")
                    assertTrue(operationDiagnostics.all { line -> line.contains("stage=repository event=failed") })
                }
            } finally {
                Logger.setLogWriters(emptyList())
            }
        }

    @Test
    fun uploadClientLogsUsesTheAuthenticatedPlainTextEndpointAndMapsDisallowed() =
        runTest {
            var path: String? = null
            var method: HttpMethod? = null
            var authorization: String? = null
            var body: String? = null
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            path = request.url.encodedPath
                            method = request.method
                            authorization = request.headers[HttpHeaders.Authorization]
                            body = request.body.asText()
                            respondJson("""{"FileName":"client-logs.txt"}""")
                        },
                )

            val result = fixture.repository.uploadClientLogs("stage=planner event=failed")

            assertEquals(SendClientLogsResult.Success("client-logs.txt"), result)
            assertEquals("/ClientLog/Document", path)
            assertEquals(HttpMethod.Post, method)
            assertEquals("MediaBrowser Token=\"token-1\"", authorization)
            assertEquals("stage=planner event=failed", body)

            val disallowed =
                mediaRepositoryFixture(
                    engine = MockEngine { respond("denied", HttpStatusCode.Forbidden) },
                ).repository.uploadClientLogs("stage=planner event=failed")

            assertEquals(SendClientLogsResult.UploadDisallowed, disallowed)
        }

    @Test
    fun libraryShuffleOmitsPagingAndPayloadFieldsAndDeduplicatesIds() =
        runTest {
            val capturedParams = mutableMapOf<String, String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            request.url.parameters.names().forEach { name ->
                                capturedParams[name] = request.url.parameters[name]
                            }
                            respondJson(queryResultJsonWithTotal(3, "movie-2", "movie-1", "movie-2"))
                        },
                )

            val ids =
                fixture.repository
                    .getLibraryShuffleQueue(
                        LibraryShuffleRequest(
                            parentId = "movies",
                            filters = LibraryFilterSelection(years = listOf(2024)),
                        ),
                    ).getOrThrow()

            assertEquals(listOf("movie-2", "movie-1"), ids)
            assertEquals("Movie", capturedParams["includeItemTypes"])
            assertEquals("Random", capturedParams["sortBy"])
            assertEquals("false", capturedParams["enableTotalRecordCount"])
            assertEquals("false", capturedParams["enableUserData"])
            assertEquals("0", capturedParams["imageTypeLimit"])
            assertNull(capturedParams["limit"])
            assertNull(capturedParams["fields"])
            assertNull(capturedParams["enableImageTypes"])
        }

    @Test
    fun parsesHomeEndpointsAndSendsTokenAuthHeader() =
        runTest {
            val paths = mutableListOf<String>()
            val authHeaders = mutableListOf<String?>()
            val fields = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            authHeaders += request.headers[HttpHeaders.Authorization]
                            fields += request.url.parameters["fields"]
                            when (request.url.encodedPath) {
                                "/UserViews" -> respondJson(userViewsJson)
                                "/UserItems/Resume" -> respondJson(queryResultJson("resume-1"))
                                "/Shows/NextUp" -> respondJson(queryResultJson("next-1"))
                                "/Items/Latest" -> respondJson(latestJson)
                                "/Items" -> respondJson(queryResultJson("short-1"))
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            fixture.repository.getLibraries().getOrThrow()
            fixture.repository.getContinueWatching().getOrThrow()
            fixture.repository.getNextUp().getOrThrow()
            fixture.repository.getRecentlyAdded().getOrThrow()
            fixture.repository.getFavorites().getOrThrow()

            assertEquals(
                listOf(
                    "/UserViews",
                    "/UserItems/Resume",
                    "/Shows/NextUp",
                    "/Items/Latest",
                    "/Items",
                ),
                paths,
            )
            assertTrue(authHeaders.all { header -> header == "MediaBrowser Token=\"token-1\"" })
            fields.filterNotNull().forEach { fieldSet ->
                assertFalse(fieldSet.split(",").contains("MediaSources"))
                assertFalse(fieldSet.split(",").contains("People"))
            }
        }

    @Test
    fun getItemsByIdsBuildsRepositoryScopedQuery() =
        runTest {
            var capturedIds: String? = null
            var capturedTypes: String? = null
            var capturedLimit: String? = null
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            capturedIds = request.url.parameters["Ids"]
                            capturedTypes = request.url.parameters["includeItemTypes"]
                            capturedLimit = request.url.parameters["limit"]
                            respondJson(queryResultJson("item-1", "item-2"))
                        },
                )

            fixture.repository.getItemsByIds(listOf("item-1", "item-2")).getOrThrow()

            assertEquals("item-1,item-2", capturedIds)
            assertEquals("Movie,Series,Episode", capturedTypes)
            assertEquals("2", capturedLimit)
        }

    @Test
    fun libraryBrowseQuerySendsParentPagingSortAndFilterParams() =
        runTest {
            val capturedParams = mutableMapOf<String, String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            request.url.parameters.names().forEach { name ->
                                capturedParams[name] = request.url.parameters[name]
                            }
                            respondJson(queryResultJsonWithTotal(42, "item-1", "item-2"))
                        },
                )

            val page =
                fixture.repository
                    .getLibraryItems(
                        LibraryItemsRequest(
                            parentId = "library-1",
                            sortBy = LibrarySortBy.DatePlayed,
                            sortOrder = LibrarySortOrder.Descending,
                            filters =
                                LibraryFilterSelection(
                                    genres = listOf("Drama"),
                                    years = listOf(2024),
                                    officialRatings = listOf("PG"),
                                    studioIds = listOf("studio-1"),
                                    tags = listOf("award-winner"),
                                    itemFilters = listOf(LibraryItemFilter.Favorite, LibraryItemFilter.Unplayed),
                                    seriesStatus = listOf(LibrarySeriesStatus.Continuing),
                                    hasSubtitles = true,
                                    hasTrailer = true,
                                    hasSpecialFeature = true,
                                ),
                            startIndex = 60,
                            limit = 30,
                        ),
                    ).getOrThrow()

            assertEquals("library-1", capturedParams["parentId"])
            assertEquals("60", capturedParams["startIndex"])
            assertEquals("30", capturedParams["limit"])
            assertEquals("DatePlayed", capturedParams["sortBy"])
            assertEquals("Descending", capturedParams["sortOrder"])
            assertEquals("Drama", capturedParams["genres"])
            assertEquals("2024", capturedParams["years"])
            assertEquals("PG", capturedParams["officialRatings"])
            assertEquals("studio-1", capturedParams["studioIds"])
            assertEquals("award-winner", capturedParams["tags"])
            assertEquals("Continuing", capturedParams["seriesStatus"])
            assertEquals(
                "IsFavorite,IsUnplayed,HasSubtitles,HasTrailer,HasSpecialFeature",
                capturedParams["filters"],
            )
            assertEquals(42, page.totalCount)
            assertEquals(60, page.startIndex)
            assertEquals(listOf("item-1", "item-2"), page.items.map { item -> item.id })
        }

    @Test
    fun getRibbonItemsUsesHomeQueriesWithExpandedLimit() =
        runTest {
            val paths = mutableListOf<String>()
            val limits = mutableListOf<String?>()
            val sortBy = mutableListOf<String?>()
            val sortOrder = mutableListOf<String?>()
            val filters = mutableListOf<String?>()
            val fields = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            limits += request.url.parameters["limit"]
                            sortBy += request.url.parameters["sortBy"]
                            sortOrder += request.url.parameters["sortOrder"]
                            filters += request.url.parameters["filters"]
                            fields += request.url.parameters["fields"]
                            when (request.url.encodedPath) {
                                "/UserItems/Resume" -> respondJson(queryResultJson("resume-1"))
                                "/Items" -> respondJson(queryResultJson("item-1"))
                                "/Shows/NextUp" -> respondJson(queryResultJson("next-1"))
                                "/Items/Latest" -> respondJson(latestJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            fixture.repository.getRibbonItems(MediaRibbon.ContinueWatching).getOrThrow()
            fixture.repository.getRibbonItems(MediaRibbon.Favorites).getOrThrow()
            fixture.repository.getRibbonItems(MediaRibbon.NextUp).getOrThrow()
            fixture.repository.getRibbonItems(MediaRibbon.RecentlyAdded).getOrThrow()

            assertEquals(
                listOf(
                    "/UserItems/Resume",
                    "/Items",
                    "/Shows/NextUp",
                    "/Items/Latest",
                ),
                paths,
            )
            assertEquals<List<String?>>(listOf("500", "500", "500", "500"), limits)
            assertEquals<List<String?>>(listOf(null, "DateCreated", null, null), sortBy)
            assertEquals<List<String?>>(listOf(null, "Descending", null, null), sortOrder)
            assertEquals<List<String?>>(listOf(null, "IsFavorite", null, null), filters)
            assertEquals<List<String?>>(
                List(4) { defaultItemFields.joinToString(",") },
                fields,
            )
            fields.filterNotNull().forEach { fieldSet ->
                assertFalse(fieldSet.split(",").contains("MediaSources"))
                assertFalse(fieldSet.split(",").contains("People"))
            }
        }

    @Test
    fun mapsAllLibrariesAndHandlesLatestBareArray() =
        runTest {
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/UserViews" -> respondJson(userViewsJson)
                                "/Items/Latest" -> respondJson(latestJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val libraries = fixture.repository.getLibraries().getOrThrow()
            val latest = fixture.repository.getRecentlyAdded().getOrThrow()

            assertEquals(
                listOf(LibraryCollectionType.Movies, LibraryCollectionType.TvShows, LibraryCollectionType.Music),
                libraries.map { it.collectionType },
            )
            assertEquals(listOf("latest-1"), latest.map { it.id })
            assertEquals(MediaKind.Movie, latest.single().kind)
        }

    @Test
    fun mapsUnauthorizedToTypedError() =
        runTest {
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine {
                            respondJson(
                                content = "{}",
                                status = HttpStatusCode.Unauthorized,
                            )
                        },
                )

            val result = fixture.repository.getItemDetail("item-1")

            assertIs<JellyfinApiException.Unauthorized>(result.exceptionOrNull())
        }

    @Test
    fun getPlaybackInfoAutoQualitySendsNoClientLimitSentinelWithoutVideoBitrateCondition() =
        runTest {
            var capturedBody = ""
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            capturedBody = request.body.asText()
                            respondJson(playbackInfoJson)
                        },
                )

            fixture.repository
                .getPlaybackInfo(
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                    startTimeTicks = 0L,
                    audioStreamIndex = null,
                    subtitleStreamIndex = null,
                    maxStreamingBitrate = null,
                ).getOrThrow()

            // Parse instead of substring-matching: the request-level field and the
            // nested profile field share the "MaxStreamingBitrate" name, and each
            // must independently carry the default ceiling.
            val body = Json.parseToJsonElement(capturedBody).jsonObject
            assertEquals(
                Int.MAX_VALUE.toLong(),
                body.getValue("MaxStreamingBitrate").jsonPrimitive.long,
            )
            val deviceProfile = body.getValue("DeviceProfile").jsonObject
            assertEquals(
                Int.MAX_VALUE.toLong(),
                deviceProfile.getValue("MaxStreamingBitrate").jsonPrimitive.long,
            )
            assertEquals(
                Int.MAX_VALUE.toLong(),
                deviceProfile.getValue("MaxStaticBitrate").jsonPrimitive.long,
            )
            assertEquals(
                listOf(null),
                deviceProfile.getValue("TranscodingProfiles").jsonArray.map { profile ->
                    profile.jsonObject["MaxBitrate"]
                },
            )
            val conditionProperties =
                deviceProfile.getValue("CodecProfiles").jsonArray.flatMap { codecProfile ->
                    codecProfile.jsonObject.getValue("Conditions").jsonArray.map { condition ->
                        condition.jsonObject
                            .getValue("Property")
                            .jsonPrimitive
                            .content
                    }
                }
            assertFalse(conditionProperties.contains("VideoBitrate"))
        }

    @Test
    fun getPlaybackInfoNeverSendsMaxFramerateInTheRequestBody() =
        runTest {
            var capturedBody = ""
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            capturedBody = request.body.asText()
                            respondJson(playbackInfoJson)
                        },
                )

            fixture.repository
                .getPlaybackInfo(
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                    startTimeTicks = 0L,
                    maxStreamingBitrate = 4_000_000L,
                    requestPolicy = PlaybackInfoRequestPolicy(maxFramerate = 30),
                ).getOrThrow()

            // PlaybackInfoDto has no MaxFramerate member, so a body field would be
            // silently ignored and a server probe could never falsify
            // it. The pin travels on the transcode URL instead (PlaybackUrls).
            assertFalse(Json.parseToJsonElement(capturedBody).jsonObject.containsKey("MaxFramerate"))
        }

    @Test
    fun getPlaybackInfoPostsProfileAndMapsResponse() =
        runTest {
            var capturedPath: String? = null
            var capturedMethod: HttpMethod? = null
            var capturedAuthHeader: String? = null
            var capturedBody = ""
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            capturedPath = request.url.encodedPath
                            capturedMethod = request.method
                            capturedAuthHeader = request.headers[HttpHeaders.Authorization]
                            capturedBody = request.body.asText()
                            respondJson(playbackInfoJson)
                        },
                )

            val playbackInfo =
                fixture.repository
                    .getPlaybackInfo(
                        itemId = "item-1",
                        mediaSourceId = "source-1",
                        startTimeTicks = 12_345_000L,
                        audioStreamIndex = 1,
                        subtitleStreamIndex = 2,
                        maxStreamingBitrate = 12_000_000L,
                    ).getOrThrow()

            assertEquals("/Items/item-1/PlaybackInfo", capturedPath)
            assertEquals(HttpMethod.Post, capturedMethod)
            assertEquals("MediaBrowser Token=\"token-1\"", capturedAuthHeader)
            val requestBody = Json.parseToJsonElement(capturedBody)
            val expectedProfile =
                buildDeviceProfile(
                    capabilities =
                        DeviceDecodingCapabilities(
                            videoCodecs = listOf("h264"),
                            audioCodecs = listOf("aac", "mp3"),
                            supportsDolbyVision = false,
                        ),
                    maxStreamingBitrate = 12_000_000L,
                    requestPolicy =
                        PlaybackInfoRequestPolicy(
                            userVideoResolutionCapIsResolved = true,
                            bitrateConstraint = 12_000_000L.toExactBitrateConstraint(),
                        ),
                    bitrateConstraint = 12_000_000L.toExactBitrateConstraint(),
                )
            val expectedRequest =
                PlaybackInfoRequestDto(
                    userId = "user-1",
                    startTimeTicks = 12_345_000L,
                    mediaSourceId = "source-1",
                    autoOpenLiveStream = false,
                    deviceProfile = expectedProfile,
                    audioStreamIndex = 1,
                    subtitleStreamIndex = 2,
                    maxStreamingBitrate = 12_000_000L,
                    maxAudioChannels = null,
                    enableDirectPlay = true,
                    enableDirectStream = true,
                    enableTranscoding = true,
                    allowAudioStreamCopy = true,
                    allowVideoStreamCopy = true,
                )
            assertEquals(
                Json { explicitNulls = false }.encodeToJsonElement(expectedRequest),
                requestBody,
            )
            assertEquals("play-session-1", playbackInfo.playSessionId)
            assertEquals(listOf("source-1"), playbackInfo.mediaSources.map { source -> source.id })
            assertEquals(false, playbackInfo.mediaSources.single().supportsDirectPlay)
            val videoStream =
                playbackInfo.mediaSources
                    .single()
                    .mediaStreams
                    .single()
            assertEquals(3840, videoStream.width)
            assertEquals(2160, videoStream.height)
            assertEquals(23.976f.toDouble(), videoStream.realFrameRate)
            assertEquals("HDR10", videoStream.videoRangeType)
            assertEquals(
                listOf("VideoCodecNotSupported", "SubtitleCodecNotSupported"),
                playbackInfo.mediaSources.single().transcodeReasons,
            )
            assertEquals(
                "/Videos/item-1/master.m3u8?VideoCodec=h264&" +
                    "TranscodeReasons=VideoCodecNotSupported%2C%20SubtitleCodecNotSupported",
                playbackInfo.mediaSources.single().transcodingUrl,
            )
        }

    @Test
    fun getPlaybackInfoUnauthorizedMapsToSafeDomainFailure() =
        runTest {
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine {
                            respondJson(
                                content = "{}",
                                status = HttpStatusCode.Unauthorized,
                            )
                        },
                )

            val result =
                fixture.repository.getPlaybackInfo(
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                    startTimeTicks = 0L,
                )

            val failure = assertIs<PlaybackPlanningException.RemoteRequestFailed>(result.exceptionOrNull())
            assertTrue(failure.isNetworkFailure)
            assertEquals("Unauthorized", failure.sourceExceptionType)
        }

    @Test
    fun getPlaybackInfoNotReachableMapsToRetryableSafeDomainFailure() =
        runTest {
            val fixture =
                mediaRepositoryFixture(
                    engine = MockEngine { error("transport should not be reached") },
                    apiDecorator = playbackFailureDecorator(JellyfinApiException.NotReachable),
                )

            val result =
                fixture.repository.getPlaybackInfo(
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                    startTimeTicks = 0L,
                )

            val failure = assertIs<PlaybackPlanningException.RemoteRequestFailed>(result.exceptionOrNull())
            assertTrue(failure.isNetworkFailure)
            assertEquals("NotReachable", failure.sourceExceptionType)
        }

    @Test
    fun getPlaybackInfoUnexpectedMapsToNonNetworkSafeDomainFailure() =
        runTest {
            val fixture =
                mediaRepositoryFixture(
                    engine = MockEngine { error("transport should not be reached") },
                    apiDecorator =
                        playbackFailureDecorator(
                            JellyfinApiException.Unexpected(IllegalStateException("unsafe detail")),
                        ),
                )

            val result =
                fixture.repository.getPlaybackInfo(
                    itemId = "item-1",
                    mediaSourceId = "source-1",
                    startTimeTicks = 0L,
                )

            val failure = assertIs<PlaybackPlanningException.RemoteRequestFailed>(result.exceptionOrNull())
            assertFalse(failure.isNetworkFailure)
            assertEquals("Unexpected", failure.sourceExceptionType)
        }

    @Test
    fun getMediaSegmentsSendsSegmentTypesAndMapsTicks() =
        runTest {
            var capturedPath: String? = null
            var capturedTypes: String? = null
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            capturedPath = request.url.encodedPath
                            capturedTypes = request.url.parameters["includeSegmentTypes"]
                            respondJson(mediaSegmentsJson)
                        },
                )

            val segments = fixture.repository.getMediaSegments("item-1").getOrThrow()

            assertEquals("/MediaSegments/item-1", capturedPath)
            assertEquals("Intro,Outro,Recap,Preview,Commercial", capturedTypes)
            assertEquals(listOf("Intro", "Commercial"), segments.map { segment -> segment.type.apiValue })
            assertEquals(listOf(1_000L, 4_000L), segments.map { segment -> segment.startMs })
        }

    @Test
    fun detailItemFieldsIncludePeopleAndMediaSources() =
        runTest {
            var capturedFields: String? = null
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            capturedFields = request.url.parameters["fields"]
                            respondJson(movieDetailJson)
                        },
                )

            fixture.repository.getItemDetail("movie-1").getOrThrow()

            // Detail SCREENS use the lighter set: people + sources + trailer,
            // but NOT chapters/trickplay (player-only, re-fetched on Play).
            assertTrue(detailScreenItemFields.contains("People"))
            assertTrue(detailScreenItemFields.contains("MediaSources"))
            assertTrue(detailScreenItemFields.contains("RemoteTrailers"))
            assertTrue(detailScreenItemFields.contains("ProviderIds"))
            assertTrue(mediaSourceItemFields.contains("ProviderIds"))
            assertFalse(detailScreenItemFields.contains("Chapters"))
            assertFalse(detailScreenItemFields.contains("Trickplay"))
            assertEquals(detailScreenItemFields.joinToString(","), capturedFields)
        }

    @Test
    fun setPlayedPostsAndDeletesUserPlayedItems() =
        runTest {
            val methodsAndPaths = mutableListOf<Pair<HttpMethod, String>>()
            val userIds = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            methodsAndPaths += request.method to request.url.encodedPath
                            userIds += request.url.parameters["userId"]
                            respondJson("{}")
                        },
                )

            fixture.repository.setPlayed(itemId = "movie-1", played = true).getOrThrow()
            fixture.repository.setPlayed(itemId = "movie-1", played = false).getOrThrow()

            assertEquals(
                listOf(
                    HttpMethod.Post to "/UserPlayedItems/movie-1",
                    HttpMethod.Delete to "/UserPlayedItems/movie-1",
                ),
                methodsAndPaths,
            )
            assertEquals<List<String?>>(listOf("user-1", "user-1"), userIds)
        }

    @Test
    fun setFavoritePostsAndDeletesUserFavoriteItems() =
        runTest {
            val methodsAndPaths = mutableListOf<Pair<HttpMethod, String>>()
            val userIds = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            methodsAndPaths += request.method to request.url.encodedPath
                            userIds += request.url.parameters["userId"]
                            respondJson("""{ "IsFavorite": true }""")
                        },
                )

            fixture.repository.setFavorite(itemId = "movie-1", favorite = true).getOrThrow()
            fixture.repository.setFavorite(itemId = "movie-1", favorite = false).getOrThrow()

            assertEquals(
                listOf(
                    HttpMethod.Post to "/UserFavoriteItems/movie-1",
                    HttpMethod.Delete to "/UserFavoriteItems/movie-1",
                ),
                methodsAndPaths,
            )
            assertEquals<List<String?>>(listOf("user-1", "user-1"), userIds)
        }

    @Test
    fun getsSeasonsAndEpisodesFromShowsEndpointsWithDefaultFields() =
        runTest {
            val paths = mutableListOf<String>()
            val fields = mutableListOf<String?>()
            val userIds = mutableListOf<String?>()
            val seasonIds = mutableListOf<String?>()
            val seasons = mutableListOf<String?>()
            val sortBy = mutableListOf<String?>()
            val sortOrder = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            fields += request.url.parameters["fields"]
                            userIds += request.url.parameters["userId"]
                            seasonIds += request.url.parameters["seasonId"]
                            seasons += request.url.parameters["season"]
                            sortBy += request.url.parameters["sortBy"]
                            sortOrder += request.url.parameters["sortOrder"]
                            when (request.url.encodedPath) {
                                "/Shows/series-1/Seasons" -> respondJson(seasonsJson)
                                "/Shows/series-1/Episodes" -> respondJson(seasonEpisodesJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val seriesSeasons = fixture.repository.getSeasons("series-1").getOrThrow()
            val episodes = fixture.repository.getEpisodes("series-1", "season-1", seasonIndex = 1).getOrThrow()

            assertEquals(
                listOf(
                    "/Shows/series-1/Seasons",
                    "/Shows/series-1/Episodes",
                ),
                paths,
            )
            assertEquals<List<String?>>(listOf("user-1", "user-1"), userIds)
            assertEquals<List<String?>>(listOf(null, "season-1"), seasonIds)
            assertEquals<List<String?>>(listOf(null, "1"), seasons)
            assertEquals<List<String?>>(listOf(null, "ParentIndexNumber,IndexNumber"), sortBy)
            assertEquals<List<String?>>(listOf(null, "Ascending"), sortOrder)
            assertEquals<List<String?>>(
                listOf(defaultItemFields.joinToString(","), episodeStripFields.joinToString(",")),
                fields,
            )
            assertEquals(listOf("season-1"), seriesSeasons.map { item -> item.id })
            assertEquals(listOf("episode-2", "episode-1"), episodes.map { item -> item.id })
            val episodeWithDetails = episodes.last()
            assertEquals("season-1", episodeWithDetails.seasonId)
            assertEquals("2024-02-03T00:00:00Z", episodeWithDetails.premiereDate?.toString())
            assertEquals(listOf("source-1"), episodeWithDetails.versions.map { version -> version.id })
        }

    @Test
    fun filtersConfirmedSpecialsFromRegularSeasonEpisodeLists() =
        runTest {
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/Shows/series-1/Episodes" -> respondJson(regularSeasonEpisodesWithSpecialsJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val episodes = fixture.repository.getEpisodes("series-1", "season-1", seasonIndex = 1).getOrThrow()
            val episodesWithoutSeasonIndex = fixture.repository.getEpisodes("series-1", "season-1").getOrThrow()

            assertEquals(listOf("episode-1", "episode-null-season"), episodes.map { item -> item.id })
            assertEquals(listOf("episode-1", "episode-null-season"), episodesWithoutSeasonIndex.map { item -> item.id })
        }

    @Test
    fun keepsConfirmedSpecialsForSpecialsSeasonEpisodeLists() =
        runTest {
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/Shows/series-1/Episodes" -> respondJson(specialsSeasonEpisodesJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val episodes = fixture.repository.getEpisodes("series-1", "season-0", seasonIndex = 0).getOrThrow()
            val episodesWithoutSeasonIndex = fixture.repository.getEpisodes("series-1", "season-0").getOrThrow()

            assertEquals(listOf("special-parent-zero", "special-flag"), episodes.map { item -> item.id })
            assertEquals(listOf("special-parent-zero", "special-flag"), episodesWithoutSeasonIndex.map { item -> item.id })
        }

    @Test
    fun getsMovieDetailAndRelatedSimilarItems() =
        runTest {
            val paths = mutableListOf<String>()
            val similarLimits = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            when (request.url.encodedPath) {
                                "/Items/movie-1" -> respondJson(movieDetailJson)
                                "/Items/movie-1/Similar" -> {
                                    similarLimits += request.url.parameters["limit"]
                                    respondJson(queryResultJson("similar-1"))
                                }
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val detail = fixture.repository.getItemDetail("movie-1").getOrThrow()
            val related =
                fixture.repository
                    .getRelated(
                        itemId = "movie-1",
                        kind = MediaKind.Movie,
                        seriesId = null,
                    ).getOrThrow()

            assertEquals("movie-1", detail.item.id)
            assertEquals("Movie overview.", detail.overview)
            assertEquals(listOf("1080p"), detail.versions.map { it.name })
            assertEquals(listOf("similar-1"), related.map { it.id })
            assertEquals(
                listOf(
                    "/Items/movie-1",
                    "/Items/movie-1/Similar",
                ),
                paths,
            )
            assertEquals(listOf<String?>("12"), similarLimits)
        }

    @Test
    fun getRelatedGroupsCachesShelvesUntilRefetchableClear() =
        runTest {
            val detailRelatedCache = DetailRelatedCache()
            val registry = ServerScopedStoreRegistry()
            registry.register(detailRelatedCache)
            // The related fetches run concurrently on worker threads, so the
            // capture list must be lock-protected — unsynchronized appends lost
            // elements and flaked this test on the jvm target.
            val relatedPathsLock = ReentrantLock()
            val relatedPaths = mutableListOf<String>()

            fun recordPath(path: String) = relatedPathsLock.withLock { relatedPaths += path }
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/Items/movie-1" -> respondJson(movieDetailJson)
                                "/Items/movie-1/Similar" -> {
                                    recordPath(request.url.encodedPath)
                                    respondJson(queryResultJson("similar-1"))
                                }
                                "/Items" -> {
                                    recordPath("${request.url.encodedPath}:${request.url.parameters["genres"]}")
                                    respondJson(queryResultJson("genre-1"))
                                }
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                    detailRelatedCache = detailRelatedCache,
                )

            val detail = fixture.repository.getItemDetail("movie-1").getOrThrow()

            val first = fixture.repository.getRelatedGroups(detail).toList()
            val second = fixture.repository.getRelatedGroups(detail).toList()

            assertEquals(first, second)
            assertEquals(listOf(RelatedGroupKind.Similar, RelatedGroupKind.Genre), first.map { group -> group.kind })
            assertEquals(
                listOf(listOf("similar-1"), listOf("genre-1")),
                first.map { group -> group.items.map { item -> item.id } },
            )
            // Similar + Genre fetches run concurrently, so the network ORDER is
            // scheduler-dependent (flaked on the jvm target); assert the request
            // multiset per round instead of arrival order.
            val relatedRoundPaths = listOf("/Items/movie-1/Similar", "/Items:Drama").sorted()
            assertEquals(relatedRoundPaths, relatedPaths.sorted())

            detailRelatedCache.clearServerScoped()
            val afterDirectClear = fixture.repository.getRelatedGroups(detail).toList()

            assertEquals(first, afterDirectClear)
            assertEquals(2, relatedPaths.size - 2)
            assertEquals(relatedRoundPaths, relatedPaths.drop(2).sorted())

            registry.clearRefetchableCaches()
            val afterRegistryClear = fixture.repository.getRelatedGroups(detail).toList()

            assertEquals(first, afterRegistryClear)
            assertEquals(6, relatedPaths.size)
            assertEquals(relatedRoundPaths, relatedPaths.drop(4).sorted())
        }

    @Test
    fun relatedGroupsKeepFallbackEmptyAndLogEachBestEffortFailure() =
        runTest {
            val capturedDiagnostics = mutableListOf<String>()
            Logger.setLogWriters(
                listOf(
                    object : LogWriter() {
                        override fun log(
                            severity: Severity,
                            message: String,
                            tag: String,
                            throwable: Throwable?,
                        ) {
                            if (tag == "MediaRepository") {
                                capturedDiagnostics += message
                            }
                        }
                    },
                ),
            )
            try {
                val fixture =
                    mediaRepositoryFixture(
                        engine = MockEngine { error("related request failed") },
                    )
                val detail =
                    MediaItemDetail(
                        item = MediaItem(id = "movie-1", name = "Movie", kind = MediaKind.Movie),
                    )

                assertEquals(emptyList(), fixture.repository.getRelatedGroups(detail).toList())
                assertTrue(
                    capturedDiagnostics.any { line -> line.contains("operation=getRelatedGroups") },
                )
            } finally {
                Logger.setLogWriters(emptyList())
            }
        }

    @Test
    fun getsEpisodeRelatedWithSeriesNextUpMergedFirst() =
        runTest {
            val paths = mutableListOf<String>()
            val nextUpSeriesIds = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            when (request.url.encodedPath) {
                                "/Items/episode-1" -> respondJson(episodeDetailJson)
                                "/Shows/NextUp" -> {
                                    nextUpSeriesIds += request.url.parameters["seriesId"]
                                    respondJson(queryResultJson("next-up-1"))
                                }
                                "/Items/episode-1/Similar" ->
                                    respondJson(queryResultJson("next-up-1", "similar-1"))
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val related =
                fixture.repository
                    .getRelated(
                        itemId = "episode-1",
                        kind = MediaKind.Episode,
                        seriesId = "series-1",
                    ).getOrThrow()

            assertEquals(listOf("next-up-1", "similar-1"), related.map { it.id })
            assertEquals(listOf<String?>("series-1"), nextUpSeriesIds)
            assertEquals(
                listOf(
                    "/Shows/NextUp",
                    "/Items/episode-1/Similar",
                ),
                paths,
            )
        }

    @Test
    fun searchSendsFindQueryParamsAndProjectsRuntimeBuckets() =
        runTest {
            val capturedParams = mutableMapOf<String, String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            request.url.parameters.names().forEach { name ->
                                capturedParams[name] = request.url.parameters[name]
                            }
                            when (request.url.encodedPath) {
                                "/Items" -> respondJson(findItemsJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val result =
                fixture.repository
                    .search(
                        FindQuery(
                            text = "matrix 1999",
                            year = 1999,
                            genreNames = listOf("Action", "Sci-Fi"),
                            personId = "person-1",
                            watchedFilter = WatchedFilter.Unwatched,
                            runtimeBucket = RuntimeBucket.Under60,
                        ),
                    ).getOrThrow()

            assertEquals("user-1", capturedParams["userId"])
            assertEquals("true", capturedParams["recursive"])
            assertEquals("Movie,Series,Episode", capturedParams["includeItemTypes"])
            assertEquals("60", capturedParams["limit"])
            assertEquals("matrix 1999", capturedParams["searchTerm"])
            assertEquals("person-1", capturedParams["personIds"])
            assertEquals("Action,Sci-Fi", capturedParams["genres"])
            assertEquals("1999", capturedParams["years"])
            assertEquals("IsUnplayed", capturedParams["filters"])
            assertEquals(defaultItemFields.joinToString(","), capturedParams["fields"])
            assertFalse(capturedParams["fields"].orEmpty().split(",").contains("MediaSources"))
            assertFalse(capturedParams["fields"].orEmpty().split(",").contains("People"))
            assertEquals(listOf("movie-1"), result.movies.map { it.id })
            assertEquals(listOf("series-1"), result.shows.map { it.id })
            assertEquals(emptyList(), result.episodes.map { it.id })
        }

    @Test
    fun personSearchDeduplicatesRepeatedServerPersons() =
        runTest {
            // Jellyfin returns the same person multiple times when they are
            // reachable through several library folders; duplicate ids crash
            // lazy suggestion rows keyed by person id.
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/Persons" ->
                                    respondJson(
                                        """
                                        {"Items":[
                                          {"Id":"person-1","Name":"Kajal Aggarwal"},
                                          {"Id":"person-1","Name":"Kajal Aggarwal"},
                                          {"Id":"person-2","Name":"Kajal Kiran"}
                                        ]}
                                        """.trimIndent(),
                                    )
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val persons = fixture.repository.findPersons("kajal").getOrThrow()

            assertEquals(listOf("person-1", "person-2"), persons.map { person -> person.id })
        }

    @Test
    fun personSearchUnauthorizedMapsToTypedError() =
        runTest {
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/Persons" ->
                                    respondJson(
                                        content = "{}",
                                        status = HttpStatusCode.Unauthorized,
                                    )
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val result = fixture.repository.findPersons("tom")

            assertIs<JellyfinApiException.Unauthorized>(result.exceptionOrNull())
        }

    @Test
    fun libraryFiltersFetchesGenresStudiosAndFilterOptions() =
        runTest {
            val paths = mutableListOf<String>()
            val parentIds = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            parentIds += request.url.parameters["parentId"]
                            when (request.url.encodedPath) {
                                "/Genres" -> respondJson(facetResultJson("genre-1", "Drama"))
                                "/Studios" -> respondJson(facetResultJson("studio-1", "Studio One"))
                                "/Items/Filters" -> respondJson(libraryFilterOptionsJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val facets = fixture.repository.getLibraryFilters(parentId = "library-1").getOrThrow()

            assertEquals(listOf("/Genres", "/Studios", "/Items/Filters"), paths)
            assertEquals<List<String?>>(listOf("library-1", "library-1", "library-1"), parentIds)
            assertEquals(listOf("Drama"), facets.genres.map { facet -> facet.name })
            assertEquals(listOf("Studio One"), facets.studios.map { facet -> facet.name })
            assertEquals(listOf("PG", "TV-14"), facets.officialRatings)
            assertEquals(listOf("Favorite", "Award"), facets.tags)
            assertEquals(listOf(2024, 2023), facets.years)
        }

    @Test
    fun genreAndStudioListsUseDedicatedFacetEndpoints() =
        runTest {
            val paths = mutableListOf<String>()
            val parentIds = mutableListOf<String?>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            parentIds += request.url.parameters["parentId"]
                            when (request.url.encodedPath) {
                                "/Genres" -> respondJson(facetResultWithImageJson("genre-1", "Drama", "genre-primary"))
                                "/Studios" -> respondJson(facetResultWithImageJson("studio-1", "Studio One", "studio-primary"))
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val genres = fixture.repository.getGenres(parentId = "library-1").getOrThrow()
            val studios = fixture.repository.getStudios(parentId = "library-1").getOrThrow()

            assertEquals(listOf("/Genres", "/Studios"), paths)
            assertEquals<List<String?>>(listOf("library-1", "library-1"), parentIds)
            assertEquals("Drama", genres.single().name)
            assertEquals("genre-primary", genres.single().imageRefs.primaryTag)
            assertEquals("Studio One", studios.single().name)
            assertEquals("studio-primary", studios.single().imageRefs.primaryTag)
        }

    @Test
    fun discoveryQueriesMapCollectionsGenreStudioAndPersonItems() =
        runTest {
            val capturedParams = mutableListOf<Map<String, String?>>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            capturedParams +=
                                request.url.parameters.names().associateWith { name ->
                                    request.url.parameters[name]
                                }
                            if (request.url.parameters["personIds"] == "person-1") {
                                respondJson(typedQueryResultJson("movie-1" to "Movie", "series-1" to "Series"))
                            } else {
                                respondJson(queryResultJsonWithTotal(2, "item-1", "item-2"))
                            }
                        },
                )

            val collections = fixture.repository.getCollections().getOrThrow()
            val collectionItems =
                fixture.repository
                    .getCollectionItems(collectionId = "collection-1", startIndex = 30, limit = 15)
                    .getOrThrow()
            fixture.repository
                .getGenreItems(genre = "Drama", parentId = "library-1", startIndex = 60, limit = 30)
                .getOrThrow()
            fixture.repository
                .getStudioItems(studioId = "studio-1", parentId = "library-1", startIndex = 90, limit = 30)
                .getOrThrow()
            val filmography = fixture.repository.getPersonItems("person-1").getOrThrow()

            assertEquals("BoxSet", capturedParams[0]["includeItemTypes"])
            assertEquals("SortName", capturedParams[0]["sortBy"])
            assertEquals("collection-1", capturedParams[1]["parentId"])
            assertEquals("false", capturedParams[1]["recursive"])
            assertEquals("30", capturedParams[1]["startIndex"])
            assertEquals("15", capturedParams[1]["limit"])
            assertEquals("Drama", capturedParams[2]["genres"])
            assertEquals("library-1", capturedParams[2]["parentId"])
            assertEquals("studio-1", capturedParams[3]["studioIds"])
            assertEquals("person-1", capturedParams[4]["personIds"])
            assertEquals("Movie,Series", capturedParams[4]["includeItemTypes"])
            assertEquals(listOf("item-1", "item-2"), collections.map { item -> item.id })
            assertEquals(2, collectionItems.totalCount)
            assertEquals(listOf("movie-1"), filmography.movies.map { item -> item.id })
            assertEquals(listOf("series-1"), filmography.series.map { item -> item.id })
        }

    @Test
    fun libraryRecommendationsStayScopedAndMapMovieCategories() =
        runTest {
            val captured = mutableListOf<Pair<String, Map<String, String?>>>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            captured +=
                                request.url.encodedPath to
                                request.url.parameters
                                    .names()
                                    .associateWith { name -> request.url.parameters[name] }
                            when (request.url.encodedPath) {
                                "/UserItems/Resume" -> respondJson(queryResultJson("resume-1", "resume-1"))
                                "/Items/Latest" -> respondJson("[${queryResultItemJson("latest-1")}]")
                                "/Shows/NextUp" -> respondJson(queryResultJson("next-1"))
                                "/Movies/Recommendations" -> respondJson(movieRecommendationsJson)
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val movieRequest =
                LibraryRecommendationRequest(
                    parentId = "movies-1",
                    collectionType = LibraryCollectionType.Movies,
                    section = LibraryRecommendationSection.ContinueWatching,
                )
            val resume = fixture.repository.getLibraryRecommendationSection(movieRequest).getOrThrow()
            val latest =
                fixture.repository
                    .getLibraryRecommendationSection(movieRequest.copy(section = LibraryRecommendationSection.RecentlyAdded))
                    .getOrThrow()
            val next =
                fixture.repository
                    .getLibraryRecommendationSection(
                        movieRequest.copy(
                            collectionType = LibraryCollectionType.TvShows,
                            section = LibraryRecommendationSection.NextUp,
                        ),
                    ).getOrThrow()
            val movieRecommendations =
                fixture.repository
                    .getLibraryRecommendationSection(movieRequest.copy(section = LibraryRecommendationSection.MovieRecommendations))
                    .getOrThrow()

            assertEquals(listOf("resume-1"), resume.single().items.map { item -> item.id })
            assertEquals(listOf("latest-1"), latest.single().items.map { item -> item.id })
            assertEquals(listOf("next-1"), next.single().items.map { item -> item.id })
            assertEquals(LibraryRecommendationReason.HasActor, movieRecommendations.single().reason)
            assertEquals("Actor One", movieRecommendations.single().baselineItemName)
            assertTrue(captured.take(3).all { (_, parameters) -> parameters["parentId"] == "movies-1" })
            assertEquals("Movie", captured[0].second["includeItemTypes"])
            assertEquals("Movie", captured[1].second["includeItemTypes"])
            assertEquals("movies-1", captured[3].second["parentId"])
        }

    @Test
    fun personHeaderAndPagedFilmographyUseDetailAndItemsPaging() =
        runTest {
            val paths = mutableListOf<String>()
            val capturedParams = mutableListOf<Map<String, String?>>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            capturedParams +=
                                request.url.parameters.names().associateWith { name ->
                                    request.url.parameters[name]
                                }
                            when (request.url.encodedPath) {
                                "/Items/person-1" -> respondJson(personDetailJson)
                                "/Items" -> respondJson(typedQueryResultJsonWithTotal(3, "movie-1" to "Movie", "series-1" to "Series"))
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val header = fixture.repository.getPerson("person-1").getOrThrow()
            val filmography =
                fixture.repository
                    .getPersonItemsPage(personId = "person-1", startIndex = 60, limit = 30)
                    .getOrThrow()

            assertEquals(listOf("/Items/person-1", "/Items"), paths)
            assertEquals("user-1", capturedParams[0]["userId"])
            assertEquals(detailScreenItemFields.joinToString(","), capturedParams[0]["fields"])
            assertEquals("person-1", header.id)
            assertEquals("Actor", header.name)
            assertEquals("Actor biography.", header.overview)
            assertEquals("person-primary", header.imageRefs.primaryTag)
            assertEquals("person-1", capturedParams[1]["personIds"])
            assertEquals("Movie,Series", capturedParams[1]["includeItemTypes"])
            assertEquals("60", capturedParams[1]["startIndex"])
            assertEquals("30", capturedParams[1]["limit"])
            assertEquals(3, filmography.totalCount)
            assertEquals(60, filmography.startIndex)
            assertEquals(listOf("movie-1"), filmography.movies.map { item -> item.id })
            assertEquals(listOf("series-1"), filmography.series.map { item -> item.id })
        }

    @Test
    fun suggestionsUseSimilarItemsFromContinueWatchingSeed() =
        runTest {
            val paths = mutableListOf<String>()
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            when (request.url.encodedPath) {
                                "/UserItems/Resume" -> respondJson(queryResultJsonWithTotal(1, "seed-1"))
                                "/Items/seed-1/Similar" -> respondJson(queryResultJsonWithTotal(2, "seed-1", "similar-1"))
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                )

            val suggestions = fixture.repository.getSuggestions().getOrThrow()

            assertEquals(listOf("/UserItems/Resume", "/Items/seed-1/Similar"), paths)
            assertEquals("seed-1", suggestions.seedItem?.id)
            assertEquals(listOf("similar-1"), suggestions.items.map { item -> item.id })
        }

    @Test
    fun upcomingTvUsesShowsUpcomingEndpoint() =
        runTest {
            var capturedPath: String? = null
            var capturedUserId: String? = null
            var capturedLimit: String? = null
            val fixture =
                mediaRepositoryFixture(
                    engine =
                        MockEngine { request ->
                            capturedPath = request.url.encodedPath
                            capturedUserId = request.url.parameters["userId"]
                            capturedLimit = request.url.parameters["limit"]
                            respondJson(queryResultJsonWithTotal(1, "upcoming-1"))
                        },
                )

            val upcoming = fixture.repository.getUpcomingEpisodes().getOrThrow()

            assertEquals("/Shows/Upcoming", capturedPath)
            assertEquals("user-1", capturedUserId)
            assertEquals("50", capturedLimit)
            assertEquals(listOf("upcoming-1"), upcoming.map { item -> item.id })
        }
}

private fun testRelatedItem(id: String) =
    MediaItem(
        id = id,
        name = id,
        kind = MediaKind.Movie,
    )

private fun playbackFailureDecorator(failure: JellyfinApiException): (JellyfinApi) -> JellyfinApi =
    { delegate ->
        object : JellyfinApi by delegate {
            override suspend fun getPlaybackInfo(
                context: AuthenticatedRequestContext,
                itemId: String,
                mediaSourceId: String?,
                startTimeTicks: Long,
                deviceProfile: PlaybackDeviceProfileDto,
                playerDevicePolicy: EffectivePlayerDevicePolicy,
                requestPolicy: PlaybackInfoRequestPolicy,
                audioStreamIndex: Int?,
                subtitleStreamIndex: Int?,
                maxStreamingBitrate: Long?,
            ): PlaybackInfoResponseDto = throw failure
        }
    }

private fun mediaRepositoryFixture(
    engine: MockEngine,
    detailRelatedCache: DetailRelatedCache = DetailRelatedCache(),
    apiDecorator: (JellyfinApi) -> JellyfinApi = { api -> api },
): MediaRepositoryFixture {
    val sessionRepository = MediaRepositorySessionRepository(SessionState.LoggedIn(session))

    val repository =
        DefaultMediaRepository(
            jellyfinApi =
                apiDecorator(
                    KtorJellyfinApi(
                        client = mockClient(engine),
                        authHeaderProvider =
                            object : AuthHeaderProvider {
                                override suspend fun authHeader(token: String?) = "MediaBrowser Token=\"$token\""
                            },
                    ),
                ),
            sessionRepository = sessionRepository,
            deviceProfileProvider =
                object : DeviceProfileProvider {
                    override fun capabilities(backend: PlayerBackend) =
                        DeviceDecodingCapabilities(
                            videoCodecs = listOf("h264"),
                            audioCodecs = listOf("aac", "mp3"),
                            supportsDolbyVision = false,
                        )
                },
            playerDeviceSettingsStore = StaticPlayerDeviceSettingsStore(),
            discoveryCache = DiscoveryCache(),
            detailRelatedCache = detailRelatedCache,
        )

    return MediaRepositoryFixture(repository)
}

private data class MediaRepositoryFixture(
    val repository: DefaultMediaRepository,
)

private data class RepositoryFailureCase(
    val operation: RepositoryOperation,
    val invoke: suspend (DefaultMediaRepository) -> Any?,
)

private class StaticPlayerDeviceSettingsStore : PlayerDeviceSettingsStore {
    private val _settings = MutableStateFlow(PlayerDeviceSettings())

    override val settings: StateFlow<PlayerDeviceSettings> = _settings.asStateFlow()

    override suspend fun setSettings(settings: PlayerDeviceSettings) {
        _settings.value = settings
    }
}

private class MediaRepositorySessionRepository(
    initialState: SessionState,
) : SessionRepository {
    private val _sessionState = MutableStateFlow(initialState)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    override suspend fun setLoggedIn(session: Session) {
        _sessionState.value = SessionState.LoggedIn(session)
    }

    override suspend fun setLoggedOut(
        serverUrl: String?,
        authorization: SessionRemovalAuthorization,
    ): Result<Unit> {
        _sessionState.value = SessionState.LoggedOut(serverUrl)
        return Result.success(Unit)
    }
}

private fun mockClient(engine: MockEngine): HttpClient =
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

private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun OutgoingContent.asText(): String =
    when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        else -> toString()
    }

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

private val userViewsJson =
    """
    {
      "Items": [
        { "Id": "movies", "Name": "Movies", "CollectionType": "movies" },
        { "Id": "shows", "Name": "Shows", "CollectionType": "tvshows" },
        { "Id": "music", "Name": "Music", "CollectionType": "music" }
      ],
      "TotalRecordCount": 3
    }
    """.trimIndent()

private val playbackInfoJson =
    """
    {
      "PlaySessionId": "play-session-1",
      "MediaSources": [
        {
          "Id": "source-1",
          "SupportsDirectPlay": false,
          "SupportsDirectStream": false,
          "SupportsTranscoding": true,
          "TranscodingUrl": "/Videos/item-1/master.m3u8?VideoCodec=h264&TranscodeReasons=VideoCodecNotSupported%2C%20SubtitleCodecNotSupported",
          "Container": "mkv",
          "MediaStreams": [
            {
              "Type": "Video",
              "Width": 3840,
              "Height": 2160,
              "RealFrameRate": 23.976,
              "AverageFrameRate": 24.0,
              "VideoRangeType": "HDR10"
            }
          ]
        }
      ]
    }
    """.trimIndent()

private val mediaSegmentsJson =
    """
    [
      { "Type": "Intro", "StartTicks": 10000000, "EndTicks": 30000000 },
      { "Type": "Commercial", "StartTicks": 40000000, "EndTicks": 50000000 },
      { "Type": "Outro", "StartTicks": 60000000, "EndTicks": 60000000 }
    ]
    """.trimIndent()

private fun queryResultJson(vararg ids: String) = queryResultJsonWithTotal(total = ids.size, ids = ids)

private val movieRecommendationsJson =
    """
    [
      {
        "RecommendationType": "HasLikedActor",
        "BaselineItemName": "Actor One",
        "CategoryId": "actor-one",
        "Items": [${queryResultItemJson("recommended-1")}]
      }
    ]
    """.trimIndent()

private fun queryResultJsonWithTotal(
    total: Int,
    vararg ids: String,
) = """
    {
      "Items": [
        ${ids.joinToString(",") { id -> queryResultItemJson(id) }}
      ],
      "TotalRecordCount": $total
    }
    """.trimIndent()

private fun queryResultItemJson(id: String) =
    """
    {
      "Id": "$id",
      "Name": "Item $id",
          "Type": "Episode",
          "SeriesName": "A Show",
          "ParentIndexNumber": 1,
          "IndexNumber": 2,
          "RunTimeTicks": 300000000,
          "DateCreated": "2024-01-01T00:00:00Z",
          "ImageTags": { "Primary": "primary-tag" },
          "UserData": { "Played": false, "IsFavorite": true, "PlayedPercentage": 50.0, "PlaybackPositionTicks": 100 }
    }
    """.trimIndent()

private fun typedQueryResultJson(vararg items: Pair<String, String>) = typedQueryResultJsonWithTotal(items.size, *items)

private fun typedQueryResultJsonWithTotal(
    total: Int,
    vararg items: Pair<String, String>,
) = """
    {
      "Items": [
        ${items.joinToString(",") { (id, type) -> typedQueryResultItemJson(id = id, type = type) }}
      ],
      "TotalRecordCount": $total
    }
    """.trimIndent()

private fun typedQueryResultItemJson(
    id: String,
    type: String,
) = """
    {
      "Id": "$id",
      "Name": "Item $id",
      "Type": "$type",
      "ImageTags": { "Primary": "primary-tag" }
    }
    """.trimIndent()

private val movieDetailJson =
    """
    {
      "Id": "movie-1",
      "Name": "Movie",
      "Type": "Movie",
      "Overview": "Movie overview.",
      "Genres": ["Drama"],
      "OfficialRating": "PG",
      "ProductionYear": 2024,
      "RunTimeTicks": 600000000,
      "ImageTags": { "Primary": "poster-tag" },
      "BackdropImageTags": ["backdrop-tag"],
      "MediaSources": [
        {
          "Id": "source-1",
          "Name": "1080p",
          "MediaStreams": [
            { "Type": "Audio", "DisplayTitle": "English AAC", "Index": 1 },
            { "Type": "Subtitle", "DisplayTitle": "English SDH", "Index": 2 }
          ]
        }
      ],
      "UserData": { "Played": false, "PlayedPercentage": 25.0, "PlaybackPositionTicks": 100 }
    }
    """.trimIndent()

private val personDetailJson =
    """
    {
      "Id": "person-1",
      "Name": "Actor",
      "Type": "Person",
      "Overview": "Actor biography.",
      "ImageTags": { "Primary": "person-primary" },
      "BackdropImageTags": ["person-backdrop"]
    }
    """.trimIndent()

private val episodeDetailJson =
    """
    {
      "Id": "episode-1",
      "Name": "Episode",
      "Type": "Episode",
      "SeriesName": "A Show",
      "SeriesId": "series-1",
      "ParentIndexNumber": 2,
      "IndexNumber": 3,
      "Overview": "Episode overview.",
      "RunTimeTicks": 600000000,
      "ImageTags": { "Primary": "poster-tag" },
      "UserData": { "Played": false, "PlayedPercentage": 25.0, "PlaybackPositionTicks": 100 }
    }
    """.trimIndent()

private val seasonsJson =
    """
    {
      "Items": [
        {
          "Id": "season-1",
          "Name": "Season 1",
          "Type": "Season",
          "SeriesId": "series-1",
          "IndexNumber": 1,
          "ImageTags": { "Primary": "season-tag" },
          "UserData": { "UnplayedItemCount": 3 }
        }
      ],
      "TotalRecordCount": 1
    }
    """.trimIndent()

private val seasonEpisodesJson =
    """
    {
      "Items": [
        {
          "Id": "episode-2",
          "Name": "Episode Two",
          "Type": "Episode",
          "SeriesName": "A Show",
          "SeriesId": "series-1",
          "SeasonId": "season-1",
          "ParentIndexNumber": 1,
          "IndexNumber": 2
        },
        {
          "Id": "episode-1",
          "Name": "Episode One",
          "Type": "Episode",
          "SeriesName": "A Show",
          "SeriesId": "series-1",
          "SeasonId": "season-1",
          "ParentIndexNumber": 1,
          "IndexNumber": 1,
          "PremiereDate": "2024-02-03T00:00:00Z",
          "Overview": "Episode overview.",
          "OfficialRating": "TV-14",
          "RunTimeTicks": 600000000,
          "ImageTags": { "Primary": "episode-tag" },
          "MediaSources": [
            {
              "Id": "source-1",
              "Name": "1080p",
              "MediaStreams": [
                { "Type": "Video", "Height": 1080, "Index": 0 },
                { "Type": "Audio", "Codec": "eac3", "ChannelLayout": "5.1", "IsDefault": true, "Index": 1 }
              ]
            }
          ],
          "UserData": { "Played": false, "PlayedPercentage": 25.0, "PlaybackPositionTicks": 100 }
        }
      ],
      "TotalRecordCount": 2
    }
    """.trimIndent()

private val regularSeasonEpisodesWithSpecialsJson =
    """
    {
      "Items": [
        {
          "Id": "episode-1",
          "Name": "Episode One",
          "Type": "Episode",
          "SeriesId": "series-1",
          "SeasonId": "season-1",
          "ParentIndexNumber": 1,
          "IndexNumber": 1
        },
        {
          "Id": "special-parent-zero",
          "Name": "Special",
          "Type": "Episode",
          "SeriesId": "series-1",
          "SeasonId": "season-0",
          "ParentIndexNumber": 0,
          "IndexNumber": 1
        },
        {
          "Id": "special-flag",
          "Name": "Flagged Special",
          "Type": "Episode",
          "SeriesId": "series-1",
          "SeasonId": "season-0",
          "IsSpecial": true,
          "IndexNumber": 2
        },
        {
          "Id": "episode-null-season",
          "Name": "Episode With Missing Season",
          "Type": "Episode",
          "SeriesId": "series-1",
          "SeasonId": "season-1",
          "IndexNumber": 3
        }
      ],
      "TotalRecordCount": 4
    }
    """.trimIndent()

private val specialsSeasonEpisodesJson =
    """
    {
      "Items": [
        {
          "Id": "special-parent-zero",
          "Name": "Special",
          "Type": "Episode",
          "SeriesId": "series-1",
          "SeasonId": "season-0",
          "ParentIndexNumber": 0,
          "IndexNumber": 1
        },
        {
          "Id": "special-flag",
          "Name": "Flagged Special",
          "Type": "Episode",
          "SeriesId": "series-1",
          "SeasonId": "season-0",
          "IsSpecial": true,
          "IndexNumber": 2
        }
      ],
      "TotalRecordCount": 2
    }
    """.trimIndent()

private val latestJson =
    """
    [
      {
        "Id": "latest-1",
        "Name": "Latest",
        "Type": "Movie",
        "RunTimeTicks": 600000000,
        "DateCreated": "2024-02-01T00:00:00Z",
        "ImageTags": { "Primary": "latest-tag" }
      }
    ]
    """.trimIndent()

private val findItemsJson =
    """
    {
      "Items": [
        {
          "Id": "movie-1",
          "Name": "Movie",
          "Type": "Movie",
          "RunTimeTicks": 36000000000
        },
        {
          "Id": "series-1",
          "Name": "Series",
          "Type": "Series",
          "RunTimeTicks": 18000000000
        },
        {
          "Id": "episode-long",
          "Name": "Long Episode",
          "Type": "Episode",
          "RunTimeTicks": 72000000000
        }
      ],
      "TotalRecordCount": 3
    }
    """.trimIndent()

private fun facetResultJson(
    id: String,
    name: String,
) = """
    {
      "Items": [
        { "Id": "$id", "Name": "$name" }
      ],
      "TotalRecordCount": 1
    }
    """.trimIndent()

private fun facetResultWithImageJson(
    id: String,
    name: String,
    primaryTag: String,
) = """
    {
      "Items": [
        { "Id": "$id", "Name": "$name", "ImageTags": { "Primary": "$primaryTag" } }
      ],
      "TotalRecordCount": 1
    }
    """.trimIndent()

private val libraryFilterOptionsJson =
    """
    {
      "OfficialRatings": ["PG", "TV-14"],
      "Tags": ["Favorite", "Award"],
      "Years": [2024, 2023]
    }
    """.trimIndent()
