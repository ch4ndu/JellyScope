// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.adaptive.adaptiveScreenPadding
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.TooltipIconButton
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.component.appTopBarHeight
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.rememberAutoHidingTopBarVisible
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.home_favorites
import com.jellyscope.ui.generated.resources.home_next_up
import com.jellyscope.ui.generated.resources.home_recently_added
import com.jellyscope.ui.generated.resources.home_refresh_button
import com.jellyscope.ui.generated.resources.home_resume
import com.jellyscope.ui.generated.resources.home_title
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun HomeScreen(
    session: Session,
    onItemSelected: (MediaCardUi) -> Unit,
    onViewAllSelected: (HomeRow) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
    viewModel: HomeViewModel =
        koinViewModel(
            parameters = { parametersOf(session) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnResumeEffect(viewModel::refreshSilently)

    HomeContent(
        session = session,
        state = state,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retry,
        onItemSelected = onItemSelected,
        onViewAllSelected = onViewAllSelected,
        onPlayItem = onPlayItem,
        onSettingsClick = onSettingsClick,
        modifier = modifier,
        bottomContentPadding = bottomContentPadding,
    )
}

@Composable
fun HomeContent(
    session: Session,
    state: HomeUiState,
    onRefresh: () -> Unit,
    onRetry: (HomeRow) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onViewAllSelected: (HomeRow) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
) {
    val listState = rememberLazyListState()
    val topBarVisible = rememberAutoHidingTopBarVisible(listState)
    val title = stringResource(Res.string.home_title, session.serverName)
    val refreshContentDescription = stringResource(Res.string.home_refresh_button)
    val screenBottomContentPadding = adaptiveScreenPadding()
    val featuredHeroAvailable =
        state.featured is RowState.Loading ||
            (state.featured as? RowState.Content)?.items.orEmpty().isNotEmpty()
    val hasFeaturedHeroSlot = shouldShowFeaturedHero(LocalWindowWidthTier.current, featuredHeroAvailable)
    // The featured carousel rests flush below the topBar: reserve the topBar
    // HEIGHT (status bar + bar) but NOT the extra screen-padding gap, so it's
    // neither clipped under the bar nor pushed down by a gap. Without a carousel,
    // use the full content padding so the first row has the normal gap.
    val topContentPadding =
        if (hasFeaturedHeroSlot) {
            appTopBarHeight()
        } else {
            appTopBarContentPadding()
        }

    // An Empty row is dropped entirely (no header, no placeholder) instead of
    // showing "Nothing here yet."; Loading/Error/Content rows stay visible. When
    // nothing at all is visible, fall back to a single empty-home message so a
    // fresh account doesn't land on a blank screen. The visibility/fallback
    // policy is pure and tested (see HomeLayoutPolicy).
    val visibleRows = state.visibleHomeRows()
    val showEmptyHome = state.shouldShowEmptyHome(hasFeaturedHeroSlot)

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .desktopScrollInput(listState, DesktopScrollOrientation.Vertical),
            contentPadding =
                PaddingValues(
                    top = topContentPadding,
                    bottom = screenBottomContentPadding + bottomContentPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.homeRowSpacing),
        ) {
            if (hasFeaturedHeroSlot) {
                item(key = "featured-hero") {
                    FeaturedHeroCarousel(
                        featured = state.featured,
                        session = session,
                        onPlayItem = onPlayItem,
                        onItemSelected = onItemSelected,
                    )
                }
            }
            if (showEmptyHome) {
                item(key = "home-empty") {
                    HomeEmptyState(modifier = Modifier.fillParentMaxSize())
                }
            } else {
                items(
                    items = visibleRows,
                    key = { row -> row },
                ) { row ->
                    HomeRowSection(
                        title = stringResource(row.titleResource()),
                        row = row,
                        rowState = state.rowState(row),
                        session = session,
                        onRetry = onRetry,
                        onItemSelected = onItemSelected,
                        onViewAllSelected = onViewAllSelected,
                        onPlayItem = onPlayItem,
                    )
                }
            }
        }
        AppTopBar(
            title = title,
            visible = topBarVisible,
            onSettingsClick = onSettingsClick,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TooltipIconButton(
                label = refreshContentDescription,
                onClick = onRefresh,
                modifier = Modifier.size(Dimensions.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                )
            }
        }
    }
}

// Render order of the home shelves (distinct from the HomeRow enum's declaration
// order). Featured is rendered separately as the hero carousel, not as a shelf.
private val HOME_ROW_RENDER_ORDER =
    listOf(
        HomeRow.NextUp,
        HomeRow.ContinueWatching,
        HomeRow.RecentlyAdded,
        HomeRow.Favorites,
    )

/** Shelves that should render: everything except rows that resolved to Empty. */
internal fun HomeUiState.visibleHomeRows(): List<HomeRow> = HOME_ROW_RENDER_ORDER.filter { row -> rowState(row) !is RowState.Empty }

/**
 * True when there is nothing to show at all — no featured hero and every shelf
 * is Empty — so the screen renders the empty-home message instead of a blank
 * page. A still-loading or errored shelf counts as visible, so this only trips
 * once the account genuinely has no content.
 */
internal fun HomeUiState.shouldShowEmptyHome(hasFeaturedHeroSlot: Boolean): Boolean = !hasFeaturedHeroSlot && visibleHomeRows().isEmpty()

private fun HomeRow.titleResource() =
    when (this) {
        HomeRow.NextUp -> Res.string.home_next_up
        HomeRow.ContinueWatching -> Res.string.home_resume
        HomeRow.RecentlyAdded -> Res.string.home_recently_added
        HomeRow.Favorites -> Res.string.home_favorites
    }

internal fun shouldShowFeaturedHero(
    widthTier: WindowWidthTier,
    featuredAvailable: Boolean,
): Boolean = featuredAvailable && widthTier != WindowWidthTier.XLarge
