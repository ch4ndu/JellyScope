// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.RuntimeBucket
import com.jellyscope.core.domain.model.WatchedFilter
import com.jellyscope.core.domain.playback.PlayerAudioMode
import com.jellyscope.core.domain.playback.PlayerBackend
import com.jellyscope.core.domain.playback.PlayerHdrMode
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.screen.collection.CollectionContent
import com.jellyscope.ui.screen.discover.DiscoverContent
import com.jellyscope.ui.screen.find.FindContent
import com.jellyscope.ui.screen.grid.GridContent
import com.jellyscope.ui.screen.grid.GridSort
import com.jellyscope.ui.screen.home.HomeContent
import com.jellyscope.ui.screen.library.LibraryBrowseGridContent
import com.jellyscope.ui.screen.library.LibraryDropdown
import com.jellyscope.ui.screen.library.LibraryListModeContent
import com.jellyscope.ui.screen.library.LibraryRecommendationRowState
import com.jellyscope.ui.screen.library.LibraryRecommendationRowUi
import com.jellyscope.ui.screen.library.LibraryRecommendedContent
import com.jellyscope.ui.screen.settings.SettingsContent

@JellyScopeScreenPreviews
@Composable
private fun HomeContentPreview() {
    JellyScopePreviewSurface {
        HomeContent(
            session = PreviewFixtures.session,
            state = PreviewFixtures.homeState,
            onRefresh = {},
            onRetry = {},
            onItemSelected = {},
            onViewAllSelected = {},
            onPlayItem = { _, _ -> },
            onSettingsClick = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun DiscoverContentPreview() {
    JellyScopePreviewSurface {
        DiscoverContent(
            session = PreviewFixtures.session,
            state = PreviewFixtures.discoverState,
            onRetryGenres = {},
            onRetryStudios = {},
            onRetryCollections = {},
            onRetrySuggestions = {},
            onRetryUpcoming = {},
            onGenreSelected = {},
            onStudioSelected = {},
            onCollectionSelected = {},
            onItemSelected = {},
            onSettingsClick = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun FindContentPreview() {
    JellyScopePreviewSurface {
        FindContent(
            session = PreviewFixtures.session,
            state = PreviewFixtures.findState,
            onBack = {},
            onSettingsClick = {},
            onQueryTextChanged = {},
            onClearQuery = {},
            onSearchSubmit = {},
            onGenreToggled = {},
            onRuntimeSelected = { _: RuntimeBucket -> },
            onWatchedFilterSelected = { _: WatchedFilter -> },
            onPersonSelected = {},
            onRecentSelected = {},
            onClearRecentSearches = {},
            onResultTabSelected = {},
            onRetry = {},
            onItemSelected = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun LibraryGridPreview() {
    JellyScopePreviewSurface {
        LibraryBrowseGridContent(
            session = PreviewFixtures.session,
            state = PreviewFixtures.overflowingLibraryState,
            gridState = rememberLazyGridState(),
            onRetry = {},
            onLoadMore = {},
            onSetSort = { _: LibrarySortBy, _: LibrarySortOrder -> },
            onSetFilters = { _: LibraryFilterSelection -> },
            onShuffleAll = {},
            onItemSelected = {},
            onPlayItem = { _, _ -> },
            topBarContent = { actions ->
                AppTopBar(
                    title = "Movies",
                    visible = true,
                    onBack = {},
                    onSettingsClick = {},
                    actions = actions,
                )
            },
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun LibraryDropdownPreview() {
    JellyScopePreviewSurface {
        AppTopBar(
            title = "Library",
            titleContent = {
                LibraryDropdown(
                    libraries = PreviewFixtures.libraries,
                    selectedLibrary = PreviewFixtures.libraries.first(),
                    onLibrarySelected = {},
                )
            },
            visible = true,
            onSettingsClick = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun LibraryRecommendedPreview() {
    JellyScopePreviewSurface {
        LibraryRecommendedContent(
            session = PreviewFixtures.session,
            states =
                mapOf(
                    LibraryRecommendationSection.ContinueWatching to
                        LibraryRecommendationRowState.Content(
                            listOf(
                                LibraryRecommendationRowUi(
                                    key = "continue-watching",
                                    section = LibraryRecommendationSection.ContinueWatching,
                                    reason = null,
                                    baselineItemName = null,
                                    items = PreviewFixtures.mediaCards.take(4),
                                ),
                            ),
                        ),
                ),
            listState = rememberLazyListState(),
            onRetry = {},
            onItemSelected = {},
            topPadding = 16.dp,
            bottomPadding = 16.dp,
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun LibraryListPreview() {
    JellyScopePreviewSurface {
        LibraryListModeContent(
            session = PreviewFixtures.session,
            state = PreviewFixtures.overflowingLibraryState,
            listState = rememberLazyListState(),
            onRetry = {},
            onLoadMore = {},
            onSetSort = { _: LibrarySortBy, _: LibrarySortOrder -> },
            onSetFilters = { _: LibraryFilterSelection -> },
            onShuffleAll = {},
            onToggleMode = {},
            onItemSelected = {},
            onPlayItem = { _, _ -> },
            topBarContent = { actions ->
                AppTopBar(
                    title = "Movies",
                    visible = true,
                    onBack = {},
                    onSettingsClick = {},
                    actions = actions,
                )
            },
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun GridContentPreview() {
    JellyScopePreviewSurface {
        GridContent(
            session = PreviewFixtures.session,
            state = PreviewFixtures.gridState,
            row = PreviewFixtures.gridState.row,
            onBack = {},
            onRetry = {},
            onSortSelected = { _: GridSort -> },
            onShuffle = {},
            onItemSelected = {},
            onPlayItem = { _, _ -> },
            onSettingsClick = {},
        )
    }
}

@JellyScopeScreenPreviews
@Composable
private fun CollectionContentPreview() {
    JellyScopePreviewSurface {
        CollectionContent(
            session = PreviewFixtures.session,
            title = "Mind-Bending Sci-Fi",
            state = PreviewFixtures.collectionState,
            onBack = {},
            onRetry = {},
            onLoadMore = {},
            onItemSelected = {},
            onPlayItem = { _, _ -> },
            onSettingsClick = {},
        )
    }
}

@JellyScopeScreenPreviews
@Preview(
    name = "Settings four columns",
    group = "Desktop",
    widthDp = 1600,
    heightDp = 900,
    showBackground = true,
    backgroundColor = 0xFF081420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsContentPreview() {
    JellyScopePreviewSurface {
        SettingsContent(
            state = PreviewFixtures.settingsState,
            accountState = PreviewFixtures.accountState,
            onBack = {},
            onLogout = {},
            onReloadPlaybackPreferences = {},
            onSetDefaultMaxBitrateBps = {},
            onSetDefaultPlayerBackend = { _: PlayerBackend -> },
            onSetPreferredAudioLanguage = {},
            onSetPreferredSubtitleLanguage = {},
            onSetPlayerAudioMode = { _: PlayerAudioMode -> },
            onSetPlayerHdrMode = { _: PlayerHdrMode -> },
            onSetSegmentSkipPolicy = { _, _ -> },
            onRefreshPlayerDevicePolicy = {},
            onSetAppTheme = {},
            onSetTileSize = {},
            onSetPictureInPictureEnabled = {},
            onSetRememberLastLibraryView = {},
            onAddAccount = { _, _, _ -> },
            onSwitchAccount = {},
            onSignOutAccount = {},
        )
    }
}
