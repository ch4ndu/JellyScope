// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.data.local

import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedLibrarySortCodecTest {
    @Test
    fun everyLibrarySortPairRoundTripsWithThePersistedShape() {
        LibrarySortBy.entries.forEach { sortBy ->
            LibrarySortOrder.entries.forEach { sortOrder ->
                val saved = SavedLibrarySort(sortBy = sortBy, sortOrder = sortOrder)

                assertEquals("${sortBy.name}:${sortOrder.name}", saved.toStoredValue())
                assertEquals(saved, saved.toStoredValue().toSavedLibrarySortOrNull())
            }
        }
    }

    @Test
    fun malformedPersistedValuesDecodeToNull() {
        listOf(
            "",
            "Name",
            "A:B:C",
            "Unknown:Ascending",
            "Name:Unknown",
            "Ascending:Name",
            ":Ascending",
            "Name:",
            ":",
        ).forEach { encoded ->
            assertNull(encoded.toSavedLibrarySortOrNull(), encoded)
        }
    }
}
