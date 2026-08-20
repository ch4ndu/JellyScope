// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.model.accountIdentity
import com.jellyscope.core.domain.usecase.ObserveTileSizeUseCase
import com.jellyscope.ui.adaptive.LocalTileScale
import com.jellyscope.ui.adaptive.LocalWindowWidthTier
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.adaptive.tileScaleFor
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.library_tab_cd
import com.jellyscope.ui.generated.resources.library_tab_label
import com.jellyscope.ui.generated.resources.nav_discover
import com.jellyscope.ui.generated.resources.nav_discover_cd
import com.jellyscope.ui.generated.resources.nav_downloads
import com.jellyscope.ui.generated.resources.nav_downloads_cd
import com.jellyscope.ui.generated.resources.nav_find
import com.jellyscope.ui.generated.resources.nav_find_cd
import com.jellyscope.ui.generated.resources.nav_home
import com.jellyscope.ui.generated.resources.nav_home_cd
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject

data class TopLevelNavItem(
    val route: String,
    val label: StringResource,
    val contentDescription: StringResource,
    val icon: TopLevelNavIcon,
)

enum class TopLevelNavIcon {
    Home,
    Discover,
    Library,
    Find,
    Downloads,
}

val TopLevelNavItems =
    listOf(
        TopLevelNavItem(
            route = Routes.Home,
            label = Res.string.nav_home,
            contentDescription = Res.string.nav_home_cd,
            icon = TopLevelNavIcon.Home,
        ),
        TopLevelNavItem(
            route = Routes.LibraryRoot,
            label = Res.string.library_tab_label,
            contentDescription = Res.string.library_tab_cd,
            icon = TopLevelNavIcon.Library,
        ),
        TopLevelNavItem(
            route = Routes.Discover,
            label = Res.string.nav_discover,
            contentDescription = Res.string.nav_discover_cd,
            icon = TopLevelNavIcon.Discover,
        ),
        TopLevelNavItem(
            route = Routes.Find,
            label = Res.string.nav_find,
            contentDescription = Res.string.nav_find_cd,
            icon = TopLevelNavIcon.Find,
        ),
        TopLevelNavItem(
            route = Routes.Downloads,
            label = Res.string.nav_downloads,
            contentDescription = Res.string.nav_downloads_cd,
            icon = TopLevelNavIcon.Downloads,
        ),
    )

internal fun topLevelNavItems(enableContentDownloading: Boolean): List<TopLevelNavItem> =
    TopLevelNavItems.filter { item ->
        item.icon != TopLevelNavIcon.Downloads || enableContentDownloading
    }

@Composable
fun LoggedInNavHost(
    session: Session,
    boundaryEpoch: Long,
    onLogoutComplete: () -> Unit,
    modifier: Modifier = Modifier,
    initialPlaybackItemId: String? = null,
    initialDetailItemId: String? = null,
    initialDetailEvent: InitialDetailNavigationEvent? = null,
    onInitialDetailEventConsumed: (Long) -> Unit = {},
) {
    val observeTileSizeUseCase = koinInject<ObserveTileSizeUseCase>()
    val tileSize by observeTileSizeUseCase().collectAsStateWithLifecycle()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val windowWidthTier = WindowWidthTier.fromAvailableWidth(maxWidth)
        CompositionLocalProvider(
            LocalWindowWidthTier provides windowWidthTier,
            LocalTileScale provides tileScaleFor(windowWidthTier, tileSize),
        ) {
            LoggedInNavHostContent(
                session = session,
                boundaryEpoch = boundaryEpoch,
                onLogoutComplete = onLogoutComplete,
                initialPlaybackItemId = initialPlaybackItemId,
                initialDetailItemId = initialDetailItemId,
                initialDetailEvent = initialDetailEvent,
                onInitialDetailEventConsumed = onInitialDetailEventConsumed,
            )
        }
    }
}

@Composable
private fun LoggedInNavHostContent(
    session: Session,
    boundaryEpoch: Long,
    onLogoutComplete: () -> Unit,
    initialPlaybackItemId: String?,
    initialDetailItemId: String?,
    initialDetailEvent: InitialDetailNavigationEvent?,
    onInitialDetailEventConsumed: (Long) -> Unit,
) {
    val navController = rememberNavController()
    LaunchedEffect(initialDetailEvent, session.accountIdentity(), boundaryEpoch) {
        val event = initialDetailEvent ?: return@LaunchedEffect
        if (event.isEligibleFor(session.accountIdentity(), boundaryEpoch)) {
            navController.navigate(Routes.detail(event.itemId))
        }
        onInitialDetailEventConsumed(event.eventId)
    }
    LaunchedEffect(initialDetailItemId, initialPlaybackItemId, session.accountIdentity(), boundaryEpoch) {
        if (initialDetailEvent != null) return@LaunchedEffect
        resolveInitialLoggedInRoute(
            initialDetailItemId = initialDetailItemId,
            initialPlaybackItemId = initialPlaybackItemId,
        )?.let(navController::navigate)
    }
    val playbackQueueHandoffStore = remember(session.serverId, session.userId, boundaryEpoch) { PlaybackQueueHandoffStore() }
    DisposableEffect(playbackQueueHandoffStore) {
        onDispose { playbackQueueHandoffStore.clear() }
    }
    val currentRoute =
        navController
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route
    val visibleTopLevelNavItems =
        remember(session.enableContentDownloading) {
            topLevelNavItems(session.enableContentDownloading)
        }
    val topLevelRoutes = remember(visibleTopLevelNavItems) { visibleTopLevelNavItems.mapTo(HashSet()) { item -> item.route } }
    val windowWidthTier = LocalWindowWidthTier.current
    val showTopLevelChrome = currentRoute != null && currentRoute in topLevelRoutes
    val showBottomBar = showTopLevelChrome && windowWidthTier == WindowWidthTier.Compact
    val showAdaptiveRail = showTopLevelChrome && windowWidthTier != WindowWidthTier.Compact
    var adaptiveNavRailExpanded by rememberSaveable(session.serverId, session.userId, boundaryEpoch) {
        mutableStateOf(false)
    }
    val adaptiveNavRailTargetWidth =
        if (adaptiveNavRailExpanded) {
            Dimensions.adaptiveNavRailExpandedWidth
        } else {
            Dimensions.adaptiveNavRailCollapsedWidth
        }
    val adaptiveNavRailWidth =
        animateDpAsState(
            targetValue = adaptiveNavRailTargetWidth,
            label = "adaptive-nav-rail-width",
        )
    val currentRailWidth =
        remember(showAdaptiveRail, adaptiveNavRailWidth) {
            {
                if (showAdaptiveRail) {
                    adaptiveNavRailWidth.value
                } else {
                    Dimensions.zero
                }
            }
        }
    val loggedInContentInsets = WindowInsets(0, 0, 0, 0)
    val bottomBarContentPadding =
        if (showBottomBar) {
            Dimensions.bottomNavigationContentPadding +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        } else {
            Dimensions.zero
        }
    val onSettingsClick = {
        navController.navigate(Routes.Settings) {
            launchSingleTop = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AdaptiveRailScaffold(
            railWidth = currentRailWidth,
            rail = {
                if (showAdaptiveRail) {
                    AdaptiveNavRail(
                        items = visibleTopLevelNavItems,
                        selectedRoute = selectedTopLevelRoute(currentRoute),
                        expanded = adaptiveNavRailExpanded,
                        onExpandedChange = { expanded -> adaptiveNavRailExpanded = expanded },
                        onItemSelected = { item -> navController.navigateTopLevel(item.route) },
                    )
                }
            },
        ) {
            LoggedInNavGraph(
                navController = navController,
                session = session,
                onLogoutComplete = onLogoutComplete,
                onSettingsClick = onSettingsClick,
                loggedInContentInsets = loggedInContentInsets,
                startSafeDrawingConsumed = showAdaptiveRail,
                bottomBarContentPadding = bottomBarContentPadding,
                playbackQueueHandoffStore = playbackQueueHandoffStore,
            )
        }
        if (showBottomBar) {
            LoggedInBottomBar(
                currentRoute = currentRoute,
                items = visibleTopLevelNavItems,
                onItemSelected = { item -> navController.navigateTopLevel(item.route) },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
            )
        }
    }
}

internal fun resolveInitialLoggedInRoute(
    initialDetailItemId: String?,
    initialPlaybackItemId: String?,
): String? {
    initialDetailItemId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(Routes::detail)
        ?.let { route -> return route }
    return initialPlaybackItemId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { itemId ->
            Routes.player(
                itemId = itemId,
                startTicks = 0L,
                mediaSourceId = null,
            )
        }
}

@Composable
private fun AdaptiveRailScaffold(
    railWidth: () -> Dp,
    rail: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
            Box(modifier = Modifier.fillMaxHeight()) {
                rail()
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val layoutWidth = constraints.maxWidth
        val layoutHeight = constraints.maxHeight
        val railWidthPx = railWidth().roundToPx().coerceIn(0, layoutWidth)
        val contentWidth = layoutWidth - railWidthPx
        val contentPlaceable =
            measurables[0].measure(
                Constraints.fixed(contentWidth, layoutHeight),
            )
        val railPlaceable =
            measurables[1].measure(
                Constraints.fixed(railWidthPx, layoutHeight),
            )

        layout(layoutWidth, layoutHeight) {
            contentPlaceable.placeRelative(railWidthPx, 0)
            railPlaceable.placeRelative(0, 0)
        }
    }
}
