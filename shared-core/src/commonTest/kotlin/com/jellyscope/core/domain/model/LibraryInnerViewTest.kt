// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryInnerViewTest {
    @Test
    fun movieAndShowLibrariesExposeRecommendedAndLibraryOnly() {
        val expected = listOf(LibraryInnerView.Recommended, LibraryInnerView.Library)
        listOf(LibraryCollectionType.Movies, LibraryCollectionType.TvShows).forEach { collectionType ->
            assertEquals(expected, collectionType.availableInnerViews())
        }
    }

    @Test
    fun retiredViewsAreNoLongerOfferedByAnyCollectionType() {
        // The enum entries survive so a persisted preference still deserializes;
        // they must simply never be offered as a tab again.
        LibraryCollectionType.entries.forEach { collectionType ->
            assertEquals(
                emptyList(),
                collectionType.availableInnerViews().filter { view ->
                    view == LibraryInnerView.Genres || view == LibraryInnerView.Collections
                },
            )
        }
    }

    @Test
    fun unsupportedLibrariesRemainLibraryOnly() {
        assertEquals(listOf(LibraryInnerView.Library), LibraryCollectionType.Music.availableInnerViews())
        assertEquals(listOf(LibraryInnerView.Library), LibraryCollectionType.Other.availableInnerViews())
    }
}
