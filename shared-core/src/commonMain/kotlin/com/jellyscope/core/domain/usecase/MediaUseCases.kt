// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.usecase

import com.jellyscope.core.data.repository.MediaRepository
import com.jellyscope.core.domain.model.AccountIdentity
import com.jellyscope.core.domain.model.DEFAULT_DISCOVERY_PAGE_SIZE
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibraryRecommendationRequest
import com.jellyscope.core.domain.model.LibraryShuffleRequest
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaItemDetail
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.MediaRibbon
import com.jellyscope.core.domain.model.RELATED_GROUP_DISPLAY_LIMIT
import com.jellyscope.core.domain.model.RelatedGroup
import com.jellyscope.core.domain.model.isUserLibrary
import com.jellyscope.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

// Only the user's real media libraries (for navigation chrome); drops auto/system
// views like collections, playlists, folders, and live TV.
class GetUserLibrariesUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(): Result<List<Library>> =
        mediaRepository.getLibraries().map { libraries ->
            libraries.filter { library -> library.collectionType.isUserLibrary }
        }
}

class GetKidsCatalogueUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        expectedAccountIdentity: AccountIdentity,
        expectedBoundaryEpoch: Long,
    ): Result<List<MediaItem>> = mediaRepository.getKidsCatalogue(expectedAccountIdentity, expectedBoundaryEpoch)
}

class GetContinueWatchingUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke() = mediaRepository.getContinueWatching()
}

class GetNextUpUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        seriesId: String? = null,
        includeResumable: Boolean = true,
    ) = mediaRepository.getNextUp(seriesId = seriesId, includeResumable = includeResumable)
}

class GetItemDetailUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        itemId: String,
        includePlaybackFields: Boolean = false,
    ) = mediaRepository.getItemDetail(itemId, includePlaybackFields)
}

class GetRelatedItemsUseCase(
    private val mediaRepository: MediaRepository,
) {
    // Returns titled related shelves for the already-loaded detail. The
    // repository starts source jobs together but emits their non-empty results
    // in priority order, so a lower-priority shelf cannot overtake an earlier one.
    operator fun invoke(detail: MediaItemDetail) = mediaRepository.getRelatedGroups(detail)
}

/** Keeps the visible shelf cap without cancelling cache-backed related flows. */
fun Flow<RelatedGroup>.visibleRelatedGroups(): Flow<RelatedGroup> =
    flow {
        var renderedGroups = 0
        collect { group ->
            if (group.items.isNotEmpty() && renderedGroups < RELATED_GROUP_DISPLAY_LIMIT) {
                emit(group)
                renderedGroups += 1
            }
        }
    }

class GetSeriesSeasonsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(seriesId: String) = mediaRepository.getSeasons(seriesId)
}

class GetSeasonEpisodesUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        seriesId: String,
        seasonId: String,
        seasonIndex: Int? = null,
    ) = mediaRepository.getEpisodes(
        seriesId = seriesId,
        seasonId = seasonId,
        seasonIndex = seasonIndex,
    )
}

class GetChronologicalEpisodeQueueUseCase(
    private val getSeriesSeasonsUseCase: GetSeriesSeasonsUseCase,
    private val getSeasonEpisodesUseCase: GetSeasonEpisodesUseCase,
) {
    suspend operator fun invoke(currentEpisode: MediaItem): Result<List<MediaItem>> =
        runCatchingCancellable {
            if (currentEpisode.kind != MediaKind.Episode) {
                return@runCatchingCancellable emptyList()
            }
            val seriesId =
                currentEpisode.seriesId?.takeIf { id -> id.isNotBlank() }
                    ?: return@runCatchingCancellable emptyList()
            val seasons =
                getSeriesSeasonsUseCase(seriesId)
                    .getOrThrow()
                    .sortedSeasonsChronologically()
            val startSeasonIndex = seasons.startSeasonIndexFor(currentEpisode)
            if (startSeasonIndex < 0) {
                return@runCatchingCancellable emptyList()
            }

            val queue = mutableListOf<MediaItem>()
            seasons
                .drop(startSeasonIndex)
                .forEachIndexed { offset, season ->
                    val episodes =
                        getSeasonEpisodesUseCase(
                            seriesId = seriesId,
                            seasonId = season.id,
                            seasonIndex = season.indexNumber,
                        ).getOrThrow()
                            .filter { item -> item.kind == MediaKind.Episode }
                            .sortedEpisodesChronologically()
                    queue +=
                        if (offset == 0) {
                            episodes.suffixFromCurrentEpisode(currentEpisode)
                        } else {
                            episodes
                        }
                }

            val queueWithCurrent =
                if (queue.firstOrNull()?.id == currentEpisode.id) {
                    queue
                } else {
                    listOf(currentEpisode) + queue.filterNot { item -> item.id == currentEpisode.id }
                }
            queueWithCurrent.distinctByItemId()
        }
}

class GetRecentlyAddedUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke() = mediaRepository.getRecentlyAdded()
}

class GetUpcomingEpisodesUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke() = mediaRepository.getUpcomingEpisodes()
}

class GetFavoritesUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke() = mediaRepository.getFavorites()
}

class GetRibbonItemsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        ribbon: MediaRibbon,
        limit: Int = 500,
    ) = mediaRepository.getRibbonItems(ribbon = ribbon, limit = limit)
}

class GetLibraryItemsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(request: LibraryItemsRequest) = mediaRepository.getLibraryItems(request)
}

class GetLibraryShuffleQueueUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(request: LibraryShuffleRequest) = mediaRepository.getLibraryShuffleQueue(request)
}

class GetLibraryFiltersUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(parentId: String?) = mediaRepository.getLibraryFilters(parentId)
}

class GetGenresUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(parentId: String? = null) = mediaRepository.getGenres(parentId)
}

class GetStudiosUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(parentId: String? = null) = mediaRepository.getStudios(parentId)
}

class GetMediaSegmentsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(itemId: String) = mediaRepository.getMediaSegments(itemId)
}

class GetCollectionsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(parentId: String? = null) = mediaRepository.getCollections(parentId)
}

class GetLibraryRecommendationSectionUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(request: LibraryRecommendationRequest) = mediaRepository.getLibraryRecommendationSection(request)
}

class GetCollectionItemsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        collectionId: String,
        startIndex: Int = 0,
        limit: Int = DEFAULT_DISCOVERY_PAGE_SIZE,
    ) = mediaRepository.getCollectionItems(collectionId = collectionId, startIndex = startIndex, limit = limit)
}

class GetGenreItemsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        genre: String,
        parentId: String? = null,
        startIndex: Int = 0,
        limit: Int = DEFAULT_DISCOVERY_PAGE_SIZE,
    ) = mediaRepository.getGenreItems(genre = genre, parentId = parentId, startIndex = startIndex, limit = limit)
}

class GetStudioItemsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        studioId: String,
        parentId: String? = null,
        startIndex: Int = 0,
        limit: Int = DEFAULT_DISCOVERY_PAGE_SIZE,
    ) = mediaRepository.getStudioItems(studioId = studioId, parentId = parentId, startIndex = startIndex, limit = limit)
}

class GetPersonItemsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(personId: String) = mediaRepository.getPersonItems(personId)
}

class GetPersonUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(personId: String) = mediaRepository.getPerson(personId)
}

class GetPersonItemsPageUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(
        personId: String,
        startIndex: Int = 0,
        limit: Int = DEFAULT_DISCOVERY_PAGE_SIZE,
    ) = mediaRepository.getPersonItemsPage(personId = personId, startIndex = startIndex, limit = limit)
}

class GetSuggestionsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke() = mediaRepository.getSuggestions()
}

class GetItemsByIdsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(ids: List<String>) = mediaRepository.getItemsByIds(ids)
}

private fun List<MediaItem>.sortedSeasonsChronologically(): List<MediaItem> =
    sortedWith(
        compareBy<MediaItem> { item -> item.indexNumber ?: Int.MAX_VALUE }
            .thenBy { item -> item.name },
    )

private fun List<MediaItem>.sortedEpisodesChronologically(): List<MediaItem> =
    sortedWith(
        compareBy<MediaItem> { item -> item.parentIndexNumber ?: Int.MAX_VALUE }
            .thenBy { item -> item.indexNumber ?: Int.MAX_VALUE }
            .thenBy { item -> item.name },
    )

private fun List<MediaItem>.startSeasonIndexFor(currentEpisode: MediaItem): Int {
    val currentSeasonId = currentEpisode.seasonId?.takeIf { id -> id.isNotBlank() }
    val indexBySeasonId =
        currentSeasonId
            ?.let { seasonId -> indexOfFirst { season -> season.id == seasonId } }
            ?.takeIf { index -> index >= 0 }
    if (indexBySeasonId != null) {
        return indexBySeasonId
    }

    val currentSeasonNumber = currentEpisode.parentIndexNumber ?: return -1
    return indexOfFirst { season -> season.indexNumber == currentSeasonNumber }
}

private fun List<MediaItem>.suffixFromCurrentEpisode(currentEpisode: MediaItem): List<MediaItem> {
    val currentIndex = indexOfFirst { item -> item.id == currentEpisode.id }
    if (currentIndex >= 0) {
        return drop(currentIndex)
    }

    val currentEpisodeNumber = currentEpisode.indexNumber ?: return emptyList()
    return filter { item ->
        val episodeNumber = item.indexNumber
        episodeNumber != null && episodeNumber > currentEpisodeNumber
    }
}

private fun List<MediaItem>.distinctByItemId(): List<MediaItem> {
    val seen = mutableSetOf<String>()
    return filter { item -> seen.add(item.id) }
}

class SearchLibraryUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(query: FindQuery) = mediaRepository.search(query)
}

class FindPersonsUseCase(
    private val mediaRepository: MediaRepository,
) {
    suspend operator fun invoke(term: String) = mediaRepository.findPersons(term)
}
