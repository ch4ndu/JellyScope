// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.core.domain.model

data class PersonHeader(
    val id: String,
    val name: String,
    val overview: String? = null,
    val imageRefs: ImageRefs = ImageRefs(),
)

data class PersonFilmography(
    val movies: List<MediaItem> = emptyList(),
    val series: List<MediaItem> = emptyList(),
    val totalCount: Int = movies.size + series.size,
    val startIndex: Int = 0,
)

data class MediaSuggestions(
    val seedItem: MediaItem?,
    val items: List<MediaItem> = emptyList(),
)

const val DEFAULT_DISCOVERY_PAGE_SIZE = 60
