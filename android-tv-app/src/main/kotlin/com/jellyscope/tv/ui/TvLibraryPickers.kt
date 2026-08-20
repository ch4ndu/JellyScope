// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFacet
import com.jellyscope.core.domain.model.LibraryFacets
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemFilter
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.isAvailableFor
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvLibrarySortPickerOverlay(
    selectedSortBy: LibrarySortBy,
    selectedSortOrder: LibrarySortOrder,
    collectionType: LibraryCollectionType,
    onSelectSort: (LibrarySortBy, LibrarySortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstRowRequester = remember { FocusRequester() }
    val sortChoices = remember(collectionType) { LibrarySortBy.entries.filter { sortBy -> sortBy.isAvailableFor(collectionType) } }

    TvLibraryPickerDialog(
        initialFocusRequester = firstRowRequester,
        onDismiss = onDismiss,
    ) {
        TvPickerOverlay(
            title = stringResource(R.string.tv_sort_title),
            onDismiss = onDismiss,
            maxHeight = TvDimens.browsePickerMaxHeight,
            scrollable = true,
            opaque = true,
        ) {
            sortChoices.forEachIndexed { index, sortBy ->
                TvPickerRow(
                    title = librarySortLabel(sortBy),
                    selected = sortBy == selectedSortBy,
                    focusRequester = firstRowRequester.takeIf { index == 0 },
                    onClick = {
                        onSelectSort(
                            sortBy,
                            if (
                                sortBy == LibrarySortBy.VideoBitRate ||
                                sortBy == LibrarySortBy.DateLastContentAdded
                            ) {
                                LibrarySortOrder.Descending
                            } else {
                                selectedSortOrder
                            },
                        )
                    },
                )
            }
            TvPickerDivider()
            LibrarySortOrder.entries.forEach { sortOrder ->
                TvPickerRow(
                    title = librarySortOrderLabel(sortOrder),
                    selected = sortOrder == selectedSortOrder,
                    focusRequester = null,
                    onClick = { onSelectSort(selectedSortBy, sortOrder) },
                )
            }
        }
    }
}

@Composable
internal fun TvLibraryFilterPickerOverlay(
    filters: LibraryFilterSelection,
    facets: LibraryFacets,
    onSelectFilters: (LibraryFilterSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstRowRequester = remember { FocusRequester() }

    TvLibraryPickerDialog(
        initialFocusRequester = firstRowRequester,
        onDismiss = onDismiss,
    ) {
        TvPickerOverlay(
            title = stringResource(R.string.tv_filter_title),
            onDismiss = onDismiss,
            maxHeight = TvDimens.browsePickerMaxHeight,
            opaque = true,
        ) {
            TvPickerRow(
                title = stringResource(R.string.tv_filter_played),
                selected = LibraryItemFilter.Played in filters.itemFilters,
                focusRequester = firstRowRequester,
                onClick = { onSelectFilters(filters.toggleItemFilter(LibraryItemFilter.Played)) },
            )
            TvPickerRow(
                title = stringResource(R.string.tv_filter_unplayed),
                selected = LibraryItemFilter.Unplayed in filters.itemFilters,
                focusRequester = null,
                onClick = { onSelectFilters(filters.toggleItemFilter(LibraryItemFilter.Unplayed)) },
            )
            TvPickerRow(
                title = stringResource(R.string.tv_filter_favorites),
                selected = LibraryItemFilter.Favorite in filters.itemFilters,
                focusRequester = null,
                onClick = { onSelectFilters(filters.toggleItemFilter(LibraryItemFilter.Favorite)) },
            )
            TvPickerDivider()
            TvText(
                text = stringResource(R.string.tv_filter_genres),
                style = TvSecondaryStyle.copy(fontWeight = FontWeight.SemiBold),
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(vertical = TvDimens.detailBadgeGap),
            )
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = TvDimens.browsePickerGenreListMaxHeight),
                verticalArrangement = Arrangement.spacedBy(TvDimens.progressHeight),
            ) {
                items(
                    items = facets.genres,
                    key = { genre -> genre.id ?: genre.name },
                ) { genre ->
                    val genreId = genre.id
                    TvPickerRow(
                        title = genre.displayName,
                        selected = genreId != null && genreId in filters.genreIds,
                        focusRequester = null,
                        onClick = { genreId?.let { id -> onSelectFilters(filters.toggleGenreId(id)) } },
                    )
                }
            }
            TvPickerDivider()
            TvPickerRow(
                title = stringResource(R.string.tv_filter_clear),
                selected = filters == LibraryFilterSelection(),
                focusRequester = null,
                onClick = { onSelectFilters(LibraryFilterSelection()) },
            )
        }
    }
}

@Composable
private fun TvLibraryPickerDialog(
    initialFocusRequester: FocusRequester,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        LaunchedEffect(initialFocusRequester) {
            requestTvFocusWithRetry { initialFocusRequester.requestFocusSafely() }
        }
        content()
    }
}

@Composable
internal fun librarySortLabel(sortBy: LibrarySortBy): String =
    when (sortBy) {
        LibrarySortBy.Name -> stringResource(R.string.tv_sort_name)
        LibrarySortBy.Random -> stringResource(R.string.tv_sort_random)
        LibrarySortBy.CommunityRating -> stringResource(R.string.tv_sort_community_rating)
        LibrarySortBy.CriticRating -> stringResource(R.string.tv_sort_critic_rating)
        LibrarySortBy.DateCreated -> stringResource(R.string.tv_sort_date_added)
        LibrarySortBy.DatePlayed -> stringResource(R.string.tv_sort_date_played)
        LibrarySortBy.PremiereDate -> stringResource(R.string.tv_sort_release_date)
        LibrarySortBy.Runtime -> stringResource(R.string.tv_sort_runtime)
        LibrarySortBy.OfficialRating -> stringResource(R.string.tv_sort_official_rating)
        LibrarySortBy.PlayCount -> stringResource(R.string.tv_sort_play_count)
        LibrarySortBy.VideoBitRate -> stringResource(R.string.tv_sort_bitrate)
        LibrarySortBy.DateLastContentAdded -> stringResource(R.string.tv_sort_last_episode_added)
    }

@Composable
internal fun librarySortOrderLabel(sortOrder: LibrarySortOrder): String =
    when (sortOrder) {
        LibrarySortOrder.Ascending -> stringResource(R.string.tv_sort_order_ascending)
        LibrarySortOrder.Descending -> stringResource(R.string.tv_sort_order_descending)
    }

private val LibraryFacet.displayName: String
    get() = name.takeIf { value -> value.isNotBlank() } ?: id.orEmpty()

private fun LibraryFilterSelection.toggleGenre(genre: String): LibraryFilterSelection =
    copy(
        genres =
            if (genre in genres) {
                genres - genre
            } else {
                genres + genre
            },
    )

private fun LibraryFilterSelection.toggleGenreId(genreId: String): LibraryFilterSelection =
    copy(
        genreIds =
            if (genreId in genreIds) {
                genreIds - genreId
            } else {
                genreIds + genreId
            },
    )

private fun LibraryFilterSelection.toggleItemFilter(filter: LibraryItemFilter): LibraryFilterSelection {
    val nextFilters =
        when (filter) {
            LibraryItemFilter.Played ->
                if (filter in itemFilters) {
                    itemFilters - filter
                } else {
                    itemFilters.filterNot { item -> item == LibraryItemFilter.Unplayed } + filter
                }
            LibraryItemFilter.Unplayed ->
                if (filter in itemFilters) {
                    itemFilters - filter
                } else {
                    itemFilters.filterNot { item -> item == LibraryItemFilter.Played } + filter
                }
            LibraryItemFilter.Resumable,
            LibraryItemFilter.Favorite,
            ->
                if (filter in itemFilters) {
                    itemFilters - filter
                } else {
                    itemFilters + filter
                }
        }
    return copy(itemFilters = nextFilters)
}
