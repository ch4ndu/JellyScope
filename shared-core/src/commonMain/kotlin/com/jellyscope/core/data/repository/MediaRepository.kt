// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.domain.model.DEFAULT_DISCOVERY_PAGE_SIZE
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.FindResults
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryFacet
import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibraryRecommendationRequest
import com.jellyscope.core.domain.model.LibraryRecommendationRow
import com.jellyscope.core.domain.model.LibraryShuffleRequest
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.MediaSuggestions
import com.jellyscope.core.domain.model.PagedItems
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.PersonFilmography
import com.jellyscope.core.domain.model.PersonHeader
import com.jellyscope.core.domain.model.RelatedGroup
import com.jellyscope.core.domain.model.RelatedGroupKind
import com.jellyscope.core.domain.model.SendClientLogsResult
import com.jellyscope.core.domain.playback.MediaSegment
import com.jellyscope.core.domain.playback.PlaybackInfo
import com.jellyscope.core.domain.playback.PlaybackInfoRequestPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface MediaRepository {
    suspend fun uploadClientLogs(content: String): SendClientLogsResult = SendClientLogsResult.Failure

    suspend fun getLibraries(): Result<List<Library>>

    suspend fun getContinueWatching(): Result<List<MediaItem>>

    suspend fun getNextUp(seriesId: String? = null): Result<List<MediaItem>>

    suspend fun getItemDetail(
        itemId: String,
        includePlaybackFields: Boolean = false,
    ): Result<MediaItemDetail>

    suspend fun getRelated(
        itemId: String,
        kind: MediaKind,
        seriesId: String?,
    ): Result<List<MediaItem>>

    // Titled related shelves for the detail screen. Consumers render only the
    // shared visible cap but collect through completion so cache population is
    // never cancelled. Default delegates to getRelated as a single "Similar"
    // group so existing fakes/tests keep working.
    fun getRelatedGroups(detail: MediaItemDetail): Flow<RelatedGroup> =
        flow {
            val items =
                getRelated(
                    itemId = detail.item.id,
                    kind = detail.item.kind,
                    seriesId = detail.item.seriesId,
                ).getOrDefault(emptyList())
            if (items.isNotEmpty()) {
                emit(RelatedGroup(kind = RelatedGroupKind.Similar, items = items))
            }
        }

    suspend fun getSeasons(seriesId: String): Result<List<MediaItem>>

    suspend fun getEpisodes(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int? = null,
    ): Result<List<MediaItem>>

    suspend fun getRecentlyAdded(): Result<List<MediaItem>>

    suspend fun getUpcomingEpisodes(): Result<List<MediaItem>> = Result.success(emptyList())

    suspend fun getLibraryItems(request: LibraryItemsRequest): Result<PagedItems> =
        Result.success(
            PagedItems(
                items = emptyList(),
                totalCount = 0,
                startIndex = request.startIndex.coerceAtLeast(0),
            ),
        )

    suspend fun getLibraryShuffleQueue(request: LibraryShuffleRequest): Result<List<String>> = Result.success(emptyList())

    suspend fun getGenres(parentId: String?): Result<List<LibraryFacet>> = Result.success(emptyList())

    suspend fun getStudios(parentId: String?): Result<List<LibraryFacet>> = Result.success(emptyList())

    suspend fun getLibraryFilters(parentId: String?): Result<LibraryFacets> = Result.success(LibraryFacets())

    suspend fun getLibraryRecommendationSection(request: LibraryRecommendationRequest): Result<List<LibraryRecommendationRow>> =
        Result.success(emptyList())

    suspend fun getMediaSegments(itemId: String): Result<List<MediaSegment>> = Result.success(emptyList())

    suspend fun getCollections(parentId: String? = null): Result<List<MediaItem>> = Result.success(emptyList())

    suspend fun getCollectionItems(
        collectionId: String,
        startIndex: Int = 0,
        limit: Int = DEFAULT_DISCOVERY_PAGE_SIZE,
    ): Result<PagedItems> = Result.success(PagedItems(emptyList(), totalCount = 0, startIndex = startIndex.coerceAtLeast(0)))

    suspend fun getGenreItems(
        genre: String,
        parentId: String? = null,
        startIndex: Int = 0,
        limit: Int = DEFAULT_DISCOVERY_PAGE_SIZE,
    ): Result<PagedItems> = Result.success(PagedItems(emptyList(), totalCount = 0, startIndex = startIndex.coerceAtLeast(0)))

    suspend fun getStudioItems(
        studioId: String,
        parentId: String? = null,
        startIndex: Int = 0,
        limit: Int = DEFAULT_DISCOVERY_PAGE_SIZE,
    ): Result<PagedItems> = Result.success(PagedItems(emptyList(), totalCount = 0, startIndex = startIndex.coerceAtLeast(0)))

    suspend fun getPerson(personId: String): Result<PersonHeader> =
        Result.failure(UnsupportedOperationException("Person headers are not implemented."))

    suspend fun getPersonItems(personId: String): Result<PersonFilmography> = Result.success(PersonFilmography())

    suspend fun getPersonItemsPage(
        personId: String,
        startIndex: Int = 0,
        limit: Int = DEFAULT_DISCOVERY_PAGE_SIZE,
    ): Result<PersonFilmography> = getPersonItems(personId)

    suspend fun getSuggestions(): Result<MediaSuggestions> = Result.success(MediaSuggestions(seedItem = null))

    suspend fun getItemsByIds(ids: List<String>): Result<List<MediaItem>> = Result.success(emptyList())

    suspend fun getRibbonItems(
        ribbon: MediaRibbon,
        limit: Int = 500,
    ): Result<List<MediaItem>>

    suspend fun getFavorites(): Result<List<MediaItem>>

    suspend fun search(query: FindQuery): Result<FindResults>

    suspend fun findPersons(term: String): Result<List<Person>>

    suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        maxStreamingBitrate: Long? = null,
    ): Result<PlaybackInfo>

    suspend fun getPlaybackInfo(
        itemId: String,
        mediaSourceId: String?,
        startTimeTicks: Long,
        audioStreamIndex: Int? = null,
        subtitleStreamIndex: Int? = null,
        maxStreamingBitrate: Long? = null,
        requestPolicy: PlaybackInfoRequestPolicy,
    ): Result<PlaybackInfo> =
        getPlaybackInfo(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            startTimeTicks = startTimeTicks,
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            maxStreamingBitrate = maxStreamingBitrate,
        )

    suspend fun setPlayed(
        itemId: String,
        played: Boolean,
    ): Result<Unit>

    suspend fun setFavorite(
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Favorite writes are not implemented."))
}
