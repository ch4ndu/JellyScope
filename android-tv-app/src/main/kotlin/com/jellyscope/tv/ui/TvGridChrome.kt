// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.grid.GridSort
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailText as TvText

@Composable
internal fun TvGridError(
    retryable: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvText(
            text = stringResource(R.string.tv_grid_error),
            color = LocalJellyfinPalette.current.error,
        )
        if (retryable) {
            TvButton(
                text = stringResource(R.string.tv_retry),
                onClick = onRetry,
            )
        }
    }
}

@Composable
internal fun TvGridSortPickerOverlay(
    selected: GridSort,
    onSelectSort: (GridSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstRowRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        firstRowRequester.requestFocusSafely()
    }

    TvPickerOverlay(
        title = stringResource(R.string.tv_sort_title),
        onDismiss = onDismiss,
    ) {
        GridSort.values().forEachIndexed { index, sort ->
            TvPickerRow(
                title = gridSortLabel(sort),
                selected = selected == sort,
                focusRequester = firstRowRequester.takeIf { index == 0 },
                onClick = { onSelectSort(sort) },
                shape = RoundedCornerShape(6.dp),
            )
        }
    }
}

@Composable
internal fun gridRowTitle(row: HomeRow): String =
    when (row) {
        HomeRow.ContinueWatching -> stringResource(R.string.tv_continue_watching)
        HomeRow.Favorites -> stringResource(R.string.tv_favorites)
        HomeRow.NextUp -> stringResource(R.string.tv_next_up)
        HomeRow.RecentlyAdded -> stringResource(R.string.tv_recently_added)
    }

@Composable
internal fun gridSortLabel(sort: GridSort): String =
    when (sort) {
        GridSort.Default -> stringResource(R.string.tv_sort_default)
        GridSort.Title -> stringResource(R.string.tv_sort_name)
        GridSort.DateAdded -> stringResource(R.string.tv_sort_date_added)
        GridSort.ReleaseDate -> stringResource(R.string.tv_sort_release_date)
        GridSort.Runtime -> stringResource(R.string.tv_sort_runtime)
    }
