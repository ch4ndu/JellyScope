// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tvos.presenter

import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFacet
import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemFilter
import com.jellyscope.core.domain.model.LibrarySeriesStatus

enum class TvLibraryFilterGroupKind {
    Watched,
    Genres,
    Years,
    Ratings,
    Studios,
    Tags,
    SeriesStatus,
    Features,
}

enum class TvLibraryFilterOptionKind {
    Dynamic,
    Played,
    Unplayed,
    Resumable,
    Favorite,
    Continuing,
    Ended,
    Unreleased,
    HasSubtitles,
    HasTrailer,
    HasSpecialFeature,
}

data class TvLibraryFilterOption(
    val key: String,
    val kind: TvLibraryFilterOptionKind,
    val label: String? = null,
    val isSelected: Boolean = false,
)

data class TvLibraryFilterGroup(
    val kind: TvLibraryFilterGroupKind,
    val options: List<TvLibraryFilterOption>,
)

internal data class TvLibraryFilterProjection(
    val definitions: List<TvLibraryFilterDefinition>,
    val groups: List<TvLibraryFilterGroup>,
)

internal data class TvLibraryFilterDefinition(
    val group: TvLibraryFilterGroupKind,
    val key: String,
    val kind: TvLibraryFilterOptionKind,
    val label: String? = null,
    val value: TvLibraryFilterValue,
)

internal sealed interface TvLibraryFilterValue {
    data class GenreId(
        val value: String,
    ) : TvLibraryFilterValue

    data class GenreName(
        val value: String,
    ) : TvLibraryFilterValue

    data class Year(
        val value: Int,
    ) : TvLibraryFilterValue

    data class Rating(
        val value: String,
    ) : TvLibraryFilterValue

    data class StudioId(
        val value: String,
    ) : TvLibraryFilterValue

    data class Tag(
        val value: String,
    ) : TvLibraryFilterValue

    data class Item(
        val value: LibraryItemFilter,
    ) : TvLibraryFilterValue

    data class SeriesStatus(
        val value: LibrarySeriesStatus,
    ) : TvLibraryFilterValue

    data object HasSubtitles : TvLibraryFilterValue

    data object HasTrailer : TvLibraryFilterValue

    data object HasSpecialFeature : TvLibraryFilterValue
}

internal fun LibraryFacets.toTvFilterProjection(
    collectionType: LibraryCollectionType,
    selection: LibraryFilterSelection,
): TvLibraryFilterProjection {
    val definitions =
        buildList {
            addAll(staticFilterDefinitions(collectionType))
            addAll(genres.map { facet -> facet.genreDefinition() })
            addAll(
                years.distinct().sortedDescending().map { year ->
                    TvLibraryFilterDefinition(
                        group = TvLibraryFilterGroupKind.Years,
                        key = "year-$year",
                        kind = TvLibraryFilterOptionKind.Dynamic,
                        label = year.toString(),
                        value = TvLibraryFilterValue.Year(year),
                    )
                },
            )
            addAll(
                officialRatings.distinct().map { rating ->
                    TvLibraryFilterDefinition(
                        group = TvLibraryFilterGroupKind.Ratings,
                        key = "rating-$rating",
                        kind = TvLibraryFilterOptionKind.Dynamic,
                        label = rating,
                        value = TvLibraryFilterValue.Rating(rating),
                    )
                },
            )
            addAll(
                studios.mapNotNull { studio ->
                    studio.id?.takeIf { it.isNotBlank() }?.let { id ->
                        TvLibraryFilterDefinition(
                            group = TvLibraryFilterGroupKind.Studios,
                            key = "studio-$id",
                            kind = TvLibraryFilterOptionKind.Dynamic,
                            label = studio.name,
                            value = TvLibraryFilterValue.StudioId(id),
                        )
                    }
                },
            )
            addAll(
                tags.distinct().map { tag ->
                    TvLibraryFilterDefinition(
                        group = TvLibraryFilterGroupKind.Tags,
                        key = "tag-$tag",
                        kind = TvLibraryFilterOptionKind.Dynamic,
                        label = tag,
                        value = TvLibraryFilterValue.Tag(tag),
                    )
                },
            )
        }
    return TvLibraryFilterProjection(
        definitions = definitions,
        groups = definitions.toTvFilterGroups(selection),
    )
}

internal fun List<TvLibraryFilterDefinition>.toTvFilterGroups(selection: LibraryFilterSelection): List<TvLibraryFilterGroup> =
    TvLibraryFilterGroupKind.entries.mapNotNull { group ->
        val options =
            filter { definition -> definition.group == group }.map { definition ->
                TvLibraryFilterOption(
                    key = definition.key,
                    kind = definition.kind,
                    label = definition.label,
                    isSelected = selection.contains(definition.value),
                )
            }
        options.takeIf { it.isNotEmpty() }?.let { TvLibraryFilterGroup(group, it) }
    }

internal fun LibraryFilterSelection.toggled(value: TvLibraryFilterValue): LibraryFilterSelection =
    when (value) {
        is TvLibraryFilterValue.GenreId -> copy(genreIds = genreIds.toggle(value.value))
        is TvLibraryFilterValue.GenreName -> copy(genres = genres.toggle(value.value))
        is TvLibraryFilterValue.Year -> copy(years = years.toggle(value.value))
        is TvLibraryFilterValue.Rating -> copy(officialRatings = officialRatings.toggle(value.value))
        is TvLibraryFilterValue.StudioId -> copy(studioIds = studioIds.toggle(value.value))
        is TvLibraryFilterValue.Tag -> copy(tags = tags.toggle(value.value))
        is TvLibraryFilterValue.Item -> toggleItemFilter(value.value)
        is TvLibraryFilterValue.SeriesStatus -> copy(seriesStatus = seriesStatus.toggle(value.value))
        TvLibraryFilterValue.HasSubtitles -> copy(hasSubtitles = !hasSubtitles)
        TvLibraryFilterValue.HasTrailer -> copy(hasTrailer = !hasTrailer)
        TvLibraryFilterValue.HasSpecialFeature -> copy(hasSpecialFeature = !hasSpecialFeature)
    }

private fun LibraryFacet.genreDefinition(): TvLibraryFilterDefinition {
    val id = id?.takeIf { it.isNotBlank() }
    return TvLibraryFilterDefinition(
        group = TvLibraryFilterGroupKind.Genres,
        key = if (id == null) "genre-name-$name" else "genre-id-$id",
        kind = TvLibraryFilterOptionKind.Dynamic,
        label = name,
        value =
            if (id == null) {
                TvLibraryFilterValue.GenreName(name)
            } else {
                TvLibraryFilterValue.GenreId(id)
            },
    )
}

private fun staticFilterDefinitions(collectionType: LibraryCollectionType): List<TvLibraryFilterDefinition> =
    buildList {
        addItemFilter("played", TvLibraryFilterOptionKind.Played, LibraryItemFilter.Played)
        addItemFilter("unplayed", TvLibraryFilterOptionKind.Unplayed, LibraryItemFilter.Unplayed)
        addItemFilter("resumable", TvLibraryFilterOptionKind.Resumable, LibraryItemFilter.Resumable)
        addItemFilter("favorite", TvLibraryFilterOptionKind.Favorite, LibraryItemFilter.Favorite)
        if (collectionType == LibraryCollectionType.TvShows) {
            addSeriesStatus("continuing", TvLibraryFilterOptionKind.Continuing, LibrarySeriesStatus.Continuing)
            addSeriesStatus("ended", TvLibraryFilterOptionKind.Ended, LibrarySeriesStatus.Ended)
            addSeriesStatus("unreleased", TvLibraryFilterOptionKind.Unreleased, LibrarySeriesStatus.Unreleased)
        }
        if (collectionType == LibraryCollectionType.Movies || collectionType == LibraryCollectionType.TvShows) {
            add(
                TvLibraryFilterDefinition(
                    group = TvLibraryFilterGroupKind.Features,
                    key = "feature-subtitles",
                    kind = TvLibraryFilterOptionKind.HasSubtitles,
                    value = TvLibraryFilterValue.HasSubtitles,
                ),
            )
            add(
                TvLibraryFilterDefinition(
                    group = TvLibraryFilterGroupKind.Features,
                    key = "feature-trailer",
                    kind = TvLibraryFilterOptionKind.HasTrailer,
                    value = TvLibraryFilterValue.HasTrailer,
                ),
            )
            add(
                TvLibraryFilterDefinition(
                    group = TvLibraryFilterGroupKind.Features,
                    key = "feature-special",
                    kind = TvLibraryFilterOptionKind.HasSpecialFeature,
                    value = TvLibraryFilterValue.HasSpecialFeature,
                ),
            )
        }
    }

private fun MutableList<TvLibraryFilterDefinition>.addItemFilter(
    key: String,
    kind: TvLibraryFilterOptionKind,
    value: LibraryItemFilter,
) {
    add(
        TvLibraryFilterDefinition(
            group = TvLibraryFilterGroupKind.Watched,
            key = "watched-$key",
            kind = kind,
            value = TvLibraryFilterValue.Item(value),
        ),
    )
}

private fun MutableList<TvLibraryFilterDefinition>.addSeriesStatus(
    key: String,
    kind: TvLibraryFilterOptionKind,
    value: LibrarySeriesStatus,
) {
    add(
        TvLibraryFilterDefinition(
            group = TvLibraryFilterGroupKind.SeriesStatus,
            key = "status-$key",
            kind = kind,
            value = TvLibraryFilterValue.SeriesStatus(value),
        ),
    )
}

private fun LibraryFilterSelection.contains(value: TvLibraryFilterValue): Boolean =
    when (value) {
        is TvLibraryFilterValue.GenreId -> value.value in genreIds
        is TvLibraryFilterValue.GenreName -> value.value in genres
        is TvLibraryFilterValue.Year -> value.value in years
        is TvLibraryFilterValue.Rating -> value.value in officialRatings
        is TvLibraryFilterValue.StudioId -> value.value in studioIds
        is TvLibraryFilterValue.Tag -> value.value in tags
        is TvLibraryFilterValue.Item -> value.value in itemFilters
        is TvLibraryFilterValue.SeriesStatus -> value.value in seriesStatus
        TvLibraryFilterValue.HasSubtitles -> hasSubtitles
        TvLibraryFilterValue.HasTrailer -> hasTrailer
        TvLibraryFilterValue.HasSpecialFeature -> hasSpecialFeature
    }

private fun LibraryFilterSelection.toggleItemFilter(filter: LibraryItemFilter): LibraryFilterSelection {
    if (filter == LibraryItemFilter.Favorite) {
        return copy(itemFilters = itemFilters.toggle(filter))
    }
    val withoutExclusive =
        itemFilters - LibraryItemFilter.Played - LibraryItemFilter.Unplayed - LibraryItemFilter.Resumable
    return copy(
        itemFilters =
            if (filter in itemFilters) {
                withoutExclusive
            } else {
                withoutExclusive + filter
            },
    )
}

private fun <T> List<T>.toggle(value: T): List<T> = if (value in this) this - value else this + value
