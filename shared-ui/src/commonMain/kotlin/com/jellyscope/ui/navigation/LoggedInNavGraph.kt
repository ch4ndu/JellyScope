// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.savedstate.read
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.ui.screen.collection.CollectionScreen
import com.jellyscope.ui.screen.detail.AdaptiveSeasonScreen
import com.jellyscope.ui.screen.detail.DetailScreen
import com.jellyscope.ui.screen.detail.SeriesScreen
import com.jellyscope.ui.screen.detail.SeriesViewModel
import com.jellyscope.ui.screen.discover.DiscoverScreen
import com.jellyscope.ui.screen.downloads.DownloadsScreen
import com.jellyscope.ui.screen.find.FindScreen
import com.jellyscope.ui.screen.grid.GridScreen
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.screen.home.HomeScreen
import com.jellyscope.ui.screen.library.LibraryScreen
import com.jellyscope.ui.screen.library.LibraryTabScreen
import com.jellyscope.ui.screen.person.PersonScreen
import com.jellyscope.ui.screen.player.PlayerScreen
import com.jellyscope.ui.screen.settings.SettingsScreen
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun LoggedInNavGraph(
    navController: NavHostController,
    session: Session,
    onLogoutComplete: () -> Unit,
    onSettingsClick: () -> Unit,
    loggedInContentInsets: WindowInsets,
    startSafeDrawingConsumed: Boolean,
    bottomBarContentPadding: Dp,
    playbackQueueHandoffStore: PlaybackQueueHandoffStore,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.Home,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.Home) {
            LoggedInScreenFrame(
                contentWindowInsets = loggedInContentInsets,
                startSafeDrawingConsumed = startSafeDrawingConsumed,
            ) {
                HomeScreen(
                    session = session,
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onViewAllSelected = { row -> navController.navigate(Routes.grid(row.name)) },
                    onPlayItem = { itemId, startTicks ->
                        navController.navigate(
                            Routes.player(
                                itemId = itemId,
                                startTicks = startTicks,
                                mediaSourceId = null,
                            ),
                        )
                    },
                    onSettingsClick = onSettingsClick,
                    bottomContentPadding = bottomBarContentPadding,
                )
            }
        }
        composable(Routes.Discover) {
            LoggedInScreenFrame(
                contentWindowInsets = loggedInContentInsets,
                startSafeDrawingConsumed = startSafeDrawingConsumed,
            ) {
                DiscoverScreen(
                    session = session,
                    onGenreSelected = { facet ->
                        navController.navigate(
                            Routes.filteredLibraryForGenre(
                                genre = facet.id ?: facet.name,
                                title = facet.name,
                            ),
                        )
                    },
                    onStudioSelected = { facet ->
                        navController.navigate(
                            Routes.filteredLibraryForStudio(
                                studioId = facet.id ?: facet.name,
                                title = facet.name,
                            ),
                        )
                    },
                    onCollectionSelected = { item ->
                        navController.navigate(Routes.collection(item.id, item.title))
                    },
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onSettingsClick = onSettingsClick,
                    bottomContentPadding = bottomBarContentPadding,
                )
            }
        }
        composable(Routes.LibraryRoot) {
            LoggedInScreenFrame(
                contentWindowInsets = loggedInContentInsets,
                startSafeDrawingConsumed = startSafeDrawingConsumed,
            ) {
                LibraryTabScreen(
                    session = session,
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onPlayItem = { itemId, startTicks ->
                        navController.navigate(
                            Routes.player(
                                itemId = itemId,
                                startTicks = startTicks,
                                mediaSourceId = null,
                            ),
                        )
                    },
                    onShuffleQueue = { itemIds ->
                        val key = playbackQueueHandoffStore.put(itemIds)
                        navController.navigate(
                            Routes.player(
                                itemId = itemIds.first(),
                                startTicks = 0L,
                                mediaSourceId = null,
                                queueKey = key,
                            ),
                        )
                    },
                    onSettingsClick = onSettingsClick,
                    bottomContentPadding = bottomBarContentPadding,
                )
            }
        }
        composable(Routes.Find) {
            LoggedInScreenFrame(
                contentWindowInsets = loggedInContentInsets,
                startSafeDrawingConsumed = startSafeDrawingConsumed,
            ) {
                FindScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onSettingsClick = onSettingsClick,
                    bottomContentPadding = bottomBarContentPadding,
                )
            }
        }
        composable(Routes.Downloads) {
            LoggedInScreenFrame(
                contentWindowInsets = loggedInContentInsets,
                startSafeDrawingConsumed = startSafeDrawingConsumed,
            ) {
                DownloadsScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onPlayOffline = { record ->
                        navController.navigate(
                            Routes.player(
                                itemId = record.businessKey.itemId,
                                startTicks = 0L,
                                mediaSourceId = record.businessKey.mediaSourceId,
                                offlineDownloadId = record.downloadId,
                            ),
                        )
                    },
                )
            }
        }
        composable(Routes.Settings) {
            LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                SettingsScreen(
                    session = session,
                    onBack = { navController.popBackStack() },
                    onLogoutComplete = onLogoutComplete,
                    onOpenDownloads = { navController.navigate(Routes.Downloads) },
                    modifier = Modifier,
                )
            }
        }
        composable(
            Routes.Detail,
            enterTransition = drillDownEnter,
            exitTransition = drillDownExit,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = drillDownPopExit,
        ) { entry ->
            val itemId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.ItemIdArgument) }
                    .orEmpty()
            LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                DetailScreen(
                    session = session,
                    itemId = itemId,
                    onBack = { navController.popBackStack() },
                    onPlayClick = { playItemId, startTicks, mediaSourceId, audioStreamIndex, subtitleSelection ->
                        navController.navigate(
                            Routes.player(
                                itemId = playItemId,
                                startTicks = startTicks,
                                mediaSourceId = mediaSourceId,
                                initialAudioStreamIndex = audioStreamIndex,
                                initialSubtitleSelection = subtitleSelection,
                            ),
                        )
                    },
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onPersonSelected = { personId -> navController.navigate(Routes.person(personId)) },
                    onPlayWithSubtitleIntent = { playItemId, startTicks, mediaSourceId, audioStreamIndex, subtitleIntent ->
                        navController.navigate(
                            Routes.player(
                                itemId = playItemId,
                                startTicks = startTicks,
                                mediaSourceId = mediaSourceId,
                                initialAudioStreamIndex = audioStreamIndex,
                                initialSubtitleSelection = subtitleIntent,
                            ),
                        )
                    },
                    onOpenDownloads = { navController.navigate(Routes.Downloads) },
                    onPlayOffline = { record ->
                        navController.navigate(
                            Routes.player(
                                itemId = record.businessKey.itemId,
                                startTicks = 0L,
                                mediaSourceId = record.businessKey.mediaSourceId,
                                offlineDownloadId = record.downloadId,
                            ),
                        )
                    },
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier,
                )
            }
        }
        navigation(
            route = Routes.SeriesGraph,
            startDestination = Routes.Series,
        ) {
            composable(
                Routes.Series,
                enterTransition = drillDownEnter,
                exitTransition = drillDownExit,
                popEnterTransition = { EnterTransition.None },
                popExitTransition = drillDownPopExit,
            ) { entry ->
                val seriesId =
                    entry.arguments
                        ?.read { getStringOrNull(Routes.SeriesIdArgument) }
                        .orEmpty()
                val graphRoute = Routes.seriesGraph(seriesId)
                val graphEntry =
                    remember(navController, graphRoute) {
                        navController.getBackStackEntry(graphRoute)
                    }
                val seriesViewModel: SeriesViewModel =
                    koinViewModel(
                        viewModelStoreOwner = graphEntry,
                        parameters = { parametersOf(session, seriesId) },
                    )
                LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                    SeriesScreen(
                        session = session,
                        seriesId = seriesId,
                        onBack = { navController.popBackStack() },
                        onPlayClick = { itemId, startTicks, mediaSourceId, audioStreamIndex, subtitleSelection ->
                            navController.navigate(
                                Routes.player(
                                    itemId = itemId,
                                    startTicks = startTicks,
                                    mediaSourceId = mediaSourceId,
                                    initialAudioStreamIndex = audioStreamIndex,
                                    initialSubtitleSelection = subtitleSelection,
                                ),
                            )
                        },
                        onPlayEpisode = { itemId, startTicks, mediaSourceId, audioStreamIndex, subtitleSelection, queue ->
                            navController.navigate(
                                Routes.player(
                                    itemId = itemId,
                                    startTicks = startTicks,
                                    mediaSourceId = mediaSourceId,
                                    initialAudioStreamIndex = audioStreamIndex,
                                    initialSubtitleSelection = subtitleSelection,
                                    queue = queue,
                                ),
                            )
                        },
                        onSeasonRouteSelected = { seasonId ->
                            navController.navigate(Routes.season(seriesId, seasonId))
                        },
                        onItemSelected = { item -> navController.navigateToMediaItem(item) },
                        onPersonSelected = { personId -> navController.navigate(Routes.person(personId)) },
                        onSettingsClick = onSettingsClick,
                        modifier = Modifier,
                        viewModel = seriesViewModel,
                    )
                }
            }
            composable(
                Routes.Season,
                enterTransition = drillDownEnter,
                exitTransition = drillDownExit,
                popEnterTransition = { EnterTransition.None },
                popExitTransition = drillDownPopExit,
            ) { entry ->
                val seriesId =
                    entry.arguments
                        ?.read { getStringOrNull(Routes.SeriesIdArgument) }
                        .orEmpty()
                val seasonId =
                    entry.arguments
                        ?.read { getStringOrNull(Routes.SeasonIdArgument) }
                        ?.let(Routes::decodeRouteValue)
                        .orEmpty()
                val graphRoute = Routes.seriesGraph(seriesId)
                val graphEntry =
                    remember(navController, graphRoute) {
                        navController.getBackStackEntry(graphRoute)
                    }
                val seriesViewModel: SeriesViewModel =
                    koinViewModel(
                        viewModelStoreOwner = graphEntry,
                        parameters = { parametersOf(session, seriesId) },
                    )
                LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                    AdaptiveSeasonScreen(
                        session = session,
                        seriesId = seriesId,
                        initialSeasonId = seasonId,
                        onBack = { navController.popBackStack() },
                        onPlay = { itemId, startTicks, mediaSourceId, audioStreamIndex, subtitleSelection, queue ->
                            navController.navigate(
                                Routes.player(
                                    itemId = itemId,
                                    startTicks = startTicks,
                                    mediaSourceId = mediaSourceId,
                                    initialAudioStreamIndex = audioStreamIndex,
                                    initialSubtitleSelection = subtitleSelection,
                                    queue = queue,
                                ),
                            )
                        },
                        onSeasonSelected = { selectedSeasonId ->
                            navController.navigate(Routes.season(seriesId, selectedSeasonId)) {
                                launchSingleTop = true
                            }
                        },
                        onPersonSelected = { personId -> navController.navigate(Routes.person(personId)) },
                        modifier = Modifier,
                        viewModel = seriesViewModel,
                    )
                }
            }
        }
        composable(
            Routes.Collection,
            enterTransition = drillDownEnter,
            exitTransition = drillDownExit,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = drillDownPopExit,
        ) { entry ->
            val collectionId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.CollectionIdArgument) }
                    ?.let(Routes::decodeRouteValue)
                    .orEmpty()
            val title =
                entry.arguments
                    ?.read { getStringOrNull(Routes.TitleArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.takeIf { value -> value.isNotBlank() }
            LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                CollectionScreen(
                    session = session,
                    collectionId = collectionId,
                    title = title,
                    onBack = { navController.popBackStack() },
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onPlayItem = { itemId, startTicks ->
                        navController.navigate(
                            Routes.player(
                                itemId = itemId,
                                startTicks = startTicks,
                                mediaSourceId = null,
                            ),
                        )
                    },
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier,
                )
            }
        }
        composable(
            Routes.Person,
            enterTransition = drillDownEnter,
            exitTransition = drillDownExit,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = drillDownPopExit,
        ) { entry ->
            val personId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.PersonIdArgument) }
                    ?.let(Routes::decodeRouteValue)
                    .orEmpty()
            LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                PersonScreen(
                    session = session,
                    personId = personId,
                    onBack = { navController.popBackStack() },
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onPlayItem = { itemId, startTicks ->
                        navController.navigate(
                            Routes.player(
                                itemId = itemId,
                                startTicks = startTicks,
                                mediaSourceId = null,
                            ),
                        )
                    },
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier,
                )
            }
        }
        composable(Routes.FilteredLibrary) { entry ->
            val title =
                entry.arguments
                    ?.read { getStringOrNull(Routes.TitleArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.takeIf { value -> value.isNotBlank() }
            val genre =
                entry.arguments
                    ?.read { getStringOrNull(Routes.GenreArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.takeIf { value -> value.isNotBlank() }
            val studioId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.StudioIdArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.takeIf { value -> value.isNotBlank() }
            val parentId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.ParentIdArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.takeIf { value -> value.isNotBlank() }
            val collectionType =
                entry.arguments
                    ?.read { getStringOrNull(Routes.LibraryTypeArgument) }
                    ?.let { value -> LibraryCollectionType.entries.firstOrNull { type -> type.name == value } }
                    ?: LibraryCollectionType.Other
            val initialFilters =
                LibraryFilterSelection(
                    genreIds = genre?.let(::listOf).orEmpty(),
                    studioIds = studioId?.let(::listOf).orEmpty(),
                )
            LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                LibraryScreen(
                    session = session,
                    parentId = parentId.orEmpty(),
                    title = title,
                    onBack = { navController.popBackStack() },
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onPlayItem = { itemId, startTicks ->
                        navController.navigate(
                            Routes.player(
                                itemId = itemId,
                                startTicks = startTicks,
                                mediaSourceId = null,
                            ),
                        )
                    },
                    onShuffleQueue = {},
                    collectionType = collectionType,
                    initialFilters = initialFilters,
                    initialFilterLabel = title,
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier,
                )
            }
        }
        composable(Routes.Library) { entry ->
            val parentId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.ParentIdArgument) }
                    .orEmpty()
            val title =
                entry.arguments
                    ?.read { getStringOrNull(Routes.TitleArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.takeIf { value -> value.isNotBlank() }
            val collectionType =
                entry.arguments
                    ?.read { getStringOrNull(Routes.LibraryTypeArgument) }
                    ?.let { value -> LibraryCollectionType.entries.firstOrNull { type -> type.name == value } }
                    ?: LibraryCollectionType.Other
            LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                LibraryScreen(
                    session = session,
                    parentId = parentId,
                    title = title,
                    onBack = { navController.popBackStack() },
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onPlayItem = { itemId, startTicks ->
                        navController.navigate(
                            Routes.player(
                                itemId = itemId,
                                startTicks = startTicks,
                                mediaSourceId = null,
                            ),
                        )
                    },
                    onShuffleQueue = { itemIds ->
                        val key = playbackQueueHandoffStore.put(itemIds)
                        navController.navigate(
                            Routes.player(
                                itemId = itemIds.first(),
                                startTicks = 0L,
                                mediaSourceId = null,
                                queueKey = key,
                            ),
                        )
                    },
                    collectionType = collectionType,
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier,
                )
            }
        }
        composable(
            Routes.Grid,
            enterTransition = drillDownEnter,
            exitTransition = drillDownExit,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = drillDownPopExit,
        ) { entry ->
            val row =
                entry.arguments
                    ?.read { getStringOrNull(Routes.RowArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.let { rowName -> HomeRow.entries.firstOrNull { row -> row.name == rowName } }
                    ?: HomeRow.RecentlyAdded
            LoggedInScreenFrame(contentWindowInsets = loggedInContentInsets) {
                GridScreen(
                    session = session,
                    row = row,
                    onBack = { navController.popBackStack() },
                    onItemSelected = { item -> navController.navigateToMediaItem(item) },
                    onPlayItem = { itemId, startTicks ->
                        navController.navigate(
                            Routes.player(
                                itemId = itemId,
                                startTicks = startTicks,
                                mediaSourceId = null,
                            ),
                        )
                    },
                    onShuffleQueue = { queue ->
                        queue.firstOrNull()?.let { firstItemId ->
                            navController.navigate(
                                Routes.player(
                                    itemId = firstItemId,
                                    startTicks = 0L,
                                    mediaSourceId = null,
                                    queue = queue,
                                ),
                            )
                        }
                    },
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier,
                )
            }
        }
        composable(
            Routes.Player,
            enterTransition = drillDownEnter,
            exitTransition = drillDownExit,
            popEnterTransition = { EnterTransition.None },
            popExitTransition = drillDownPopExit,
        ) { entry ->
            val itemId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.ItemIdArgument) }
                    .orEmpty()
            val startTicks =
                entry.arguments
                    ?.read { getStringOrNull(Routes.StartTicksArgument) }
                    ?.toLongOrNull()
                    ?: 0L
            val mediaSourceId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.MediaSourceIdArgument) }
                    ?.takeIf { id -> id.isNotBlank() }
            val initialAudioStreamIndex =
                entry.arguments
                    ?.read { getStringOrNull(Routes.AudioStreamIndexArgument) }
                    ?.toIntOrNull()
            val initialSubtitleSelection =
                entry.arguments
                    ?.read { getStringOrNull(Routes.SubtitleAssetIdArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.takeIf(String::isNotBlank)
                    ?.let(SubtitleSelectionIntent::LocalAsset)
                    ?: entry.arguments
                        ?.read { getStringOrNull(Routes.SubtitleStreamIndexArgument) }
                        ?.toIntOrNull()
                        .let(SubtitleSelectionIntent::fromWireIndex)
            val queue =
                entry.arguments
                    ?.read { getStringOrNull(Routes.QueueArgument) }
                    ?.takeIf { value -> value.isNotBlank() }
                    ?.split(",")
                    ?.map(Routes::decodeRouteValue)
                    .orEmpty()
            val queueKey =
                entry.arguments
                    ?.read { getStringOrNull(Routes.QueueKeyArgument) }
                    ?.let(Routes::decodeRouteValue)
                    ?.takeIf { value -> value.isNotBlank() }
            val offlineDownloadId =
                entry.arguments
                    ?.read { getStringOrNull(Routes.OfflineDownloadIdArgument) }
                    ?.let(Routes::offlineDownloadId)
            val handoffQueue = remember(entry, queueKey) { playbackQueueHandoffStore.take(queueKey) }
            val playbackQueue =
                if (queueKey != null) {
                    handoffQueue.ifEmpty { listOf(itemId) }
                } else {
                    queue
                }
            PlayerScreen(
                session = session,
                itemId = itemId,
                startPositionTicks = startTicks,
                mediaSourceId = mediaSourceId,
                initialAudioStreamIndex = initialAudioStreamIndex,
                initialSubtitleSelection = initialSubtitleSelection,
                queue = playbackQueue,
                offlineDownloadId = offlineDownloadId,
                onBack = { navController.popBackStack() },
                onOpenPlaybackSettings = {
                    navController.popBackStack()
                    navController.navigate(Routes.Settings)
                },
            )
        }
    }
}
