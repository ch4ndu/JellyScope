// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.JellyfinImageType
import com.jellyscope.core.domain.model.JellyfinImageUrlBuilder
import com.jellyscope.core.domain.model.Person
import com.jellyscope.core.domain.model.Session

enum class TvSearchGenre(
    val serverName: String,
) {
    Comedy("Comedy"),
    Drama("Drama"),
    Action("Action"),
    Thriller("Thriller"),
    Family("Family"),
}

enum class TvSearchResultCategory {
    All,
    Movies,
    Shows,
    Episodes,
}

data class TvSearchPerson(
    val id: String,
    val name: String,
    val imageUrl: String?,
)

data class TvSearchResultSection(
    val category: TvSearchResultCategory,
    val cards: List<TvMediaCard>,
)

internal fun Person.toTvSearchPerson(
    session: Session,
    imageUrlBuilder: JellyfinImageUrlBuilder,
): TvSearchPerson =
    TvSearchPerson(
        id = id,
        name = name,
        imageUrl =
            imageRefs.primaryTag?.let { tag ->
                imageUrlBuilder.build(
                    serverUrl = session.serverUrl,
                    itemId = id,
                    type = JellyfinImageType.Primary,
                    tag = tag,
                    maxWidth = SEARCH_PERSON_IMAGE_MAX_WIDTH,
                )
            },
    )

internal fun resultSections(
    selected: TvSearchResultCategory,
    movies: List<TvMediaCard>,
    shows: List<TvMediaCard>,
    episodes: List<TvMediaCard>,
): List<TvSearchResultSection> =
    listOfNotNull(
        TvSearchResultSection(TvSearchResultCategory.Movies, movies)
            .takeIf {
                selected in setOf(TvSearchResultCategory.All, TvSearchResultCategory.Movies) && movies.isNotEmpty()
            },
        TvSearchResultSection(TvSearchResultCategory.Shows, shows)
            .takeIf {
                selected in setOf(TvSearchResultCategory.All, TvSearchResultCategory.Shows) && shows.isNotEmpty()
            },
        TvSearchResultSection(TvSearchResultCategory.Episodes, episodes)
            .takeIf {
                selected in setOf(TvSearchResultCategory.All, TvSearchResultCategory.Episodes) && episodes.isNotEmpty()
            },
    )

private const val SEARCH_PERSON_IMAGE_MAX_WIDTH = 240
