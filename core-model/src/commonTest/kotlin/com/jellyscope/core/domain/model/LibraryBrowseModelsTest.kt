// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryBrowseModelsTest {
    @Test
    fun activeCountIncludesEverySelectedFilter() {
        val filters =
            LibraryFilterSelection(
                genres = listOf("Drama"),
                genreIds = listOf("genre-1"),
                years = listOf(2024),
                officialRatings = listOf("PG"),
                studioIds = listOf("studio-1"),
                tags = listOf("tag-1"),
                itemFilters = listOf(LibraryItemFilter.Played),
                seriesStatus = listOf(LibrarySeriesStatus.Continuing),
                hasSubtitles = true,
                hasTrailer = true,
                hasSpecialFeature = true,
            )

        assertEquals(11, filters.activeCount)
    }
}
