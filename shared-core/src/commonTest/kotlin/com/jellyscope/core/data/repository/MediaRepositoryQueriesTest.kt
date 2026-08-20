// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.repository

import com.jellyscope.core.data.remote.ItemFilter
import com.jellyscope.core.domain.model.FindMediaKind
import com.jellyscope.core.domain.model.FindQuery
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemFilter
import com.jellyscope.core.domain.model.LibraryItemsRequest
import com.jellyscope.core.domain.model.LibrarySeriesStatus
import com.jellyscope.core.domain.model.LibraryShuffleRequest
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.WatchedFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaRepositoryQueriesTest {
    @Test
    fun mapsMediaSpecificSortNamesExactly() {
        assertEquals(
            "VideoBitRate",
            LibraryItemsRequest(sortBy = LibrarySortBy.VideoBitRate).toItemsQuery().sortBy,
        )
        assertEquals(
            "DateLastContentAdded",
            LibraryItemsRequest(sortBy = LibrarySortBy.DateLastContentAdded).toItemsQuery().sortBy,
        )
    }

    @Test
    fun mapsFilteredMovieShuffleAsUnboundedIdOnlyRequest() {
        val query =
            LibraryShuffleRequest(
                parentId = "movies",
                filters =
                    LibraryFilterSelection(
                        genreIds = listOf("genre-1"),
                        years = listOf(2024),
                        itemFilters = listOf(LibraryItemFilter.Unplayed),
                    ),
            ).toItemsQuery()

        assertEquals(listOf("Movie"), query.includeItemTypes)
        assertNull(query.limit)
        assertEquals("movies", query.parentId)
        assertEquals("Random", query.sortBy)
        assertEquals(emptyList(), query.fields)
        assertEquals(emptyList(), query.enableImageTypes)
        assertEquals(0, query.imageTypeLimit)
        assertEquals(false, query.enableTotalRecordCount)
        assertEquals(false, query.enableUserData)
        assertEquals(listOf("genre-1"), query.genreIds)
        assertEquals(listOf(2024), query.years)
        assertEquals(listOf(ItemFilter.IsUnplayed), query.filters)
    }

    @Test
    fun mapsFindRequestAtTheDataBoundary() {
        val query =
            FindQuery(
                text = "  matrix 1999 ",
                year = 1999,
                genreNames = listOf("Action", "Drama"),
                personId = "person-1",
                watchedFilter = WatchedFilter.InProgress,
                mediaKinds = listOf(FindMediaKind.Movies, FindMediaKind.Episodes),
            ).toItemsQuery()

        assertEquals(listOf("Movie", "Episode"), query.includeItemTypes)
        assertEquals("matrix 1999", query.searchTerm)
        assertEquals(listOf("person-1"), query.personIds)
        assertEquals(listOf("Action", "Drama"), query.genres)
        assertEquals(listOf(1999), query.years)
        assertEquals(listOf(ItemFilter.IsResumable), query.filters)
    }

    @Test
    fun mapsLibraryRequestAtTheDataBoundary() {
        val query =
            LibraryItemsRequest(
                parentId = "library-1",
                sortBy = LibrarySortBy.DateCreated,
                sortOrder = LibrarySortOrder.Descending,
                startIndex = -8,
                limit = 30,
                filters =
                    LibraryFilterSelection(
                        genres = listOf("Drama", ""),
                        genreIds = listOf("genre-1", " "),
                        years = listOf(2024),
                        officialRatings = listOf("PG", ""),
                        studioIds = listOf("studio-1", ""),
                        tags = listOf("tag-1", " "),
                        itemFilters = listOf(LibraryItemFilter.Played, LibraryItemFilter.Favorite),
                        seriesStatus = listOf(LibrarySeriesStatus.Continuing),
                        hasSubtitles = true,
                        hasTrailer = true,
                        hasSpecialFeature = true,
                    ),
            ).toItemsQuery()

        assertEquals(false, query.enableTotalRecordCount)
        assertEquals(listOf("Movie", "Series"), query.includeItemTypes)
        assertEquals(30, query.limit)
        assertEquals("library-1", query.parentId)
        assertEquals(0, query.startIndex)
        assertEquals("DateCreated", query.sortBy)
        assertEquals("Descending", query.sortOrder)
        assertEquals(listOf("Drama"), query.genres)
        assertEquals(listOf("genre-1"), query.genreIds)
        assertEquals(listOf(2024), query.years)
        assertEquals(listOf("PG"), query.officialRatings)
        assertEquals(listOf("studio-1"), query.studioIds)
        assertEquals(listOf("tag-1"), query.tags)
        assertEquals(listOf("Continuing"), query.seriesStatus)
        assertEquals(
            listOf(
                ItemFilter.IsPlayed,
                ItemFilter.IsFavorite,
                ItemFilter.HasSubtitles,
                ItemFilter.HasTrailer,
                ItemFilter.HasSpecialFeature,
            ),
            query.filters,
        )
    }

    @Test
    fun dropsBlankOptionalQueryValues() {
        val findQuery =
            FindQuery(
                text = " ",
                genreNames = listOf("Comedy", ""),
                personId = "",
                watchedFilter = WatchedFilter.Unwatched,
            ).toItemsQuery()
        val libraryQuery =
            LibraryItemsRequest(
                parentId = "",
                filters =
                    LibraryFilterSelection(
                        genres = listOf("", " "),
                        itemFilters = listOf(LibraryItemFilter.Unplayed, LibraryItemFilter.Resumable),
                    ),
            ).toItemsQuery()

        assertNull(findQuery.searchTerm)
        assertEquals(emptyList(), findQuery.personIds)
        assertEquals(listOf("Comedy"), findQuery.genres)
        assertEquals(listOf(ItemFilter.IsUnplayed), findQuery.filters)
        assertNull(libraryQuery.parentId)
        assertEquals(emptyList(), libraryQuery.genres)
        assertEquals(listOf(ItemFilter.IsUnplayed, ItemFilter.IsResumable), libraryQuery.filters)
    }
}
