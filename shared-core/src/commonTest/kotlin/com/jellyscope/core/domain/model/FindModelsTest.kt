// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class FindModelsTest {
    @Test
    fun parsesFirstFourDigitYearInSupportedRange() {
        assertEquals(1999, parseFindYear("movies like 1999 thrillers"))
        assertEquals(2100, parseFindYear("future 2100"))
        assertEquals(1900, parseFindYear("classic 1900"))
        assertNull(parseFindYear("1899 period drama"))
        assertNull(parseFindYear("2101 future"))
        assertNull(parseFindYear("abc1999def"))
    }

    @Test
    fun projectionAppliesRuntimeBucketsAndPreservesServerOrderByGroup() {
        val items =
            listOf(
                mediaItem("episode-45", MediaKind.Episode, 45),
                mediaItem("movie-30", MediaKind.Movie, 30),
                mediaItem("show-20", MediaKind.Series, 20),
                mediaItem("movie-60", MediaKind.Movie, 60),
                mediaItem("episode-120", MediaKind.Episode, 120),
            )

        val under60 = FindProjection.project(items, RuntimeBucket.Under60)

        assertEquals(listOf("movie-30", "movie-60"), under60.movies.map { it.id })
        assertEquals(listOf("show-20"), under60.shows.map { it.id })
        assertEquals(listOf("episode-45"), under60.episodes.map { it.id })
    }

    @Test
    fun projectionHandlesBucketBoundariesAndEmptyResults() {
        val items =
            listOf(
                mediaItem("under-30", MediaKind.Movie, 30),
                mediaItem("under-60", MediaKind.Movie, 60),
                mediaItem("under-120", MediaKind.Movie, 120),
                mediaItem("long", MediaKind.Movie, 121),
                mediaItem("missing", MediaKind.Movie, null),
            )

        assertEquals(listOf("under-30"), FindProjection.project(items, RuntimeBucket.Under30).movies.map { it.id })
        assertEquals(listOf("under-30", "under-60"), FindProjection.project(items, RuntimeBucket.Under60).movies.map { it.id })
        assertEquals(
            listOf("under-30", "under-60", "under-120"),
            FindProjection.project(items, RuntimeBucket.Under120).movies.map { it.id },
        )
        assertEquals(true, FindProjection.project(emptyList(), RuntimeBucket.Any).isEmpty)
    }

    @Test
    fun projectionDeduplicatesRepeatedServerItemsKeepingFirstOccurrence() {
        // Jellyfin returns the same item multiple times when it is reachable
        // through several library folders; duplicate ids crash lazy layouts
        // that key results by item id.
        val items =
            listOf(
                mediaItem("movie-1", MediaKind.Movie, 90),
                mediaItem("show-1", MediaKind.Series, null),
                mediaItem("movie-1", MediaKind.Movie, 90),
                mediaItem("episode-1", MediaKind.Episode, 45),
                mediaItem("episode-1", MediaKind.Episode, 45),
            )

        val results = FindProjection.project(items, RuntimeBucket.Any)

        assertEquals(listOf("movie-1"), results.movies.map { it.id })
        assertEquals(listOf("show-1"), results.shows.map { it.id })
        assertEquals(listOf("episode-1"), results.episodes.map { it.id })
    }
}

private fun mediaItem(
    id: String,
    kind: MediaKind,
    runtimeMinutes: Int?,
) = MediaItem(
    id = id,
    name = id,
    kind = kind,
    runtime = runtimeMinutes?.minutes,
)
