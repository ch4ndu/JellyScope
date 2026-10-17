// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.rememberAutoHidingTopBarVisible
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.find_empty
import com.jellyscope.ui.generated.resources.find_title
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun FindScreen(
    session: Session,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
    viewModel: FindViewModel =
        koinViewModel(
            parameters = { parametersOf(session) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnResumeEffect(viewModel::refreshSilently)

    FindContent(
        session = session,
        state = state,
        onBack = onBack,
        onSettingsClick = onSettingsClick,
        onQueryTextChanged = viewModel::onQueryTextChanged,
        onClearQuery = viewModel::clearQuery,
        onSearchSubmit = viewModel::commitRecentSearch,
        onGenreToggled = viewModel::toggleGenre,
        onRuntimeSelected = viewModel::selectRuntimeBucket,
        onWatchedFilterSelected = viewModel::selectWatchedFilter,
        onPersonSelected = viewModel::selectPerson,
        onRecentSelected = viewModel::selectRecentSearch,
        onClearRecentSearches = viewModel::clearRecentSearches,
        onResultTabSelected = viewModel::selectResultTab,
        onRetry = viewModel::retry,
        onItemSelected = onItemSelected,
        modifier = modifier,
        bottomContentPadding = bottomContentPadding,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FindContent(
    session: Session,
    state: FindUiState,
    onBack: () -> Unit,
    onSettingsClick: () -> Unit,
    onQueryTextChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSearchSubmit: () -> Unit,
    onGenreToggled: (String) -> Unit,
    onRuntimeSelected: (RuntimeBucket) -> Unit,
    onWatchedFilterSelected: (WatchedFilter) -> Unit,
    onPersonSelected: (PersonUi) -> Unit,
    onRecentSelected: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onResultTabSelected: (FindResultTab) -> Unit,
    onRetry: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
) {
    val gridState = rememberLazyGridState()
    val topBarVisible = rememberAutoHidingTopBarVisible(gridState)
    val title = stringResource(Res.string.find_title)
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val resultColumns =
            resultColumnCount(
                maxWidth = maxWidth,
                horizontalContentPadding = horizontalContentPadding.horizontal,
                itemWidth = Dimensions.posterCardWidth.tileScaled(),
            )
        LazyVerticalGrid(
            columns = GridCells.Fixed(resultColumns),
            state = gridState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .desktopScrollInput(gridState, DesktopScrollOrientation.Vertical),
            contentPadding =
                PaddingValues(
                    start = horizontalContentPadding.start,
                    top = appTopBarContentPadding(),
                    end = horizontalContentPadding.end,
                    bottom = Dimensions.screenPadding + bottomContentPadding,
                ),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
        ) {
            item(
                key = "search",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                FindSearchHeader(
                    state = state,
                    onQueryTextChanged = onQueryTextChanged,
                    onClearQuery = onClearQuery,
                    onSearchSubmit = onSearchSubmit,
                )
            }
            if (state.queryText.isBlank()) {
                item(
                    key = "questionnaire",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    QuestionnaireCard(
                        state = state,
                        onGenreToggled = onGenreToggled,
                        onRuntimeSelected = onRuntimeSelected,
                        onWatchedFilterSelected = onWatchedFilterSelected,
                    )
                }
            }
            if (state.queryText.isBlank() && state.recentSearches.isNotEmpty()) {
                item(
                    key = "recent",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    RecentSearches(
                        searches = state.recentSearches,
                        onRecentSelected = onRecentSelected,
                        onClear = onClearRecentSearches,
                    )
                }
            }
            if (state.personSuggestions.isNotEmpty()) {
                item(
                    key = "people",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    PersonSuggestions(
                        session = session,
                        people = state.personSuggestions,
                        onPersonSelected = onPersonSelected,
                    )
                }
            }
            if (state.isSearching) {
                item(
                    key = "loading",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    LoadingRow()
                }
            }
            if (state.error) {
                item(
                    key = "error",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    ErrorRow(onRetry = onRetry)
                }
            }
            if (!state.groupedResults.isEmpty) {
                resultsContent(
                    session = session,
                    state = state,
                    onResultTabSelected = onResultTabSelected,
                    onItemSelected = onItemSelected,
                )
            } else if (!state.isSearching && !state.error && state.hasActiveFindUi()) {
                item(
                    key = "empty",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        text = stringResource(Res.string.find_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AppTopBar(
            title = title,
            visible = topBarVisible,
            onBack = onBack,
            onSettingsClick = onSettingsClick,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

private fun resultColumnCount(
    maxWidth: androidx.compose.ui.unit.Dp,
    horizontalContentPadding: androidx.compose.ui.unit.Dp,
    itemWidth: androidx.compose.ui.unit.Dp,
): Int {
    val contentWidth = maxWidth - horizontalContentPadding
    val itemSlot = itemWidth + Dimensions.cardSpacing
    return ((contentWidth.value + Dimensions.cardSpacing.value) / itemSlot.value)
        .toInt()
        .coerceAtLeast(1)
}
