// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.local.DetailRelatedCache
import com.jellyscope.core.data.local.DiscoveryCache
import com.jellyscope.core.data.local.PlayerDeviceSettingsStore
import com.jellyscope.core.data.remote.AuthenticatedRequestContext
import com.jellyscope.core.data.remote.ItemsQuery
import com.jellyscope.core.data.remote.JellyfinApi
import com.jellyscope.core.data.remote.JellyfinApiException
import com.jellyscope.core.data.remote.buildDeviceProfile
import com.jellyscope.core.data.remote.defaultItemFields
import com.jellyscope.core.data.remote.resolvedTranscodeReasons
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DEFAULT_DISCOVERY_PAGE_SIZE
import com.jellyscope.core.domain.model.FindProjection
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFacet
import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibraryRecommendationReason
import com.jellyscope.core.domain.model.LibraryRecommendationRequest
import com.jellyscope.core.domain.model.LibraryRecommendationRow
import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.core.domain.model.LibraryShuffleRequest
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaPersonType
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.MediaSuggestions
import com.jellyscope.core.domain.model.PagedItems
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.PersonFilmography
import com.jellyscope.core.domain.model.PersonHeader
import com.jellyscope.core.domain.model.RelatedGroup
import com.jellyscope.core.domain.model.RelatedGroupKind
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.SessionState
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.model.isUserLibrary
import com.jellyscope.core.domain.model.toDomainLibrary
import com.jellyscope.core.domain.model.toDomainLibraryFacet
import com.jellyscope.core.domain.model.toDomainMediaItem
import com.jellyscope.core.domain.model.toDomainMediaItemDetail
import com.jellyscope.core.domain.model.toDomainMediaSegment
import com.jellyscope.core.domain.model.toDomainPerson
import com.jellyscope.core.domain.model.toDomainPersonHeader
import com.jellyscope.core.domain.playback.DeviceProfileProvider
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.PlaybackBitrateConstraint
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import com.jellyscope.core.domain.playback.PlaybackMediaSourceInfo
import com.jellyscope.core.domain.playback.PlaybackMediaStream
import com.jellyscope.core.domain.playback.PlaybackPlanningException
import com.jellyscope.core.domain.playback.RepositoryOperation
import com.jellyscope.core.domain.playback.diagnosticClass
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.domain.playback.resolvePlayerDevicePolicy
import com.jellyscope.core.domain.playback.toExactBitrateConstraint
import com.jellyscope.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class DefaultMediaRepository(
    private val jellyfinApi: JellyfinApi,
    private val sessionRepository: SessionRepository,
    private val deviceProfileProvider: DeviceProfileProvider,
    private val playerDeviceSettingsStore: PlayerDeviceSettingsStore,
    private val discoveryCache: DiscoveryCache,
    private val detailRelatedCache: DetailRelatedCache,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaRepository {
    override suspend fun uploadClientLogs(content: String): SendClientLogsResult =
        withSession(operation = RepositoryOperation.UploadClientLogs) { context ->
            jellyfinApi.postClientLogDocument(context, content).filename
        }.fold(
            onSuccess = { filename -> SendClientLogsResult.Success(filename) },
            onFailure = { throwable ->
                if (throwable is JellyfinApiException.ClientLogUploadDisallowed) {
                    SendClientLogsResult.UploadDisallowed
                } else {
                    SendClientLogsResult.Failure
                }
            },
        )

    override suspend fun getLibraries(): Result<List<Library>> =
        withSession(operation = RepositoryOperation.GetLibraries) { context ->
            jellyfinApi
                .getUserViews(context)
                .items
                .mapNotNull { it.toDomainLibrary() }
        }

    override suspend fun getKidsCatalogue(
        expectedAccountIdentity: AccountIdentity,
        expectedBoundaryEpoch: Long,
    ): Result<List<MediaItem>> =
        withExpectedSession(
            expectedAccountIdentity = expectedAccountIdentity,
            expectedBoundaryEpoch = expectedBoundaryEpoch,
            operation = RepositoryOperation.GetKidsCatalogue,
        ) { session, context ->
            discoveryCache
                .getOrLoad(
                    accountIdentity = expectedAccountIdentity,
                    boundaryEpoch = expectedBoundaryEpoch,
                    key = KIDS_CATALOGUE_CACHE_KEY,
                ) {
                    loadKidsCatalogue(
                        session = session,
                        context = context,
                        expectedAccountIdentity = expectedAccountIdentity,
                        expectedBoundaryEpoch = expectedBoundaryEpoch,
                    )
                }.getOrThrow()
        }

    override suspend fun getContinueWatching(): Result<List<MediaItem>> =
        withSession(operation = RepositoryOperation.GetContinueWatching) { context ->
            jellyfinApi.getResumeItems(context).items.mapNotNull { it.toDomainMediaItem() }
        }

    override suspend fun getNextUp(
        seriesId: String?,
        includeResumable: Boolean,
    ): Result<List<MediaItem>> =
        withSession(operation = RepositoryOperation.GetNextUp) { context ->
            jellyfinApi
                .getNextUp(context, seriesId = seriesId, includeResumable = includeResumable)
                .items
                .mapNotNull { it.toDomainMediaItem() }
        }

    override suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean,
    ): Result<MediaItemDetail> =
        withSession(operation = RepositoryOperation.GetItemDetail) { context ->
            jellyfinApi.getItemDetail(context, itemId, includePlaybackFields).toDomainMediaItemDetail()
                ?: throw JellyfinApiException.NotReachable
        }

    override suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>> =
        withSession(operation = RepositoryOperation.GetRelated) { context ->
            val nextUpItems =
                if (kind == MediaKind.Episode && seriesId != null) {
                    jellyfinApi
                        .getNextUp(context, seriesId = seriesId)
                        .items
                        .mapNotNull { item -> item.toDomainMediaItem() }
                } else {
                    emptyList()
                }
            val nextUpIds = nextUpItems.map { item -> item.id }.toSet()
            val similarItems =
                jellyfinApi
                    .getSimilarItems(context, itemId, limit = RELATED_ITEM_LIMIT)
                    .items
                    .mapNotNull { item -> item.toDomainMediaItem() }
                    .filterNot { item -> item.id in nextUpIds }

            nextUpItems + similarItems
        }

    override fun getRelatedGroups(detail: MediaItemDetail): Flow<RelatedGroup> =
        flow {
            val snapshot = currentSessionSnapshot()
            val session = snapshot.session
            var emittedDuringLoad = false
            val result =
                detailRelatedCache.getOrLoad(
                    accountIdentity = session.accountIdentity(),
                    boundaryEpoch = snapshot.boundaryEpoch,
                    key = "related:${detail.item.id}",
                ) {
                    runCatchingCancellable {
                        val groups = mutableListOf<RelatedGroup>()
                        uncachedRelatedGroups(detail, session).collect { group ->
                            groups += group
                            emittedDuringLoad = true
                            emit(group)
                        }
                        groups.toList()
                    }.onFailure { throwable -> logRelatedGroupsFailure(throwable) }
                }

            val groups = result.getOrThrow()
            if (!emittedDuringLoad) {
                groups.forEach { group -> emit(group) }
            }
            // flowOn: this cold flow bypasses withSession and fans out parallel
            // fetches, so relocate its upstream (fetch + dedup/group + toDomainMediaItem
            // maps) off Main. All emits run in the relocated upstream coroutine.
        }.flowOn(dispatcher)

    private fun uncachedRelatedGroups(
        detail: MediaItemDetail,
        session: Session,
    ): Flow<RelatedGroup> =
        flow {
            val item = detail.item
            val context = session.toRequestContext()
            val itemTypes = listOf(relatedItemType(item.kind))
            // Dedup across shelves (and drop the current item) so a title never
            // repeats between the cast, "More Like This", genre, and studio rows.
            val seen = mutableSetOf(item.id)
            val isEpisode = item.kind == MediaKind.Episode

            // Cast/genre/studio angles only apply to standalone titles
            // (movies/series); an episode carries these on its series. The first two
            // cast members lead, then similar, then genre/studio.
            val jobs =
                buildList {
                    if (isEpisode && item.seriesId != null) {
                        add(
                            RelatedJob(RelatedGroupKind.NextUp, null) {
                                jellyfinApi.getNextUp(context, seriesId = item.seriesId).items
                            },
                        )
                    }
                    if (!isEpisode) {
                        detail.people
                            .filter { person -> person.type == MediaPersonType.Actor }
                            .take(RELATED_CAST_COUNT)
                            .forEach { person ->
                                add(
                                    // A person's filmography spans films AND shows.
                                    RelatedJob(RelatedGroupKind.Cast, person.name) {
                                        jellyfinApi
                                            .getItems(
                                                context,
                                                relatedQuery(RELATED_CAST_ITEM_TYPES, personIds = listOf(person.id)),
                                            ).items
                                    },
                                )
                            }
                    }
                    add(
                        RelatedJob(RelatedGroupKind.Similar, null) {
                            jellyfinApi.getSimilarItems(context, item.id, limit = RELATED_ITEM_LIMIT).items
                        },
                    )
                    if (!isEpisode) {
                        detail.genres.firstOrNull()?.let { genre ->
                            add(
                                RelatedJob(RelatedGroupKind.Genre, genre) {
                                    jellyfinApi.getItems(context, relatedQuery(itemTypes, genres = listOf(genre))).items
                                },
                            )
                        }
                        detail.studios.firstOrNull()?.let { studio ->
                            add(
                                RelatedJob(RelatedGroupKind.Studio, studio.name) {
                                    jellyfinApi.getItems(context, relatedQuery(itemTypes, studioIds = listOf(studio.id))).items
                                },
                            )
                        }
                    }
                }

            coroutineScope {
                // Launch every source in parallel, then await + emit in priority
                // order. A completed lower-priority job still waits for earlier jobs.
                val deferreds = jobs.map { job -> job to async { relatedFetch(job.fetch) } }
                for ((job, deferred) in deferreds) {
                    val fresh = deferred.await().filter { candidate -> seen.add(candidate.id) }.take(RELATED_ITEM_LIMIT)
                    if (fresh.isNotEmpty()) {
                        emit(RelatedGroup(kind = job.kind, label = job.label, items = fresh))
                    }
                }
            }
        }

    override suspend fun getSeasons(seriesId: String): Result<List<MediaItem>> =
        withSession(operation = RepositoryOperation.GetSeasons) { context ->
            jellyfinApi.getSeasons(context, seriesId).items.mapNotNull { it.toDomainMediaItem() }
        }

    override suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int?,
    ): Result<List<MediaItem>> =
        withSession(operation = RepositoryOperation.GetEpisodes) { context ->
            jellyfinApi
                .getEpisodes(context, seriesId, seasonId, seasonIndex)
                .items
                .filter { item -> item.shouldShowInSeasonEpisodeList(seasonId, seasonIndex) }
                .mapNotNull { it.toDomainMediaItem() }
        }

    override suspend fun getRecentlyAdded(): Result<List<MediaItem>> =
        withSession(operation = RepositoryOperation.GetRecentlyAdded) { context ->
            jellyfinApi.getLatestItems(context).mapNotNull { it.toDomainMediaItem() }
        }

    override suspend fun getUpcomingEpisodes(): Result<List<MediaItem>> {
        val snapshot = currentSessionSnapshot()
        val session = snapshot.session
        return discoveryCache.getOrLoad(
            accountIdentity = session.accountIdentity(),
            boundaryEpoch = snapshot.boundaryEpoch,
            key = "upcoming",
        ) {
            withSession(session, RepositoryOperation.GetUpcomingEpisodes) { context ->
                jellyfinApi
                    .getUpcomingEpisodes(context = context, fields = defaultItemFields)
                    .items
                    .mapNotNull { item -> item.toDomainMediaItem() }
            }
        }
    }

    private suspend fun getItems(
        query: ItemsQuery,
        operation: RepositoryOperation,
    ): Result<List<MediaItem>> = getPagedItems(query, operation).map { paged -> paged.items }

    private suspend fun getItems(
        query: ItemsQuery,
        session: Session,
        operation: RepositoryOperation,
    ): Result<List<MediaItem>> = getPagedItems(query, session, operation).map { paged -> paged.items }

    private suspend fun getPagedItems(
        query: ItemsQuery,
        operation: RepositoryOperation,
    ): Result<PagedItems> = getPagedItems(query, currentSession(), operation)

    private suspend fun getPagedItems(
        query: ItemsQuery,
        session: Session,
        operation: RepositoryOperation,
    ): Result<PagedItems> =
        withSession(session, operation) { context ->
            val result = jellyfinApi.getItems(context, query)
            val items =
                withContext(dispatcher) {
                    result.items.mapNotNull { it.toDomainMediaItem() }
                }
            PagedItems(
                items = items,
                totalCount = result.totalRecordCount,
                startIndex = query.startIndex ?: 0,
            )
        }

    override suspend fun getLibraryItems(request: LibraryItemsRequest): Result<PagedItems> =
        getPagedItems(request.toItemsQuery(), RepositoryOperation.GetLibraryItems)

    override suspend fun getLibraryShuffleQueue(request: LibraryShuffleRequest): Result<List<String>> =
        withSession(operation = RepositoryOperation.GetLibraryShuffleQueue) { context ->
            jellyfinApi
                .getItems(context, request.toItemsQuery())
                .items
                .mapNotNull { item -> item.id }
                .distinct()
        }

    override suspend fun getItemsByIds(ids: List<String>): Result<List<MediaItem>> {
        if (ids.isEmpty()) {
            return Result.success(emptyList())
        }
        return getItems(
            ItemsQuery(
                recursive = true,
                includeItemTypes = mediaRepositoryItemsByIdTypes,
                limit = ids.size,
                ids = ids,
            ),
            RepositoryOperation.GetItemsByIds,
        )
    }

    override suspend fun getGenres(parentId: String?): Result<List<LibraryFacet>> {
        val snapshot = currentSessionSnapshot()
        val session = snapshot.session
        return discoveryCache.getOrLoad(
            accountIdentity = session.accountIdentity(),
            boundaryEpoch = snapshot.boundaryEpoch,
            key = "genres:${parentId.orEmpty()}",
        ) {
            withSession(session, RepositoryOperation.GetGenres) { context ->
                jellyfinApi
                    .getGenres(context = context, parentId = parentId)
                    .items
                    .mapNotNull { facet -> facet.toDomainLibraryFacet() }
            }
        }
    }

    override suspend fun getStudios(parentId: String?): Result<List<LibraryFacet>> {
        val snapshot = currentSessionSnapshot()
        val session = snapshot.session
        return discoveryCache.getOrLoad(
            accountIdentity = session.accountIdentity(),
            boundaryEpoch = snapshot.boundaryEpoch,
            key = "studios:${parentId.orEmpty()}",
        ) {
            withSession(session, RepositoryOperation.GetStudios) { context ->
                jellyfinApi
                    .getStudios(context = context, parentId = parentId)
                    .items
                    .mapNotNull { facet -> facet.toDomainLibraryFacet() }
            }
        }
    }

    override suspend fun getLibraryFilters(parentId: String?): Result<LibraryFacets> =
        withSession(operation = RepositoryOperation.GetLibraryFilters) { context ->
            val genres =
                jellyfinApi
                    .getGenres(context = context, parentId = parentId)
                    .items
                    .mapNotNull { facet -> facet.toDomainLibraryFacet() }
            val studios =
                jellyfinApi
                    .getStudios(context = context, parentId = parentId)
                    .items
                    .mapNotNull { facet -> facet.toDomainLibraryFacet() }
            val options = jellyfinApi.getLibraryFilterOptions(context = context, parentId = parentId)

            LibraryFacets(
                genres = genres,
                studios = studios,
                officialRatings = options.officialRatings.filter { value -> value.isNotBlank() },
                tags = options.tags.filter { value -> value.isNotBlank() },
                years = options.years,
            )
        }

    override suspend fun getMediaSegments(itemId: String): Result<List<MediaSegment>> =
        withSession(operation = RepositoryOperation.GetMediaSegments) { context ->
            jellyfinApi
                .getMediaSegments(context = context, itemId = itemId)
                .mapNotNull { segment -> segment.toDomainMediaSegment() }
        }

    override suspend fun getLibraryRecommendationSection(request: LibraryRecommendationRequest): Result<List<LibraryRecommendationRow>> =
        withSession(operation = RepositoryOperation.GetLibraryRecommendationSection) { context ->
            when (request.section) {
                LibraryRecommendationSection.ContinueWatching -> {
                    val itemType = if (request.collectionType == LibraryCollectionType.TvShows) "Episode" else "Movie"
                    val items =
                        jellyfinApi
                            .getResumeItems(
                                context = context,
                                limit = request.limit,
                                parentId = request.parentId,
                                includeItemTypes = listOf(itemType),
                            ).items
                            .mapNotNull { item -> item.toDomainMediaItem() }
                            .distinctBy { item -> item.id }
                    listOf(
                        LibraryRecommendationRow(
                            key = "continue-watching",
                            section = request.section,
                            items = items,
                        ),
                    )
                }
                LibraryRecommendationSection.RecentlyAdded -> {
                    val itemType = if (request.collectionType == LibraryCollectionType.TvShows) "Episode" else "Movie"
                    val items =
                        jellyfinApi
                            .getLatestItems(
                                context = context,
                                limit = request.limit,
                                parentId = request.parentId,
                                includeItemTypes = listOf(itemType),
                            ).mapNotNull { item -> item.toDomainMediaItem() }
                            .distinctBy { item -> item.id }
                    listOf(
                        LibraryRecommendationRow(
                            key = "recently-added",
                            section = request.section,
                            items = items,
                        ),
                    )
                }
                LibraryRecommendationSection.NextUp -> {
                    val items =
                        jellyfinApi
                            .getNextUp(
                                context = context,
                                limit = request.limit,
                                parentId = request.parentId,
                            ).items
                            .mapNotNull { item -> item.toDomainMediaItem() }
                            .distinctBy { item -> item.id }
                    listOf(
                        LibraryRecommendationRow(
                            key = "next-up",
                            section = request.section,
                            items = items,
                        ),
                    )
                }
                LibraryRecommendationSection.MovieRecommendations ->
                    jellyfinApi
                        .getMovieRecommendations(
                            context = context,
                            parentId = request.parentId,
                            itemLimit = request.limit,
                        ).mapIndexedNotNull { index, recommendation ->
                            val items =
                                recommendation.items
                                    .mapNotNull { item -> item.toDomainMediaItem() }
                                    .distinctBy { item -> item.id }
                            if (items.isEmpty()) {
                                null
                            } else {
                                LibraryRecommendationRow(
                                    key = recommendation.categoryId ?: "movie-recommendation-$index",
                                    section = request.section,
                                    reason = recommendation.recommendationType.toDomainRecommendationReason(),
                                    baselineItemName = recommendation.baselineItemName,
                                    items = items,
                                )
                            }
                        }
            }
        }

    override suspend fun getCollections(parentId: String?): Result<List<MediaItem>> {
        val snapshot = currentSessionSnapshot()
        val session = snapshot.session
        return discoveryCache.getOrLoad(
            accountIdentity = session.accountIdentity(),
            boundaryEpoch = snapshot.boundaryEpoch,
            key = "collections:${parentId.orEmpty()}",
        ) {
            getItems(
                collectionsQuery.copy(parentId = parentId?.takeIf { id -> id.isNotBlank() }),
                session,
                RepositoryOperation.GetCollections,
            )
        }
    }

    override suspend fun getCollectionItems(
        collectionId: String,
        startIndex: Int,
        limit: Int,
    ): Result<PagedItems> =
        getPagedItems(
            collectionItemsQuery.copy(
                parentId = collectionId,
                startIndex = startIndex.coerceAtLeast(0),
                limit = limit,
            ),
            RepositoryOperation.GetCollectionItems,
        )

    override suspend fun getGenreItems(
        genre: String,
        parentId: String?,
        startIndex: Int,
        limit: Int,
    ): Result<PagedItems> =
        getPagedItems(
            discoveryItemsQuery.copy(
                parentId = parentId?.takeIf { id -> id.isNotBlank() },
                startIndex = startIndex.coerceAtLeast(0),
                limit = limit,
                genres = listOf(genre).filter { value -> value.isNotBlank() },
            ),
            RepositoryOperation.GetGenreItems,
        )

    override suspend fun getStudioItems(
        studioId: String,
        parentId: String?,
        startIndex: Int,
        limit: Int,
    ): Result<PagedItems> =
        getPagedItems(
            discoveryItemsQuery.copy(
                parentId = parentId?.takeIf { id -> id.isNotBlank() },
                startIndex = startIndex.coerceAtLeast(0),
                limit = limit,
                studioIds = listOf(studioId).filter { value -> value.isNotBlank() },
            ),
            RepositoryOperation.GetStudioItems,
        )

    override suspend fun getPerson(personId: String): Result<PersonHeader> =
        if (personId.isBlank()) {
            Result.failure(IllegalArgumentException("personId is required."))
        } else {
            withSession(operation = RepositoryOperation.GetPerson) { context ->
                jellyfinApi.getItemDetail(context, personId).toDomainPersonHeader()
                    ?: throw JellyfinApiException.NotReachable
            }
        }

    override suspend fun getPersonItems(personId: String): Result<PersonFilmography> =
        getPersonItemsPage(
            personId = personId,
            startIndex = 0,
            limit = PERSON_FILMOGRAPHY_LIMIT,
            operation = RepositoryOperation.GetPersonItems,
        )

    override suspend fun getPersonItemsPage(
        personId: String,
        startIndex: Int,
        limit: Int,
    ): Result<PersonFilmography> =
        getPersonItemsPage(
            personId = personId,
            startIndex = startIndex,
            limit = limit,
            operation = RepositoryOperation.GetPersonItemsPage,
        )

    private suspend fun getPersonItemsPage(
        personId: String,
        startIndex: Int,
        limit: Int,
        operation: RepositoryOperation,
    ): Result<PersonFilmography> =
        if (personId.isBlank()) {
            Result.success(PersonFilmography(startIndex = startIndex.coerceAtLeast(0)))
        } else {
            getPagedItems(
                personFilmographyQuery.copy(
                    personIds = listOf(personId),
                    startIndex = startIndex.coerceAtLeast(0),
                    limit = limit,
                ),
                operation,
            ).map { page ->
                PersonFilmography(
                    movies = page.items.filter { item -> item.kind == MediaKind.Movie },
                    series = page.items.filter { item -> item.kind == MediaKind.Series },
                    totalCount = page.totalCount,
                    startIndex = page.startIndex,
                )
            }
        }

    override suspend fun getSuggestions(): Result<MediaSuggestions> {
        val snapshot = currentSessionSnapshot()
        val session = snapshot.session
        return discoveryCache.getOrLoad(
            accountIdentity = session.accountIdentity(),
            boundaryEpoch = snapshot.boundaryEpoch,
            key = "suggestions",
        ) {
            withSession(session, RepositoryOperation.GetSuggestions) { context ->
                val seedItem =
                    jellyfinApi
                        .getResumeItems(
                            context = context,
                            limit = 1,
                            fields = defaultItemFields,
                        ).items
                        .firstNotNullOfOrNull { item -> item.toDomainMediaItem() }
                val suggestedItems =
                    seedItem
                        ?.let { seed ->
                            jellyfinApi
                                .getSimilarItems(
                                    context = context,
                                    itemId = seed.id,
                                    limit = SUGGESTIONS_LIMIT,
                                ).items
                                .mapNotNull { item -> item.toDomainMediaItem() }
                                .filterNot { item -> item.id == seed.id }
                        }.orEmpty()

                MediaSuggestions(
                    seedItem = seedItem,
                    items = suggestedItems,
                )
            }
        }
    }

    override suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int,
    ): Result<List<MediaItem>> =
        when (ribbon) {
            MediaRibbon.ContinueWatching ->
                withSession(operation = RepositoryOperation.GetRibbonItems) { context ->
                    jellyfinApi
                        .getResumeItems(
                            context = context,
                            limit = limit,
                            fields = defaultItemFields,
                        ).items
                        .mapNotNull { it.toDomainMediaItem() }
                }
            MediaRibbon.Favorites ->
                getItems(
                    favoritesQuery.copy(limit = limit, fields = defaultItemFields),
                    RepositoryOperation.GetRibbonItems,
                )
            MediaRibbon.NextUp ->
                withSession(operation = RepositoryOperation.GetRibbonItems) { context ->
                    jellyfinApi
                        .getNextUp(
                            context = context,
                            includeResumable = false,
                            limit = limit,
                            fields = defaultItemFields,
                        ).items
                        .mapNotNull { it.toDomainMediaItem() }
                }
            MediaRibbon.RecentlyAdded ->
                withSession(operation = RepositoryOperation.GetRibbonItems) { context ->
                    jellyfinApi
                        .getLatestItems(
                            context = context,
                            limit = limit,
                            fields = defaultItemFields,
                        ).mapNotNull { it.toDomainMediaItem() }
                }
        }

    override suspend fun getFavorites(): Result<List<MediaItem>> = getItems(favoritesQuery, RepositoryOperation.GetFavorites)

    override suspend fun search(query: FindQuery): Result<FindResults> =
        withSession(operation = RepositoryOperation.Search) { context ->
            val items =
                coroutineScope {
                    query.mediaKinds
                        .distinct()
                        .map { kind ->
                            async {
                                jellyfinApi
                                    .getItems(context, query.copy(mediaKinds = listOf(kind)).toItemsQuery())
                                    .items
                                    .mapNotNull { item -> item.toDomainMediaItem() }
                            }
                        }.flatMap { request -> request.await() }
                }.distinctBy { item -> item.id }
            FindProjection.project(items, query.runtimeBucket)
        }

    override suspend fun findPersons(term: String): Result<List<Person>> =
        withSession(operation = RepositoryOperation.FindPersons) { context ->
            jellyfinApi
                .getPersons(context, searchTerm = term.trim(), limit = PERSON_SUGGESTION_LIMIT)
                .items
                .mapNotNull { person -> person.toDomainPerson() }
                // Jellyfin returns the same person more than once when they are
                // reachable through multiple library folders (same server
                // behavior as duplicate search items); UIs key suggestion rows
                // by person id, and a duplicate key crashes lazy layouts.
                .distinctBy { person -> person.id }
        }

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
    ): Result<PlaybackInfo> =
        getPlaybackInfo(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            startTimeTicks = startTimeTicks,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            maxStreamingBitrate = maxStreamingBitrate,
            requestPolicy = PlaybackInfoRequestPolicy(),
        )

    override suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxStreamingBitrate: Long?,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfo> =
        withPlaybackSession(RepositoryOperation.GetPlaybackInfo, requestPolicy) { context ->
            val effectiveBitrateConstraint =
                requestPolicy.bitrateConstraint.takeUnless { constraint ->
                    constraint == PlaybackBitrateConstraint.NoClientLimit
                } ?: maxStreamingBitrate.toExactBitrateConstraint()
            val capabilities = deviceProfileProvider.capabilities(requestPolicy.backend)
            val storedPlayerDeviceSettings = playerDeviceSettingsStore.settings.value
            val effectiveRequestPolicy =
                requestPolicy.copy(
                    bitrateConstraint = effectiveBitrateConstraint,
                    userVideoResolutionCap =
                        if (requestPolicy.userVideoResolutionCapIsResolved) {
                            requestPolicy.userVideoResolutionCap
                        } else {
                            requestPolicy.userVideoResolutionCap ?: storedPlayerDeviceSettings.maxVideoResolution.resolutionCap
                        },
                    userVideoResolutionCapIsResolved = true,
                )
            val playerDevicePolicy =
                resolvePlayerDevicePolicy(
                    capabilities = capabilities,
                    settings = storedPlayerDeviceSettings,
                )
            jellyfinApi
                .getPlaybackInfo(
                    context = context,
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    startTimeTicks = startTimeTicks,
                    deviceProfile =
                        buildDeviceProfile(
                            capabilities = playerDevicePolicy.capabilities,
                            maxStreamingBitrate = maxStreamingBitrate,
                            playerSettings = playerDevicePolicy.settings,
                            requestPolicy = effectiveRequestPolicy,
                            bitrateConstraint = effectiveBitrateConstraint,
                        ),
                    playerDevicePolicy = playerDevicePolicy,
                    requestPolicy = effectiveRequestPolicy,
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = subtitleStreamIndex,
                    maxStreamingBitrate = effectiveBitrateConstraint.bitrateBps,
                ).let { response ->
                    PlaybackInfo(
                        playSessionId = response.playSessionId,
                        mediaSources =
                            response.mediaSources.map { source ->
                                PlaybackMediaSourceInfo(
                                    id = source.id,
                                    supportsDirectPlay = source.supportsDirectPlay,
                                    supportsDirectStream = source.supportsDirectStream,
                                    supportsTranscoding = source.supportsTranscoding,
                                    transcodingUrl = source.transcodingUrl,
                                    container = source.container,
                                    transcodingContainer = source.transcodingContainer,
                                    transcodingSubProtocol = source.transcodingSubProtocol,
                                    defaultAudioStreamIndex = source.defaultAudioStreamIndex,
                                    defaultSubtitleStreamIndex = source.defaultSubtitleStreamIndex,
                                    bitrate = source.bitrate,
                                    transcodeReasons = source.resolvedTranscodeReasons(),
                                    mediaStreams =
                                        source.mediaStreams.map { stream ->
                                            PlaybackMediaStream(
                                                index = stream.index,
                                                type = stream.type,
                                                displayTitle = stream.displayTitle,
                                                title = stream.title,
                                                language = stream.language,
                                                codec = stream.codec,
                                                channelLayout = stream.channelLayout,
                                                bitRate = stream.bitRate,
                                                height = stream.height,
                                                isDefault = stream.isDefault,
                                                isExternal = stream.isExternal,
                                                deliveryMethod = stream.deliveryMethod,
                                                deliveryUrl = stream.deliveryUrl,
                                                width = stream.width,
                                                realFrameRate = (stream.realFrameRate ?: stream.averageFrameRate)?.toDouble(),
                                                videoRangeType = stream.videoRangeType,
                                                bitDepth = stream.bitDepth,
                                            )
                                        },
                                )
                            },
                    )
                }
        }

    private suspend fun <T> withPlaybackSession(
        operation: RepositoryOperation,
        requestPolicy: PlaybackInfoRequestPolicy,
        block: suspend (AuthenticatedRequestContext) -> T,
    ): Result<T> =
        runCatchingCancellable {
            // Off-main for the same reason as withSession: fetch + decode + mapping.
            withContext(dispatcher) {
                block(currentSession().toRequestContext())
            }
        }.onFailure { throwable ->
            MEDIA_LOGGER.w {
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Repository,
                        event = PlaybackDiagnosticEvent.Failed,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        exceptionType = throwable.playbackExceptionType(),
                        operation = operation,
                        requestPolicy = requestPolicy.diagnosticClass(),
                        clientTrigger = requestPolicy.clientTrigger,
                    ),
                )
            }
        }.fold(
            onSuccess = { value -> Result.success(value) },
            onFailure = { throwable -> Result.failure(throwable.toPlaybackPlanningFailure()) },
        )

    override suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit> =
        withSession(operation = RepositoryOperation.SetPlayed) { context ->
            if (played) {
                jellyfinApi.markItemPlayed(context, itemId)
            } else {
                jellyfinApi.markItemUnplayed(context, itemId)
            }
        }

    override suspend fun setFavorite(
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> =
        withSession(operation = RepositoryOperation.SetFavorite) { context ->
            if (favorite) {
                jellyfinApi.setItemFavorite(context, itemId)
            } else {
                jellyfinApi.unsetItemFavorite(context, itemId)
            }
        }

    private suspend fun loadKidsCatalogue(
        session: Session,
        context: AuthenticatedRequestContext,
        expectedAccountIdentity: AccountIdentity,
        expectedBoundaryEpoch: Long,
    ): Result<List<MediaItem>> =
        runCatchingCancellable {
            requireExpectedSession(
                session = session,
                expectedAccountIdentity = expectedAccountIdentity,
                expectedBoundaryEpoch = expectedBoundaryEpoch,
            )
            val libraries =
                jellyfinApi
                    .getUserViews(context)
                    .items
                    .mapNotNull { view -> view.toDomainLibrary() }
                    .filter { library ->
                        library.collectionType.isUserLibrary &&
                            library.collectionType != LibraryCollectionType.Music
                    }
            val itemsById = LinkedHashMap<String, MediaItem>()
            libraries.forEach { library ->
                var startIndex = 0
                while (true) {
                    requireExpectedSession(
                        session = session,
                        expectedAccountIdentity = expectedAccountIdentity,
                        expectedBoundaryEpoch = expectedBoundaryEpoch,
                    )
                    val page = jellyfinApi.getItems(context, kidsCatalogueItemsQuery(library.id, startIndex))
                    page.items
                        .mapNotNull { item -> item.toDomainMediaItem() }
                        .filter { item -> item.kind == MediaKind.Movie || item.kind == MediaKind.Episode }
                        .forEach { item ->
                            if (item.id !in itemsById) {
                                itemsById[item.id] = item
                            }
                        }
                    if (page.items.size < DEFAULT_DISCOVERY_PAGE_SIZE) {
                        break
                    }
                    startIndex += DEFAULT_DISCOVERY_PAGE_SIZE
                }
            }
            requireExpectedSession(
                session = session,
                expectedAccountIdentity = expectedAccountIdentity,
                expectedBoundaryEpoch = expectedBoundaryEpoch,
            )
            itemsById.values.toList()
        }

    private fun requireExpectedSession(
        session: Session,
        expectedAccountIdentity: AccountIdentity,
        expectedBoundaryEpoch: Long,
    ) {
        val current = currentSessionSnapshot()
        if (current.boundaryEpoch != expectedBoundaryEpoch ||
            current.session != session ||
            current.session.accountIdentity() != expectedAccountIdentity
        ) {
            throw JellyfinApiException.Unauthorized
        }
    }

    private suspend fun <T> withExpectedSession(
        expectedAccountIdentity: AccountIdentity,
        expectedBoundaryEpoch: Long,
        operation: RepositoryOperation,
        block: suspend (Session, AuthenticatedRequestContext) -> T,
    ): Result<T> =
        runCatchingCancellable {
            val session = currentSessionSnapshot().session
            requireExpectedSession(session, expectedAccountIdentity, expectedBoundaryEpoch)
            withContext(dispatcher) {
                requireExpectedSession(session, expectedAccountIdentity, expectedBoundaryEpoch)
                val result = block(session, session.toRequestContext())
                requireExpectedSession(session, expectedAccountIdentity, expectedBoundaryEpoch)
                result
            }
        }.onFailure { throwable ->
            MEDIA_LOGGER.w {
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Repository,
                        event = PlaybackDiagnosticEvent.Failed,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        exceptionType = throwable.playbackExceptionType(),
                        operation = operation,
                    ),
                )
            }
        }

    private suspend fun <T> withSession(
        operation: RepositoryOperation,
        block: suspend (AuthenticatedRequestContext) -> T,
    ): Result<T> = withSession(currentSession(), operation, block)

    private suspend fun <T> withSession(
        session: Session,
        operation: RepositoryOperation,
        block: suspend (AuthenticatedRequestContext) -> T,
    ): Result<T> =
        runCatchingCancellable {
            // Run fetch + JSON decode + DTO->domain mapping off the main thread. The
            // caller (e.g. a ViewModel's viewModelScope.launch) is on Main.immediate,
            // and neither withSession nor the Ktor decode hops dispatchers, so without
            // this a large payload (e.g. a 1200-episode season) decodes and maps as one
            // uninterrupted CPU burst on Main and ANRs. dispatcher is Default (CPU work).
            withContext(dispatcher) {
                block(session.toRequestContext())
            }
        }.onFailure { throwable ->
            MEDIA_LOGGER.w {
                formatPlaybackDiagnostic(
                    PlaybackDiagnostic(
                        stage = PlaybackDiagnosticStage.Repository,
                        event = PlaybackDiagnosticEvent.Failed,
                        platform = PlaybackDiagnosticPlatform.Shared,
                        exceptionType = throwable.playbackExceptionType(),
                        operation = operation,
                    ),
                )
            }
        }

    private fun currentSession(): Session = currentSessionSnapshot().session

    private fun currentSessionSnapshot(): AccountSessionSnapshot =
        when (val state = sessionRepository.sessionState.value) {
            is SessionState.LoggedIn -> AccountSessionSnapshot(state.session, state.boundaryEpoch)
            is SessionState.LoggedOut, SessionState.Restoring -> throw JellyfinApiException.Unauthorized
        }

    private fun Session.toRequestContext(): AuthenticatedRequestContext =
        AuthenticatedRequestContext(
            serverUrl = serverUrl,
            userId = userId,
            accessToken = accessToken,
        )
}

private fun Throwable.toPlaybackPlanningFailure(): Throwable =
    if (this is JellyfinApiException) {
        PlaybackPlanningException.RemoteRequestFailed(
            isNetworkFailure = this !is JellyfinApiException.Unexpected,
            sourceExceptionType = playbackExceptionType(),
        )
    } else {
        this
    }

private data class AccountSessionSnapshot(
    val session: Session,
    val boundaryEpoch: Long,
)

private const val KIDS_CATALOGUE_CACHE_KEY = "kids-catalogue"

private fun String?.toDomainRecommendationReason(): LibraryRecommendationReason =
    when (this) {
        "SimilarToRecentlyPlayed" -> LibraryRecommendationReason.SimilarToRecentlyPlayed
        "SimilarToLikedItem" -> LibraryRecommendationReason.SimilarToLikedItem
        "HasDirectorFromRecentlyPlayed",
        "HasLikedDirector",
        -> LibraryRecommendationReason.HasDirector
        "HasActorFromRecentlyPlayed",
        "HasLikedActor",
        -> LibraryRecommendationReason.HasActor
        else -> LibraryRecommendationReason.Other
    }
