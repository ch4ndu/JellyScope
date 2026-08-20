// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.TopBarSlideEnter
import com.jellyscope.ui.component.TopBarSlideExit
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.component.rememberAutoHidingTopBarVisible
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.discover_title
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DiscoverScreen(
    session: Session,
    onGenreSelected: (DiscoverFacetUi) -> Unit,
    onStudioSelected: (DiscoverFacetUi) -> Unit,
    onCollectionSelected: (MediaCardUi) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
    viewModel: DiscoverViewModel =
        koinViewModel(
            parameters = { parametersOf(session, null as String?) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnResumeEffect(viewModel::refreshSilently)

    DiscoverContent(
        session = session,
        state = state,
        onRetryGenres = viewModel::retryGenres,
        onRetryStudios = viewModel::retryStudios,
        onRetryCollections = viewModel::retryCollections,
        onRetrySuggestions = viewModel::retrySuggestions,
        onRetryUpcoming = viewModel::retryUpcoming,
        onGenreSelected = onGenreSelected,
        onStudioSelected = onStudioSelected,
        onCollectionSelected = onCollectionSelected,
        onItemSelected = onItemSelected,
        onSettingsClick = onSettingsClick,
        modifier = modifier,
        bottomContentPadding = bottomContentPadding,
    )
}

@Composable
fun DiscoverContent(
    session: Session,
    state: DiscoverUiState,
    onRetryGenres: () -> Unit,
    onRetryStudios: () -> Unit,
    onRetryCollections: () -> Unit,
    onRetrySuggestions: () -> Unit,
    onRetryUpcoming: () -> Unit,
    onGenreSelected: (DiscoverFacetUi) -> Unit,
    onStudioSelected: (DiscoverFacetUi) -> Unit,
    onCollectionSelected: (MediaCardUi) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = Dimensions.zero,
) {
    var selectedSection by rememberSaveable { mutableStateOf(DiscoverSection.Genres) }
    val gridState = rememberLazyGridState()
    val tabsVisible = rememberAutoHidingTopBarVisible(gridState, selectedSection)
    val title = stringResource(Res.string.discover_title)
    val topBarContentPadding = appTopBarContentPadding()
    val density = LocalDensity.current
    var barsHeightPx by remember(density, topBarContentPadding) {
        mutableStateOf(with(density) { topBarContentPadding.roundToPx() })
    }
    val topBarsHeight = with(density) { barsHeightPx.toDp() }

    LaunchedEffect(selectedSection) {
        gridState.scrollToItem(0)
    }

    // Edge-to-edge grid with the top bar + section tabs overlaid on top and a
    // constant top contentPadding equal to the measured bar height, mirroring the
    // Library tab: the grid draws under the status bar and does NOT reflow when
    // the chrome auto-hides.
    Box(modifier = modifier.fillMaxSize()) {
        DiscoverSectionBody(
            session = session,
            state = state,
            selectedSection = selectedSection,
            gridState = gridState,
            onRetryGenres = onRetryGenres,
            onRetryStudios = onRetryStudios,
            onRetryCollections = onRetryCollections,
            onRetrySuggestions = onRetrySuggestions,
            onRetryUpcoming = onRetryUpcoming,
            onGenreSelected = onGenreSelected,
            onStudioSelected = onStudioSelected,
            onCollectionSelected = onCollectionSelected,
            onItemSelected = onItemSelected,
            topContentPadding = topBarsHeight,
            bottomContentPadding = bottomContentPadding,
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible = tabsVisible,
            enter = TopBarSlideEnter,
            exit = TopBarSlideExit,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { size ->
                        if (size.height > barsHeightPx) {
                            barsHeightPx = size.height
                        }
                    },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
            ) {
                AppTopBar(
                    title = title,
                    onSettingsClick = onSettingsClick,
                )
                DiscoverSectionTabs(
                    selectedSection = selectedSection,
                    onSectionSelected = { section -> selectedSection = section },
                )
            }
        }
    }
}
