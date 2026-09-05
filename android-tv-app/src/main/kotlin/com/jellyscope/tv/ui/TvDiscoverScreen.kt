// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.discover.DiscoverFacetUi
import com.jellyscope.ui.screen.discover.DiscoverListState
import com.jellyscope.ui.screen.discover.DiscoverMediaItemsState
import com.jellyscope.ui.screen.discover.DiscoverSection
import com.jellyscope.ui.screen.discover.DiscoverUiState
import com.jellyscope.ui.screen.discover.DiscoverViewModel
import com.jellyscope.ui.screen.discover.sectionHasItems
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.jellyscope.ui.component.DetailText as TvText

@Composable
fun TvDiscoverScreen(
    session: Session,
    onBack: () -> Unit,
    onGenreSelected: (DiscoverFacetUi) -> Unit,
    onStudioSelected: (DiscoverFacetUi) -> Unit,
    onCollectionSelected: (MediaCardUi) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
    parentId: String? = null,
    viewModel: DiscoverViewModel =
        koinViewModel(
            key = "discover-${parentId.orEmpty()}",
            parameters = { parametersOf(session, parentId) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    OnResumeEffect {
        viewModel.refreshSilently()
    }

    TvDiscoverContent(
        session = session,
        state = state,
        onBack = onBack,
        onGenreSelected = onGenreSelected,
        onStudioSelected = onStudioSelected,
        onCollectionSelected = onCollectionSelected,
        onItemSelected = onItemSelected,
        onItemPlayDirect = onItemPlayDirect,
        onRetryGenres = viewModel::retryGenres,
        onRetryStudios = viewModel::retryStudios,
        onRetryCollections = viewModel::retryCollections,
        onRetrySuggestions = viewModel::retrySuggestions,
        onRetryUpcoming = viewModel::retryUpcoming,
        modifier = modifier,
    )
}

@Composable
internal fun TvDiscoverContent(
    session: Session,
    state: DiscoverUiState,
    onBack: () -> Unit,
    onGenreSelected: (DiscoverFacetUi) -> Unit,
    onStudioSelected: (DiscoverFacetUi) -> Unit,
    onCollectionSelected: (MediaCardUi) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onRetryGenres: () -> Unit,
    onRetryStudios: () -> Unit,
    onRetryCollections: () -> Unit,
    onRetrySuggestions: () -> Unit,
    onRetryUpcoming: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSection by rememberSaveable { mutableStateOf(DiscoverSection.Genres) }
    val gridFocusRequester = remember { FocusRequester() }
    val sectionTabRequester = remember { FocusRequester() }
    val loadingFocusRequester = remember { FocusRequester() }
    val hostedRailController = LocalTvHostedRailController.current
    val hostedRail = hostedRailController.enabled
    val hostedRailVisible = hostedRailController.visible
    val requestInitialContentFocus = !hostedRailController.contentAutofocusSuppressed
    val hostedRailContentRegistrationKey = remember { hostedRailController.contentRegistrationKey }
    val sectionHasItems = state.sectionHasItems(selectedSection)
    val sectionLoading = state.isSectionLoading(selectedSection)
    val currentSectionHasItems by rememberUpdatedState(sectionHasItems)
    val currentSectionLoading by rememberUpdatedState(sectionLoading)
    val railHasFocus = hostedRailController.railHasFocus
    val contentStartPadding =
        if (hostedRailVisible) {
            TvDimens.drawerContentStartPadding
        } else {
            TvDimens.overscanHorizontal
        }
    val focusContent =
        remember(gridFocusRequester, loadingFocusRequester, sectionTabRequester) {
            {
                requestDiscoverContentFocus(
                    gridFocusRequester = gridFocusRequester,
                    loadingFocusRequester = loadingFocusRequester,
                    sectionTabRequester = sectionTabRequester,
                    sectionHasItems = currentSectionHasItems,
                    sectionLoading = currentSectionLoading,
                )
            }
        }

    LaunchedEffect(hostedRailVisible, hostedRailContentRegistrationKey, sectionTabRequester, focusContent) {
        if (hostedRailVisible) {
            hostedRailController.setContentRightFocusRequester(
                hostedRailContentRegistrationKey,
                sectionTabRequester,
            )
            hostedRailController.setContentRightFocusAction(
                hostedRailContentRegistrationKey,
                focusContent,
            )
        }
    }

    fun requestRailFocus(): Boolean =
        if (hostedRail) {
            hostedRailController.requestRailFocus()
        } else {
            false
        }

    // Return focus to an ALWAYS-composed anchor. The section tabs are never
    // detached (unlike a lazy row's item 0), so this succeeds on first entry,
    // on RIGHT from the rail, and on re-entry from another tab.
    LaunchedEffect(Unit) {
        if (requestInitialContentFocus) {
            requestTvFocusWithRetry {
                sectionTabRequester.requestFocusSafely() ||
                    (currentSectionLoading && loadingFocusRequester.requestFocusSafely())
            }
        }
    }

    BackHandler(enabled = !railHasFocus) {
        if (!requestRailFocus()) {
            onBack()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalAppBackgroundBrush.current),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
        ) {
            TvText(
                text = stringResource(R.string.tv_discover_title),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = contentStartPadding,
                            top = TvDimens.overscanVertical,
                            end = TvDimens.overscanHorizontal,
                        ),
                style = TvScreenHeaderStyle,
                maxLines = 1,
            )
            TvDiscoverSectionTabs(
                selectedSection = selectedSection,
                sectionHasItems = sectionHasItems,
                selectedTabRequester = sectionTabRequester,
                gridFocusRequester = gridFocusRequester,
                onSectionSelected = { section -> selectedSection = section },
                modifier =
                    Modifier.padding(
                        start = contentStartPadding,
                        end = TvDimens.overscanHorizontal,
                    ),
            )
            TvDiscoverSectionBody(
                session = session,
                state = state,
                section = selectedSection,
                contentStartPadding = contentStartPadding,
                gridFocusRequester = gridFocusRequester,
                loadingFocusRequester = loadingFocusRequester,
                onRequestRailFocus = ::requestRailFocus,
                onGenreSelected = onGenreSelected,
                onStudioSelected = onStudioSelected,
                onCollectionSelected = onCollectionSelected,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                onRetryGenres = onRetryGenres,
                onRetryStudios = onRetryStudios,
                onRetryCollections = onRetryCollections,
                onRetrySuggestions = onRetrySuggestions,
                onRetryUpcoming = onRetryUpcoming,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

private fun requestDiscoverContentFocus(
    gridFocusRequester: FocusRequester,
    loadingFocusRequester: FocusRequester,
    sectionTabRequester: FocusRequester,
    sectionHasItems: Boolean,
    sectionLoading: Boolean,
): Boolean =
    (sectionHasItems && gridFocusRequester.requestFocusSafely()) ||
        sectionTabRequester.requestFocusSafely() ||
        (sectionLoading && loadingFocusRequester.requestFocusSafely())

private fun DiscoverUiState.isSectionLoading(section: DiscoverSection): Boolean =
    when (section) {
        DiscoverSection.Genres -> genres is DiscoverListState.Loading
        DiscoverSection.Studios -> studios is DiscoverListState.Loading
        DiscoverSection.Collections -> collectionMediaState is DiscoverMediaItemsState.Loading
        DiscoverSection.Suggestions -> suggestionMediaState is DiscoverMediaItemsState.Loading
        DiscoverSection.Upcoming -> upcomingMediaState is DiscoverMediaItemsState.Loading
    }
