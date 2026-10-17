// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemFilter
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.activeCount
import com.jellyscope.core.domain.model.isAvailableFor
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.TooltipIconButton
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.library_filter_chip_cd
import com.jellyscope.ui.generated.resources.library_filters
import com.jellyscope.ui.generated.resources.library_filters_apply
import com.jellyscope.ui.generated.resources.library_filters_cancel
import com.jellyscope.ui.generated.resources.library_filters_open_active_cd
import com.jellyscope.ui.generated.resources.library_filters_open_cd
import com.jellyscope.ui.generated.resources.library_genres
import com.jellyscope.ui.generated.resources.library_grid
import com.jellyscope.ui.generated.resources.library_list
import com.jellyscope.ui.generated.resources.library_ratings
import com.jellyscope.ui.generated.resources.library_shuffle_all
import com.jellyscope.ui.generated.resources.library_shuffle_all_cd
import com.jellyscope.ui.generated.resources.library_shuffle_preparing
import com.jellyscope.ui.generated.resources.library_sort_bitrate
import com.jellyscope.ui.generated.resources.library_sort_button
import com.jellyscope.ui.generated.resources.library_sort_community_rating
import com.jellyscope.ui.generated.resources.library_sort_date_added
import com.jellyscope.ui.generated.resources.library_sort_last_episode_added
import com.jellyscope.ui.generated.resources.library_sort_name
import com.jellyscope.ui.generated.resources.library_sort_order_ascending
import com.jellyscope.ui.generated.resources.library_sort_order_ascending_short
import com.jellyscope.ui.generated.resources.library_sort_order_descending
import com.jellyscope.ui.generated.resources.library_sort_order_descending_short
import com.jellyscope.ui.generated.resources.library_sort_premiere_date
import com.jellyscope.ui.generated.resources.library_sort_runtime
import com.jellyscope.ui.generated.resources.library_studios
import com.jellyscope.ui.generated.resources.library_watched_favorite
import com.jellyscope.ui.generated.resources.library_watched_played
import com.jellyscope.ui.generated.resources.library_watched_resumable
import com.jellyscope.ui.generated.resources.library_watched_unplayed
import com.jellyscope.ui.generated.resources.library_years
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.lazy.items as lazyItems

@Composable
internal fun LibrarySortMenu(
    collectionType: LibraryCollectionType,
    sortBy: LibrarySortBy,
    sortOrder: LibrarySortOrder,
    onSetSort: (LibrarySortBy, LibrarySortOrder) -> Unit,
) {
    val sortChoices = remember(collectionType) { librarySortChoices(collectionType) }
    val activeChoice = sortChoices.firstOrNull { choice -> choice.sortBy == sortBy }
    val activeSortLabel = stringResource(activeChoice?.label ?: Res.string.library_sort_name)
    val activeOrderLabel =
        if (sortOrder == LibrarySortOrder.Ascending) {
            stringResource(Res.string.library_sort_order_ascending_short)
        } else {
            stringResource(Res.string.library_sort_order_descending_short)
        }
    var expanded by remember { mutableStateOf(false) }
    val contentDescription = stringResource(Res.string.library_sort_button, activeSortLabel, activeOrderLabel)

    Box {
        TooltipIconButton(
            label = contentDescription,
            onClick = { expanded = true },
            modifier = Modifier.size(Dimensions.minTouchTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.controlButtonIconSize),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            sortChoices.forEach { choice ->
                val selected = sortBy == choice.sortBy
                DropdownMenuItem(
                    text = { Text(stringResource(choice.label)) },
                    onClick = {
                        expanded = false
                        onSetSort(
                            choice.sortBy,
                            if (
                                choice.sortBy == LibrarySortBy.VideoBitRate ||
                                choice.sortBy == LibrarySortBy.DateLastContentAdded
                            ) {
                                LibrarySortOrder.Descending
                            } else {
                                sortOrder
                            },
                        )
                    },
                    trailingIcon =
                        if (selected) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            null
                        },
                )
            }
            HorizontalDivider()
            LibrarySortOrderMenuItem(
                label = stringResource(Res.string.library_sort_order_ascending),
                selected = sortOrder == LibrarySortOrder.Ascending,
                onClick = {
                    expanded = false
                    onSetSort(sortBy, LibrarySortOrder.Ascending)
                },
            )
            LibrarySortOrderMenuItem(
                label = stringResource(Res.string.library_sort_order_descending),
                selected = sortOrder == LibrarySortOrder.Descending,
                onClick = {
                    expanded = false
                    onSetSort(sortBy, LibrarySortOrder.Descending)
                },
            )
        }
    }
}

@Composable
internal fun LibraryShuffleButton(
    isPreparing: Boolean,
    onClick: () -> Unit,
) {
    val compact = LocalWindowWidthTier.current == WindowWidthTier.Compact
    val description = stringResource(Res.string.library_shuffle_all_cd)
    if (compact) {
        TooltipIconButton(
            label = description,
            onClick = onClick,
            enabled = !isPreparing,
            modifier = Modifier.size(Dimensions.minTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.controlButtonIconSize),
            )
        }
    } else {
        TextButton(onClick = onClick, enabled = !isPreparing) {
            Icon(imageVector = Icons.Filled.Shuffle, contentDescription = null)
            Text(
                if (isPreparing) {
                    stringResource(Res.string.library_shuffle_preparing)
                } else {
                    stringResource(Res.string.library_shuffle_all)
                },
            )
        }
    }
}

@Composable
private fun LibrarySortOrderMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon =
            if (selected) {
                {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                    )
                }
            } else {
                null
            },
    )
}

@Composable
internal fun LibraryFilterButton(
    filters: LibraryFilterSelection,
    onClick: () -> Unit,
) {
    val activeCount = filters.activeCount
    val description =
        if (activeCount > 0) {
            stringResource(Res.string.library_filters_open_active_cd, activeCount)
        } else {
            stringResource(Res.string.library_filters_open_cd)
        }

    TooltipIconButton(
        label = description,
        onClick = onClick,
        modifier = Modifier.size(Dimensions.minTouchTarget),
    ) {
        BadgedBox(
            badge = {
                if (activeCount > 0) {
                    Badge {
                        Text(activeCount.toString())
                    }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Filled.FilterList,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.controlButtonIconSize),
            )
        }
    }
}

@Composable
internal fun LibraryViewModeButton(
    listMode: Boolean,
    onToggleMode: () -> Unit,
) {
    val description =
        if (listMode) {
            stringResource(Res.string.library_list)
        } else {
            stringResource(Res.string.library_grid)
        }
    TooltipIconButton(
        label = description,
        onClick = onToggleMode,
        modifier = Modifier.size(Dimensions.minTouchTarget),
    ) {
        Icon(
            imageVector =
                if (listMode) {
                    Icons.AutoMirrored.Filled.ViewList
                } else {
                    Icons.Filled.ViewModule
                },
            contentDescription = null,
            modifier = Modifier.size(Dimensions.controlButtonIconSize),
        )
    }
}

@Composable
internal fun LibraryFilterDialog(
    filters: LibraryFilterSelection,
    options: LibraryFilterOptions,
    onApply: (LibraryFilterSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_filters)) },
        text = {
            LibraryFilterContent(
                filters = draft,
                options = options,
                onSetFilters = { selection -> draft = selection },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(draft)
                    onDismiss()
                },
            ) {
                Text(stringResource(Res.string.library_filters_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.library_filters_cancel))
            }
        },
    )
}

@Composable
private fun LibraryFilterContent(
    filters: LibraryFilterSelection,
    options: LibraryFilterOptions,
    onSetFilters: (LibraryFilterSelection) -> Unit,
) {
    val filtersTitle = stringResource(Res.string.library_filters)
    val genresTitle = stringResource(Res.string.library_genres)
    val yearsTitle = stringResource(Res.string.library_years)
    val ratingsTitle = stringResource(Res.string.library_ratings)
    val studiosTitle = stringResource(Res.string.library_studios)
    val playedLabel = stringResource(Res.string.library_watched_played)
    val unplayedLabel = stringResource(Res.string.library_watched_unplayed)
    val resumableLabel = stringResource(Res.string.library_watched_resumable)
    val favoriteLabel = stringResource(Res.string.library_watched_favorite)
    val watchedOptions =
        remember(playedLabel, unplayedLabel, resumableLabel, favoriteLabel) {
            listOf(
                LibraryFilterOption(
                    key = "watched-played",
                    value = LibraryItemFilter.Played,
                    label = playedLabel,
                ),
                LibraryFilterOption(
                    key = "watched-unplayed",
                    value = LibraryItemFilter.Unplayed,
                    label = unplayedLabel,
                ),
                LibraryFilterOption(
                    key = "watched-resumable",
                    value = LibraryItemFilter.Resumable,
                    label = resumableLabel,
                ),
                LibraryFilterOption(
                    key = "watched-favorite",
                    value = LibraryItemFilter.Favorite,
                    label = favoriteLabel,
                ),
            )
        }
    val selectedItemFilters = remember(filters.itemFilters) { filters.itemFilters.toSet() }
    val selectedGenreIds = remember(filters.genreIds) { filters.genreIds.toSet() }
    val selectedYears = remember(filters.years) { filters.years.toSet() }
    val selectedRatings = remember(filters.officialRatings) { filters.officialRatings.toSet() }
    val selectedStudioIds = remember(filters.studioIds) { filters.studioIds.toSet() }
    val filterListState = rememberLazyListState()

    LazyColumn(
        state = filterListState,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = Dimensions.filterSheetMaxHeight)
                .desktopScrollInput(filterListState, DesktopScrollOrientation.Vertical),
        verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
    ) {
        item(key = "watched") {
            FilterGroup(
                title = filtersTitle,
                options = watchedOptions,
                selectedValues = selectedItemFilters,
                onToggle = { filter ->
                    val nextFilters =
                        if (filter == LibraryItemFilter.Favorite) {
                            filters.copy(itemFilters = filters.itemFilters.toggle(filter))
                        } else {
                            filters.withExclusiveWatchedFilter(filter)
                        }
                    onSetFilters(nextFilters)
                },
            )
        }
        filterOptionGroup(
            key = "genres",
            title = genresTitle.trimEnd(':', ' '),
            options = options.genres,
            selectedValues = selectedGenreIds,
            onToggle = { value ->
                onSetFilters(filters.copy(genreIds = filters.genreIds.toggle(value)))
            },
        )
        filterOptionGroup(
            key = "years",
            title = yearsTitle,
            options = options.years,
            selectedValues = selectedYears,
            onToggle = { value ->
                onSetFilters(filters.copy(years = filters.years.toggle(value)))
            },
        )
        filterOptionGroup(
            key = "ratings",
            title = ratingsTitle,
            options = options.ratings,
            selectedValues = selectedRatings,
            onToggle = { value ->
                onSetFilters(filters.copy(officialRatings = filters.officialRatings.toggle(value)))
            },
        )
        filterOptionGroup(
            key = "studios",
            title = studiosTitle.trimEnd(':', ' '),
            options = options.studios,
            selectedValues = selectedStudioIds,
            onToggle = { value ->
                onSetFilters(filters.copy(studioIds = filters.studioIds.toggle(value)))
            },
        )
    }
}

private fun <T> LazyListScope.filterOptionGroup(
    key: String,
    title: String,
    options: List<LibraryFilterOption<T>>,
    selectedValues: Set<T>,
    onToggle: (T) -> Unit,
) {
    if (options.isEmpty()) {
        return
    }
    item(key = key) {
        FilterGroup(
            title = title,
            options = options,
            selectedValues = selectedValues,
            onToggle = onToggle,
        )
    }
}

@Composable
private fun <T> FilterGroup(
    title: String,
    options: List<LibraryFilterOption<T>>,
    selectedValues: Set<T>,
    onToggle: (T) -> Unit,
) {
    val currentOnToggle by rememberUpdatedState(onToggle)
    val rowListState = rememberLazyListState()

    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        LazyRow(
            state = rowListState,
            modifier = Modifier.desktopScrollInput(rowListState, DesktopScrollOrientation.Horizontal),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        ) {
            lazyItems(
                items = options,
                key = { option -> option.key },
            ) { option ->
                val onClick =
                    remember(option.value) {
                        { currentOnToggle(option.value) }
                    }
                FilterChip(
                    selected = option.value in selectedValues,
                    onClick = onClick,
                    label = { Text(option.label) },
                    modifier = Modifier.filterChipDescription(option.label),
                )
            }
        }
    }
}

@Composable
private fun Modifier.filterChipDescription(label: String): Modifier {
    val contentDescription = stringResource(Res.string.library_filter_chip_cd, label)
    return heightIn(min = Dimensions.minTouchTarget)
        .semantics { this.contentDescription = contentDescription }
}

private data class LibrarySortChoice(
    val sortBy: LibrarySortBy,
    val label: StringResource,
)

private fun librarySortChoices(collectionType: LibraryCollectionType): List<LibrarySortChoice> =
    buildList {
        addAll(
            listOf(
                LibrarySortChoice(LibrarySortBy.Name, Res.string.library_sort_name),
                LibrarySortChoice(LibrarySortBy.DateCreated, Res.string.library_sort_date_added),
                LibrarySortChoice(LibrarySortBy.PremiereDate, Res.string.library_sort_premiere_date),
                LibrarySortChoice(LibrarySortBy.CommunityRating, Res.string.library_sort_community_rating),
                LibrarySortChoice(LibrarySortBy.Runtime, Res.string.library_sort_runtime),
            ),
        )
        if (LibrarySortBy.VideoBitRate.isAvailableFor(collectionType)) {
            add(LibrarySortChoice(LibrarySortBy.VideoBitRate, Res.string.library_sort_bitrate))
        }
        if (LibrarySortBy.DateLastContentAdded.isAvailableFor(collectionType)) {
            add(LibrarySortChoice(LibrarySortBy.DateLastContentAdded, Res.string.library_sort_last_episode_added))
        }
    }

private fun LibraryFilterSelection.withExclusiveWatchedFilter(filter: LibraryItemFilter): LibraryFilterSelection {
    val withoutWatched =
        itemFilters -
            LibraryItemFilter.Played -
            LibraryItemFilter.Unplayed -
            LibraryItemFilter.Resumable
    return copy(
        itemFilters =
            if (filter in itemFilters) {
                withoutWatched
            } else {
                withoutWatched + filter
            },
    )
}

private fun <T> List<T>.toggle(value: T): List<T> =
    if (value in this) {
        this - value
    } else {
        this + value
    }
