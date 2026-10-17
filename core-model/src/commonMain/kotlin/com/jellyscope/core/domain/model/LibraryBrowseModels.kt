// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

data class PagedItems(
    val items: List<MediaItem>,
    val totalCount: Int,
    val startIndex: Int,
)

data class LibraryItemsRequest(
    val parentId: String? = null,
    val sortBy: LibrarySortBy = LibrarySortBy.Name,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.Ascending,
    val filters: LibraryFilterSelection = LibraryFilterSelection(),
    val startIndex: Int = 0,
    val limit: Int = DEFAULT_LIBRARY_PAGE_SIZE,
)

data class LibraryShuffleRequest(
    val parentId: String? = null,
    val filters: LibraryFilterSelection = LibraryFilterSelection(),
)

enum class LibraryInnerView {
    Recommended,
    Library,

    // No longer offered as library tabs (Discover covers both, and their results
    // spanned every library rather than the one being browsed). The entries are
    // retained so a persisted preference still deserializes — callers filter on
    // availableInnerViews(), so a stored Genres/Collections degrades to the
    // default view instead of failing.
    Genres,
    Collections,
}

fun LibraryCollectionType.availableInnerViews(): List<LibraryInnerView> =
    when (this) {
        LibraryCollectionType.Movies,
        LibraryCollectionType.TvShows,
        ->
            listOf(
                LibraryInnerView.Recommended,
                LibraryInnerView.Library,
            )
        else -> listOf(LibraryInnerView.Library)
    }

enum class LibraryRecommendationSection {
    ContinueWatching,
    RecentlyAdded,
    NextUp,
    MovieRecommendations,
}

enum class LibraryRecommendationReason {
    SimilarToRecentlyPlayed,
    SimilarToLikedItem,
    HasDirector,
    HasActor,
    Other,
}

data class LibraryRecommendationRow(
    val key: String,
    val section: LibraryRecommendationSection,
    val reason: LibraryRecommendationReason? = null,
    val baselineItemName: String? = null,
    val items: List<MediaItem>,
)

data class LibraryRecommendationRequest(
    val parentId: String,
    val collectionType: LibraryCollectionType,
    val section: LibraryRecommendationSection,
    val limit: Int = 20,
)

data class LibraryFilterSelection(
    val genres: List<String> = emptyList(),
    // Genre IDs are the fast filter path: filtering by genre NAME (`Genres=`)
    // is pathologically slow on Jellyfin (can exceed the request timeout),
    // while `GenreIds=` uses the indexed relation. Prefer genreIds.
    val genreIds: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val officialRatings: List<String> = emptyList(),
    val studioIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val itemFilters: List<LibraryItemFilter> = emptyList(),
    val seriesStatus: List<LibrarySeriesStatus> = emptyList(),
    val hasSubtitles: Boolean = false,
    val hasTrailer: Boolean = false,
    val hasSpecialFeature: Boolean = false,
)

val LibraryFilterSelection.activeCount: Int
    get() =
        genres.size +
            genreIds.size +
            years.size +
            officialRatings.size +
            studioIds.size +
            tags.size +
            itemFilters.size +
            seriesStatus.size +
            listOf(hasSubtitles, hasTrailer, hasSpecialFeature).count { enabled -> enabled }

enum class LibrarySortBy {
    Name,
    Random,
    CommunityRating,
    CriticRating,
    DateCreated,
    DatePlayed,
    PremiereDate,
    Runtime,
    OfficialRating,
    PlayCount,
    VideoBitRate,
    DateLastContentAdded,
}

fun LibrarySortBy.isAvailableFor(collectionType: LibraryCollectionType): Boolean =
    when (this) {
        LibrarySortBy.VideoBitRate -> collectionType == LibraryCollectionType.Movies
        LibrarySortBy.DateLastContentAdded -> collectionType == LibraryCollectionType.TvShows
        else -> true
    }

enum class LibrarySortOrder {
    Ascending,
    Descending,
}

enum class LibraryItemFilter {
    Played,
    Unplayed,
    Resumable,
    Favorite,
}

enum class LibrarySeriesStatus {
    Continuing,
    Ended,
    Unreleased,
}

data class LibraryFacet(
    val id: String?,
    val name: String,
    val imageRefs: ImageRefs = ImageRefs(),
)

data class LibraryFacets(
    val genres: List<LibraryFacet> = emptyList(),
    val studios: List<LibraryFacet> = emptyList(),
    val officialRatings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
)

const val DEFAULT_LIBRARY_PAGE_SIZE = 60
