// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

import kotlin.time.Duration.Companion.minutes

data class FindQuery(
    val text: String = "",
    val year: Int? = null,
    val genreNames: List<String> = emptyList(),
    val personId: String? = null,
    val watchedFilter: WatchedFilter = WatchedFilter.Any,
    val runtimeBucket: RuntimeBucket = RuntimeBucket.Any,
    val mediaKinds: List<FindMediaKind> = FindMediaKind.entries,
)

enum class WatchedFilter {
    Any,
    Unwatched,
    InProgress,
}

enum class RuntimeBucket {
    Any,
    Under30,
    Under60,
    Under120,
}

enum class FindMediaKind {
    Movies,
    Shows,
    Episodes,
}

data class FindResults(
    val movies: List<MediaItem> = emptyList(),
    val shows: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = movies.isEmpty() && shows.isEmpty() && episodes.isEmpty()
}

object FindProjection {
    fun project(
        items: List<MediaItem>,
        runtimeBucket: RuntimeBucket,
    ): FindResults {
        // Jellyfin can return the same item more than once in a single search
        // response (e.g. reachable through multiple library folders). Result
        // identity must be unique per group: UIs key lazy rows/grids by item
        // id, and a duplicate key crashes Compose lazy layouts.
        val filtered =
            items
                .distinctBy { item -> item.id }
                .filter { item -> runtimeBucket.matches(item) }

        return FindResults(
            movies = filtered.filter { item -> item.kind == MediaKind.Movie },
            shows = filtered.filter { item -> item.kind == MediaKind.Series },
            episodes = filtered.filter { item -> item.kind == MediaKind.Episode },
        )
    }

    private fun RuntimeBucket.matches(item: MediaItem): Boolean {
        val runtime = item.runtime
        return when (this) {
            RuntimeBucket.Any -> true
            RuntimeBucket.Under30 -> runtime?.let { it <= 30.minutes } == true
            RuntimeBucket.Under60 -> runtime?.let { it <= 60.minutes } == true
            RuntimeBucket.Under120 -> runtime?.let { it <= 120.minutes } == true
        }
    }
}

fun parseFindYear(text: String): Int? =
    Regex("""\b\d{4}\b""")
        .findAll(text)
        .mapNotNull { match -> match.value.toIntOrNull() }
        .firstOrNull { year -> year in 1900..2100 }
