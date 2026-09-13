// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.remote.BaseItemDto
import com.jellyscope.core.data.remote.ItemFilter
import com.jellyscope.core.data.remote.ItemsQuery
import com.jellyscope.core.domain.model.DEFAULT_DISCOVERY_PAGE_SIZE
import com.jellyscope.core.domain.model.FindMediaKind
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemFilter
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibrarySeriesStatus
import com.jellyscope.core.domain.model.LibraryShuffleRequest
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.MediaItem
import com.jellyscope.core.domain.model.MediaKind
import com.jellyscope.core.domain.model.RelatedGroupKind
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.core.domain.model.toDomainMediaItem
import com.jellyscope.core.domain.playback.PlaybackDiagnostic
import com.jellyscope.core.domain.playback.PlaybackDiagnosticEvent
import com.jellyscope.core.domain.playback.PlaybackDiagnosticPlatform
import com.jellyscope.core.domain.playback.PlaybackDiagnosticStage
import com.jellyscope.core.domain.playback.RepositoryOperation
import com.jellyscope.core.domain.playback.formatPlaybackDiagnostic
import com.jellyscope.core.domain.playback.playbackExceptionType
import com.jellyscope.core.util.DiagnosticTag
import com.jellyscope.core.util.diagnosticLogger
import com.jellyscope.core.util.runCatchingCancellable

internal val favoritesQuery =
    ItemsQuery(
        recursive = true,
        includeItemTypes = listOf("Movie", "Series", "Episode"),
        sortBy = "DateCreated",
        sortOrder = "Descending",
        limit = 20,
        filters = listOf(ItemFilter.IsFavorite),
    )

internal fun FindQuery.toItemsQuery(): ItemsQuery =
    ItemsQuery(
        recursive = true,
        includeItemTypes = mediaKinds.map { kind -> kind.toApiValue() },
        limit = 60,
        searchTerm = text.trim().takeIf { it.isNotBlank() },
        personIds = listOfNotNull(personId?.takeIf { it.isNotBlank() }),
        genres = genreNames.filter { genre -> genre.isNotBlank() },
        years = listOfNotNull(year),
        filters =
            when (watchedFilter) {
                WatchedFilter.Any -> emptyList()
                WatchedFilter.Unwatched -> listOf(ItemFilter.IsUnplayed)
                WatchedFilter.InProgress -> listOf(ItemFilter.IsResumable)
            },
    )

internal fun LibraryItemsRequest.toItemsQuery(): ItemsQuery =
    ItemsQuery(
        recursive = true,
        // Skip the expensive exact count; the ViewModel paginates by page
        // fullness. (Large genre/library counts can take as long as the query.)
        enableTotalRecordCount = false,
        includeItemTypes = libraryBrowseItemTypes,
        limit = limit,
        parentId = parentId?.takeIf { it.isNotBlank() },
        startIndex = startIndex.coerceAtLeast(0),
        sortBy = sortBy.toApiValue(),
        sortOrder = sortOrder.toApiValue(),
        genres = filters.genres.filter { value -> value.isNotBlank() },
        genreIds = filters.genreIds.filter { value -> value.isNotBlank() },
        years = filters.years,
        officialRatings = filters.officialRatings.filter { value -> value.isNotBlank() },
        studioIds = filters.studioIds.filter { value -> value.isNotBlank() },
        tags = filters.tags.filter { value -> value.isNotBlank() },
        seriesStatus = filters.seriesStatus.map { status -> status.toApiValue() },
        filters = filters.toItemFilters(),
    )

internal fun LibraryShuffleRequest.toItemsQuery(): ItemsQuery =
    ItemsQuery(
        recursive = true,
        includeItemTypes = listOf("Movie"),
        limit = null,
        parentId = parentId?.takeIf { it.isNotBlank() },
        sortBy = "Random",
        fields = emptyList(),
        enableImageTypes = emptyList(),
        imageTypeLimit = 0,
        enableTotalRecordCount = false,
        enableUserData = false,
        genres = filters.genres.filter { value -> value.isNotBlank() },
        genreIds = filters.genreIds.filter { value -> value.isNotBlank() },
        years = filters.years,
        officialRatings = filters.officialRatings.filter { value -> value.isNotBlank() },
        studioIds = filters.studioIds.filter { value -> value.isNotBlank() },
        tags = filters.tags.filter { value -> value.isNotBlank() },
        seriesStatus = filters.seriesStatus.map { status -> status.toApiValue() },
        filters = filters.toItemFilters(),
    )

private fun FindMediaKind.toApiValue(): String =
    when (this) {
        FindMediaKind.Movies -> "Movie"
        FindMediaKind.Shows -> "Series"
        FindMediaKind.Episodes -> "Episode"
    }

private fun LibrarySortBy.toApiValue(): String = name

private fun LibrarySortOrder.toApiValue(): String = name

private fun LibrarySeriesStatus.toApiValue(): String = name

private fun LibraryFilterSelection.toItemFilters(): List<ItemFilter> =
    itemFilters.map { filter ->
        when (filter) {
            LibraryItemFilter.Played -> ItemFilter.IsPlayed
            LibraryItemFilter.Unplayed -> ItemFilter.IsUnplayed
            LibraryItemFilter.Resumable -> ItemFilter.IsResumable
            LibraryItemFilter.Favorite -> ItemFilter.IsFavorite
        }
    } +
        listOfNotNull(
            ItemFilter.HasSubtitles.takeIf { hasSubtitles },
            ItemFilter.HasTrailer.takeIf { hasTrailer },
            ItemFilter.HasSpecialFeature.takeIf { hasSpecialFeature },
        )

// Library/genre/studio browse lists titles, never individual episodes.
private val libraryBrowseItemTypes = listOf("Movie", "Series")

internal val mediaRepositoryItemsByIdTypes = listOf("Movie", "Series", "Episode")

internal val collectionsQuery =
    ItemsQuery(
        recursive = true,
        includeItemTypes = listOf("BoxSet"),
        sortBy = "SortName",
        sortOrder = "Ascending",
        limit = 500,
    )

internal val collectionItemsQuery =
    ItemsQuery(
        recursive = false,
        includeItemTypes = listOf("Movie", "Series", "Episode"),
        sortBy = "SortName",
        sortOrder = "Ascending",
        limit = DEFAULT_DISCOVERY_PAGE_SIZE,
    )

internal val discoveryItemsQuery =
    ItemsQuery(
        recursive = true,
        includeItemTypes = listOf("Movie", "Series", "Episode"),
        sortBy = "SortName",
        sortOrder = "Ascending",
        limit = DEFAULT_DISCOVERY_PAGE_SIZE,
    )

internal fun kidsCatalogueItemsQuery(
    parentId: String,
    startIndex: Int,
): ItemsQuery =
    ItemsQuery(
        recursive = true,
        includeItemTypes = kidsItemTypes,
        limit = DEFAULT_DISCOVERY_PAGE_SIZE,
        parentId = parentId,
        startIndex = startIndex.coerceAtLeast(0),
        sortBy = "SortName",
        sortOrder = "Ascending",
        fields = kidsCatalogueItemFields,
        enableImageTypes = listOf("Primary", "Backdrop"),
        enableTotalRecordCount = false,
        enableUserData = true,
    )

internal val kidsItemTypes = listOf("Movie", "Episode")

private val kidsCatalogueItemFields =
    listOf(
        "RunTimeTicks",
        "SeriesName",
        "SeriesId",
        "SeasonId",
        "IndexNumber",
        "ParentIndexNumber",
        "ImageTags",
        "BackdropImageTags",
        "UserData",
    )

internal val personFilmographyQuery =
    ItemsQuery(
        recursive = true,
        includeItemTypes = listOf("Movie", "Series"),
        sortBy = "PremiereDate",
        sortOrder = "Descending",
        limit = DEFAULT_DISCOVERY_PAGE_SIZE,
    )

internal fun relatedItemType(kind: MediaKind): String =
    when (kind) {
        MediaKind.Series -> "Series"
        MediaKind.Episode -> "Episode"
        MediaKind.Movie,
        MediaKind.Other,
        -> "Movie"
    }

// A genre/studio/director fallback shelf: shuffle so revisits feel fresh, and
// over-fetch a little because cross-shelf dedup trims the current item and any
// overlap with the "similar" row.
internal fun relatedQuery(
    includeItemTypes: List<String>,
    genres: List<String> = emptyList(),
    studioIds: List<String> = emptyList(),
    personIds: List<String> = emptyList(),
): ItemsQuery =
    ItemsQuery(
        recursive = true,
        includeItemTypes = includeItemTypes,
        sortBy = "Random",
        limit = RELATED_ITEM_LIMIT * 2,
        genres = genres,
        studioIds = studioIds,
        personIds = personIds,
    )

// One related shelf's source: its kind, dynamic label, and a suspend fetch.
internal class RelatedJob(
    val kind: RelatedGroupKind,
    val label: String?,
    val fetch: suspend () -> List<BaseItemDto>,
)

// Best-effort per-shelf fetch: a single failing endpoint must not empty the whole
// related section (runCatchingCancellable preserves coroutine cancellation).
internal suspend fun relatedFetch(block: suspend () -> List<BaseItemDto>): List<MediaItem> =
    runCatchingCancellable { block() }
        .onFailure { throwable -> logRelatedGroupsFailure(throwable) }
        .getOrDefault(emptyList())
        .mapNotNull { dto -> dto.toDomainMediaItem() }

internal fun logRelatedGroupsFailure(throwable: Throwable) {
    if (throwable is kotlin.coroutines.cancellation.CancellationException) return
    MEDIA_LOGGER.w {
        formatPlaybackDiagnostic(
            PlaybackDiagnostic(
                stage = PlaybackDiagnosticStage.Repository,
                event = PlaybackDiagnosticEvent.Failed,
                platform = PlaybackDiagnosticPlatform.Shared,
                exceptionType = throwable.playbackExceptionType(),
                operation = RepositoryOperation.GetRelatedGroups,
            ),
        )
    }
}

internal const val RELATED_ITEM_LIMIT = 12

// The first two cast members each get their own leading "More with <actor>" shelf.
internal const val RELATED_CAST_COUNT = 2

// Cast shelves mix films and shows (a person's filmography spans both).
internal val RELATED_CAST_ITEM_TYPES = listOf("Movie", "Series")

internal const val SPECIALS_SEASON_INDEX = 0

internal const val PERSON_SUGGESTION_LIMIT = 8

internal const val SUGGESTIONS_LIMIT = 20

internal const val PERSON_FILMOGRAPHY_LIMIT = 500

internal val MEDIA_LOGGER = diagnosticLogger(DiagnosticTag.MediaRepository)

internal fun BaseItemDto.shouldShowInSeasonEpisodeList(
    seasonId: String,
    seasonIndex: Int?,
): Boolean =
    seasonIndex == SPECIALS_SEASON_INDEX ||
        !isConfirmedSpecialEpisode() ||
        (seasonIndex == null && this.seasonId == seasonId)

private fun BaseItemDto.isConfirmedSpecialEpisode(): Boolean = parentIndexNumber == SPECIALS_SEASON_INDEX || isSpecial == true
