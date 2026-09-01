// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.rememberDrawerState
import com.jellyscope.core.domain.model.DownloadId
import com.jellyscope.core.domain.model.Library
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryItemFilter
import com.jellyscope.core.domain.model.Session
import com.jellyscope.core.domain.playback.SubtitleSelectionIntent
import com.jellyscope.core.domain.usecase.GetUserLibrariesUseCase
import com.jellyscope.tv.ui.LocalTvHostedRailController
import com.jellyscope.tv.ui.TvCollectionScreen
import com.jellyscope.tv.ui.TvDiscoverScreen
import com.jellyscope.tv.ui.TvDownloadsScreen
import com.jellyscope.tv.ui.TvFindScreen
import com.jellyscope.tv.ui.TvGridScreen
import com.jellyscope.tv.ui.TvHeroAmbientPresentation
import com.jellyscope.tv.ui.TvHomeScreen
import com.jellyscope.tv.ui.TvHostedRailController
import com.jellyscope.tv.ui.TvLibraryScreen
import com.jellyscope.tv.ui.TvNavigationDrawer
import com.jellyscope.tv.ui.TvPersonScreen
import com.jellyscope.tv.ui.TvPlayerScreen
import com.jellyscope.tv.ui.TvRailDestination
import com.jellyscope.tv.ui.TvRailTarget
import com.jellyscope.tv.ui.TvRouteScope
import com.jellyscope.tv.ui.TvSettingsBackdrop
import com.jellyscope.tv.ui.TvSettingsScreen
import com.jellyscope.tv.ui.focus.LocalTvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusMemoryState
import com.jellyscope.tv.ui.focus.rememberTvFocusCoordinator
import com.jellyscope.tv.ui.toRailTarget
import com.jellyscope.tv.ui.tvHomeAmbientBackground
import com.jellyscope.ui.component.MediaCardKind
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.screen.detail.DetailViewModel
import com.jellyscope.ui.screen.detail.LocalDetailFocusBridge
import com.jellyscope.ui.screen.detail.SeriesViewModel
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.discover.DiscoverViewModel
import com.jellyscope.ui.screen.downloads.DownloadsViewModel
import com.jellyscope.ui.screen.find.FindViewModel
import com.jellyscope.ui.screen.grid.GridViewModel
import com.jellyscope.ui.screen.home.HomeRow
import com.jellyscope.ui.screen.home.HomeViewModel
import com.jellyscope.ui.screen.library.LibraryBrowseViewModel
import com.jellyscope.ui.screen.library.LibraryHubViewModel
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import kotlinx.coroutines.delay
import org.koin.android.ext.android.get
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun TvLoggedInApp(
    session: Session,
    pendingWatchNextItemId: String?,
    onWatchNextItemHandled: () -> Unit,
    onPlaybackStopped: () -> Unit,
) {
    val getUserLibrariesUseCase = koinInject<GetUserLibrariesUseCase>()
    val focusCoordinator = rememberTvFocusCoordinator()
    val topLevelViewModelStoreOwner =
        remember(session.serverId, session.userId) { TvTopLevelViewModelStoreOwner() }
    val detailEntryStore =
        remember(session.serverId, session.userId) { TvDetailEntryStore() }
    val homeStateHolder = rememberSaveableStateHolder()
    val gridStateHolder = rememberSaveableStateHolder()
    val libraryStateHolder = rememberSaveableStateHolder()
    val findStateHolder = rememberSaveableStateHolder()
    val discoverStateHolder = rememberSaveableStateHolder()
    val downloadsStateHolder = rememberSaveableStateHolder()
    var route by rememberSaveable { mutableStateOf(TvRoute.Home.name) }
    var routeHistory by rememberSaveable { mutableStateOf(TvRouteHistory()) }
    var drawerAmbientPresentation by remember { mutableStateOf(TvHeroAmbientPresentation.Empty) }
    var detailItemId by rememberSaveable { mutableStateOf("") }
    var collectionItemId by rememberSaveable { mutableStateOf("") }
    var collectionTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionOriginLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var personItemId by rememberSaveable { mutableStateOf("") }
    var seriesItemId by rememberSaveable { mutableStateOf("") }
    val seriesSeasonViewModelStoreOwner =
        remember(session.serverId, session.userId, seriesItemId) {
            TvSeriesSeasonViewModelStoreOwner()
        }
    var seasonItemId by rememberSaveable { mutableStateOf("") }
    var gridRow by rememberSaveable { mutableStateOf(HomeRow.ContinueWatching.name) }
    var libraryParentId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryCollectionType by rememberSaveable { mutableStateOf(LibraryCollectionType.Other.name) }
    var libraryFilterGenreId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryFilterStudioId by rememberSaveable { mutableStateOf<String?>(null) }
    var playerItemId by rememberSaveable { mutableStateOf("") }
    var playerMediaSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var playerInitialAudioStreamIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var playerInitialSubtitleStreamIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var playerInitialSubtitleAssetId by rememberSaveable { mutableStateOf<String?>(null) }
    var playerOfflineDownloadId by rememberSaveable { mutableStateOf<String?>(null) }
    var playerStartTicks by rememberSaveable { mutableLongStateOf(0L) }
    var playerRouteKey by rememberSaveable { mutableIntStateOf(0) }
    var playerQueueIds by rememberSaveable(stateSaver = stringListSaver()) {
        mutableStateOf(emptyList<String>())
    }
    var navigationLibraries by remember(session.serverId, session.userId) { mutableStateOf(emptyList<Library>()) }
    val retainSeriesSeasonViewModel =
        shouldRetainTvSeriesSeasonViewModel(
            route = route,
            history = routeHistory,
        )

    SideEffect {
        detailEntryStore.updateRetainedEntries(
            retainedDetailEntryIds(
                route = route,
                activeEntryId = focusCoordinator.activeRouteEntryId,
                history = routeHistory,
            ),
        )
    }

    DisposableEffect(topLevelViewModelStoreOwner) {
        onDispose { topLevelViewModelStoreOwner.viewModelStore.clear() }
    }

    DisposableEffect(detailEntryStore) {
        onDispose { detailEntryStore.clear() }
    }

    DisposableEffect(seriesSeasonViewModelStoreOwner) {
        onDispose { seriesSeasonViewModelStoreOwner.viewModelStore.clear() }
    }

    LaunchedEffect(
        retainSeriesSeasonViewModel,
        seriesSeasonViewModelStoreOwner,
    ) {
        if (!retainSeriesSeasonViewModel) {
            delay(TV_NAV_TRANSITION_MS.toLong())
            seriesSeasonViewModelStoreOwner.viewModelStore.clear()
        }
    }

    LaunchedEffect(session.serverId, session.userId) {
        navigationLibraries = getUserLibrariesUseCase().getOrElse { emptyList() }
    }

    fun currentRouteSnapshot(): TvRouteSnapshot =
        TvRouteSnapshot(
            route = route,
            routeEntryId = focusCoordinator.activeRouteEntryId,
            focusPath = focusCoordinator.activePath,
            focusMemoryEntries = focusCoordinator.activeMemory.entries,
            detailItemId = detailItemId,
            collectionItemId = collectionItemId,
            collectionTitle = collectionTitle,
            collectionOriginLibraryId = collectionOriginLibraryId,
            personItemId = personItemId,
            seriesItemId = seriesItemId,
            seasonItemId = seasonItemId,
            gridRow = gridRow,
            libraryParentId = libraryParentId,
            libraryTitle = libraryTitle,
            libraryCollectionType = libraryCollectionType,
            libraryFilterGenreId = libraryFilterGenreId,
            libraryFilterStudioId = libraryFilterStudioId,
            playerItemId = playerItemId,
            playerMediaSourceId = playerMediaSourceId,
            playerInitialAudioStreamIndex = playerInitialAudioStreamIndex,
            playerInitialSubtitleStreamIndex = playerInitialSubtitleStreamIndex,
            playerInitialSubtitleAssetId = playerInitialSubtitleAssetId,
            playerOfflineDownloadId = playerOfflineDownloadId,
            playerStartTicks = playerStartTicks,
            playerRouteKey = playerRouteKey,
            playerQueueIds = playerQueueIds,
        )

    fun restoreRoute(snapshot: TvRouteSnapshot) {
        detailItemId = snapshot.detailItemId
        collectionItemId = snapshot.collectionItemId
        collectionTitle = snapshot.collectionTitle
        collectionOriginLibraryId = snapshot.collectionOriginLibraryId
        personItemId = snapshot.personItemId
        seriesItemId = snapshot.seriesItemId
        seasonItemId = snapshot.seasonItemId
        gridRow = snapshot.gridRow
        libraryParentId = snapshot.libraryParentId
        libraryTitle = snapshot.libraryTitle
        libraryCollectionType = snapshot.libraryCollectionType
        libraryFilterGenreId = snapshot.libraryFilterGenreId
        libraryFilterStudioId = snapshot.libraryFilterStudioId
        playerItemId = snapshot.playerItemId
        playerMediaSourceId = snapshot.playerMediaSourceId
        playerInitialAudioStreamIndex = snapshot.playerInitialAudioStreamIndex
        playerInitialSubtitleStreamIndex = snapshot.playerInitialSubtitleStreamIndex
        playerInitialSubtitleAssetId = snapshot.playerInitialSubtitleAssetId
        playerOfflineDownloadId = snapshot.playerOfflineDownloadId
        playerStartTicks = snapshot.playerStartTicks
        playerRouteKey = snapshot.playerRouteKey
        playerQueueIds = snapshot.playerQueueIds
        route = snapshot.route
    }

    fun popRoute(fallback: TvRoute = TvRoute.Home) {
        val popped = routeHistory.pop()
        if (popped == null) {
            routeHistory = routeHistory.clear()
            focusCoordinator.resetTopLevel()
            playerOfflineDownloadId = null
            route = fallback.name
            return
        }
        routeHistory = popped.history
        focusCoordinator.restoreParent(
            routeEntryId = popped.snapshot.routeEntryId,
            path = popped.snapshot.focusPath,
            memory = TvFocusMemoryState(popped.snapshot.focusMemoryEntries),
        )
        restoreRoute(popped.snapshot)
    }

    fun resetRoute(target: TvRoute) {
        routeHistory = routeHistory.clear()
        focusCoordinator.resetTopLevel()
        playerOfflineDownloadId = null
        route = target.name
    }

    fun pushCurrentRoute() {
        routeHistory = routeHistory.push(currentRouteSnapshot())
        focusCoordinator.allocateChildEntry()
    }

    fun openPlayer(
        itemId: String,
        startTicks: Long,
        mediaSourceId: String?,
        initialAudioStreamIndex: Int? = null,
        initialSubtitleSelection: SubtitleSelectionIntent = SubtitleSelectionIntent.Unspecified,
        queue: List<String> = emptyList(),
        offlineDownloadId: DownloadId? = null,
    ) {
        pushCurrentRoute()
        playerItemId = itemId
        playerStartTicks = startTicks
        playerMediaSourceId = mediaSourceId
        playerInitialAudioStreamIndex = initialAudioStreamIndex
        playerOfflineDownloadId = offlineDownloadId?.value
        playerInitialSubtitleAssetId = (initialSubtitleSelection as? SubtitleSelectionIntent.LocalAsset)?.assetId
        playerInitialSubtitleStreamIndex =
            initialSubtitleSelection
                .takeUnless { it is SubtitleSelectionIntent.LocalAsset }
                ?.wireIndexOrNull()
        playerQueueIds = queue
        playerRouteKey += 1
        route = TvRoute.Player.name
    }

    var contentAutofocusSuppressed by remember { mutableStateOf(false) }

    fun selectTopLevelRoute(
        target: TvRoute,
        origin: TvRailSelectionOrigin = TvRailSelectionOrigin.Content,
    ) {
        if (target == TvRoute.Downloads && !isTvDownloadsVisible(session)) {
            return
        }
        val destinationChanged = route != target.name
        if (destinationChanged) {
            if (origin == TvRailSelectionOrigin.Content) {
                contentAutofocusSuppressed = false
            } else {
                contentAutofocusSuppressed = true
            }
            // Tab switches reset focus and scroll; drill-down BACK restores them.
            when (target) {
                TvRoute.Home -> {
                    homeStateHolder.removeState("home")
                }
                TvRoute.Discover -> discoverStateHolder.removeState("discover")
                TvRoute.Favorites -> libraryStateHolder.removeState("favorites")
                TvRoute.Find -> findStateHolder.removeState("find")
                TvRoute.Downloads -> downloadsStateHolder.removeState("downloads")
                else -> Unit
            }
        } else if (origin == TvRailSelectionOrigin.Rail) {
            contentAutofocusSuppressed = true
        } else {
            contentAutofocusSuppressed = false
        }
        if (destinationChanged) {
            resetRoute(target)
        } else {
            routeHistory = routeHistory.clear()
            route = target.name
        }
    }

    LaunchedEffect(session.enableContentDownloading, route) {
        if (!isTvDownloadsVisible(session) && route == TvRoute.Downloads.name) {
            resetRoute(TvRoute.Home)
        }
    }

    // Remote PLAY starts the focused item at its resume point.
    fun playTvItemDirect(item: MediaCardUi) {
        val playable =
            item.kind == MediaCardKind.Movie ||
                item.kind == MediaCardKind.Episode ||
                item.kind == MediaCardKind.Other
        if (playable && item.supported) {
            openPlayer(
                itemId = item.id,
                startTicks = item.resumePositionTicks ?: 0L,
                mediaSourceId = null,
            )
        }
    }

    fun openTvItem(
        itemId: String,
        kind: MediaCardKind,
        title: String? = null,
        collectionType: LibraryCollectionType = LibraryCollectionType.Other,
    ) {
        when (kind) {
            MediaCardKind.Library -> {
                libraryParentId = itemId
                libraryTitle = title
                libraryCollectionType = collectionType.name
                libraryFilterGenreId = null
                libraryFilterStudioId = null
                resetRoute(TvRoute.Library)
            }
            MediaCardKind.Series -> {
                pushCurrentRoute()
                seriesItemId = itemId
                route = TvRoute.Series.name
            }
            MediaCardKind.Movie,
            MediaCardKind.Episode,
            MediaCardKind.Other,
            -> {
                pushCurrentRoute()
                detailItemId = itemId
                route = TvRoute.Detail.name
            }
        }
    }

    fun openTvItem(item: MediaCardUi) {
        openTvItem(
            itemId = item.id,
            kind = item.kind,
            title = item.title,
            collectionType = item.libraryCollectionType ?: LibraryCollectionType.Other,
        )
    }

    fun openNavigationLibrary(
        library: Library,
        origin: TvRailSelectionOrigin = TvRailSelectionOrigin.Content,
    ) {
        if (route != TvRoute.Library.name || libraryParentId != library.id) {
            if (origin == TvRailSelectionOrigin.Content) {
                contentAutofocusSuppressed = false
            } else {
                contentAutofocusSuppressed = true
            }
        } else if (origin == TvRailSelectionOrigin.Rail) {
            contentAutofocusSuppressed = true
        } else {
            contentAutofocusSuppressed = false
        }
        libraryParentId = library.id
        libraryTitle = library.name
        libraryCollectionType = library.collectionType.name
        libraryFilterGenreId = null
        libraryFilterStudioId = null
        resetRoute(TvRoute.Library)
    }

    LaunchedEffect(pendingWatchNextItemId) {
        val itemId = pendingWatchNextItemId?.takeIf { id -> id.isNotBlank() } ?: return@LaunchedEffect
        openTvItem(itemId, MediaCardKind.Other)
        onWatchNextItemHandled()
    }

    fun openCollection(
        item: MediaCardUi,
        originLibraryId: String? = null,
    ) {
        pushCurrentRoute()
        collectionItemId = item.id
        collectionTitle = item.title
        collectionOriginLibraryId = originLibraryId
        route = TvRoute.Collection.name
    }

    fun openPerson(itemId: String) {
        pushCurrentRoute()
        personItemId = itemId
        route = TvRoute.Person.name
    }

    fun openFilteredLibrary(
        title: String,
        genreId: String? = null,
        studioId: String? = null,
        originLibraryId: String? = null,
        originCollectionType: LibraryCollectionType = LibraryCollectionType.Other,
    ) {
        pushCurrentRoute()
        libraryParentId = originLibraryId
        libraryTitle = title
        libraryCollectionType = originCollectionType.name
        libraryFilterGenreId = genreId
        libraryFilterStudioId = studioId
        route = TvRoute.FilteredLibrary.name
    }

    fun openDetailRelatedItem(item: MediaCardUi) {
        when (item.kind) {
            MediaCardKind.Movie,
            MediaCardKind.Episode,
            MediaCardKind.Other,
            -> {
                pushCurrentRoute()
                detailItemId = item.id
            }
            else -> openTvItem(item)
        }
    }

    val railEnabled = isTvRailRoute(route, isTvDownloadsVisible(session))
    val selectedRailDestination = selectedTvRailDestination(route, isTvDownloadsVisible(session))
    val selectedRailLibraryId = libraryParentId.takeIf { route == TvRoute.Library.name }
    val selectedRailTarget =
        selectedRailLibraryId
            ?.let { libraryId -> TvRailTarget.Library(libraryId) }
            ?: selectedRailDestination?.toRailTarget()
    val selectedRailLibraryLoaded =
        selectedRailLibraryId != null && navigationLibraries.any { library -> library.id == selectedRailLibraryId }
    val railFocusFallbackDestination =
        TvRailDestination.Home.takeIf {
            route == TvRoute.Library.name && !selectedRailLibraryLoaded
        }
    val railSelectedTabRequester = remember { FocusRequester() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var railHasFocus by remember { mutableStateOf(false) }
    val activeContentRegistrationKey =
        route.takeIf { isTvRailRoute(it, isTvDownloadsVisible(session)) }.orEmpty()
    var contentRightFocusRegistrationKey by remember { mutableStateOf("") }
    var contentRightFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var contentRightFocusActionRegistrationKey by remember { mutableStateOf("") }
    var contentRightFocusAction by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val activeContentRightFocusRequester =
        contentRightFocusRequester.takeIf {
            contentRightFocusRegistrationKey == activeContentRegistrationKey
        }
    val activeContentRightFocusAction =
        contentRightFocusAction.takeIf {
            contentRightFocusActionRegistrationKey == activeContentRegistrationKey
        }

    LaunchedEffect(railEnabled) {
        if (!railEnabled) {
            railHasFocus = false
            drawerState.setValue(DrawerValue.Closed)
            contentRightFocusRegistrationKey = ""
            contentRightFocusRequester = null
            contentRightFocusActionRegistrationKey = ""
            contentRightFocusAction = null
            contentAutofocusSuppressed = false
        }
    }

    fun requestRailFocus(): Boolean {
        if (!railEnabled) {
            return false
        }
        drawerState.setValue(DrawerValue.Open)
        return railSelectedTabRequester.requestFocusSafely()
    }

    fun selectRailTarget(target: TvRailTarget) {
        if (target == TvRailTarget.Downloads && !isTvDownloadsVisible(session)) {
            return
        }
        val focusOnlyFallback =
            route == TvRoute.Library.name &&
                !selectedRailLibraryLoaded &&
                target == TvRailTarget.Home
        if (target == selectedRailTarget) {
            return
        }
        if (focusOnlyFallback) {
            return
        }
        when (target) {
            TvRailTarget.Find -> selectTopLevelRoute(TvRoute.Find, TvRailSelectionOrigin.Rail)
            TvRailTarget.Discover -> selectTopLevelRoute(TvRoute.Discover, TvRailSelectionOrigin.Rail)
            TvRailTarget.Favorites -> selectTopLevelRoute(TvRoute.Favorites, TvRailSelectionOrigin.Rail)
            TvRailTarget.Home -> selectTopLevelRoute(TvRoute.Home, TvRailSelectionOrigin.Rail)
            TvRailTarget.Downloads -> selectTopLevelRoute(TvRoute.Downloads, TvRailSelectionOrigin.Rail)
            TvRailTarget.Settings -> selectTopLevelRoute(TvRoute.Settings, TvRailSelectionOrigin.Rail)
            is TvRailTarget.Library ->
                navigationLibraries
                    .firstOrNull { library -> library.id == target.id }
                    ?.let { library -> openNavigationLibrary(library, TvRailSelectionOrigin.Rail) }
        }
    }

    val hostedRailEnabledState = rememberUpdatedState(true)
    val hostedRailVisibleState = rememberUpdatedState(railEnabled)
    val hostedRailHasFocusState = rememberUpdatedState(railEnabled && railHasFocus)
    val hostedRailAutofocusSuppressedState = rememberUpdatedState(contentAutofocusSuppressed)
    val hostedRailRegistrationKeyState = rememberUpdatedState(activeContentRegistrationKey)
    val hostedRailRequestFocusState = rememberUpdatedState<() -> Boolean>({ requestRailFocus() })
    val hostedRailSetRequesterState =
        rememberUpdatedState<(String, FocusRequester?) -> Unit> { registrationKey, requester ->
            if (registrationKey == activeContentRegistrationKey) {
                contentRightFocusRegistrationKey = registrationKey
                contentRightFocusRequester = requester
            }
        }
    val hostedRailSetActionState =
        rememberUpdatedState<(String, (() -> Boolean)?) -> Unit> { registrationKey, action ->
            if (registrationKey == activeContentRegistrationKey) {
                contentRightFocusActionRegistrationKey = registrationKey
                contentRightFocusAction = action
            }
        }
    val hostedRailController =
        remember {
            TvHostedRailController(
                enabledState = hostedRailEnabledState,
                visibleState = hostedRailVisibleState,
                railHasFocusState = hostedRailHasFocusState,
                contentAutofocusSuppressedState = hostedRailAutofocusSuppressedState,
                contentRegistrationKeyState = hostedRailRegistrationKeyState,
                requestRailFocusState = hostedRailRequestFocusState,
                setContentRightFocusRequesterState = hostedRailSetRequesterState,
                setContentRightFocusActionState = hostedRailSetActionState,
            )
        }

    CompositionLocalProvider(
        LocalTvHostedRailController provides hostedRailController,
        LocalTvFocusCoordinator provides focusCoordinator,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(LocalAppBackgroundBrush.current),
        ) {
            val routeContent: @Composable () -> Unit = {
                val routeTransition =
                    updateTransition(
                        targetState =
                            TvRouteRenderKey(
                                route = route,
                                entryId = focusCoordinator.activeRouteEntryId,
                                detailItemId = detailItemId.takeIf { route == TvRoute.Detail.name }.orEmpty(),
                                seriesItemId =
                                    seriesItemId
                                        .takeIf { route == TvRoute.Series.name || route == TvRoute.Season.name }
                                        .orEmpty(),
                                seasonItemId = seasonItemId.takeIf { route == TvRoute.Season.name }.orEmpty(),
                            ),
                        label = "tv-route",
                    )
                LaunchedEffect(
                    routeTransition.targetState,
                    routeTransition.isRunning,
                    focusCoordinator.pendingRestore?.token,
                ) {
                    val restore = focusCoordinator.pendingRestore ?: return@LaunchedEffect
                    if (restore.routeEntryId == routeTransition.targetState.entryId) {
                        focusCoordinator.updateRestoreStatus(
                            if (routeTransition.isRunning) {
                                com.jellyscope.tv.ui.focus.TvFocusRestoreStatus.PendingTransition
                            } else {
                                com.jellyscope.tv.ui.focus.TvFocusRestoreStatus.PendingGroup
                            },
                        )
                    }
                }
                routeTransition.AnimatedContent(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onPreviewKeyEvent { event ->
                                focusCoordinator.cancelForUserInput(
                                    event,
                                    // Modal-trapped keys do not cancel pending restore.
                                    interactive = !routeTransition.isRunning && !focusCoordinator.focusTrapActive,
                                )
                                false
                            },
                    transitionSpec = { tvRouteTransitionSpec() },
                    contentKey = { key -> key.contentIdentity() },
                ) { renderKey ->
                    val animatedRoute = renderKey.route
                    val entryDetailFocusBridge =
                        remember(focusCoordinator, renderKey.entryId) {
                            TvRouteEntryDetailFocusBridge(focusCoordinator, renderKey.entryId)
                        }
                    CompositionLocalProvider(LocalDetailFocusBridge provides entryDetailFocusBridge) {
                        when {
                            animatedRoute == TvRoute.Series.name || animatedRoute == TvRoute.Season.name -> {
                                val showingSeason = animatedRoute == TvRoute.Season.name
                                val seriesViewModel: SeriesViewModel =
                                    koinViewModel(
                                        viewModelStoreOwner = seriesSeasonViewModelStoreOwner,
                                        key = tvSeriesSeasonViewModelKey(session, renderKey.seriesItemId),
                                        parameters = { parametersOf(session, renderKey.seriesItemId) },
                                    )
                                BackHandler {
                                    popRoute()
                                }
                                TvRouteScope(key = "series-${renderKey.seriesItemId}") {
                                    TvSeriesSeasonAdapter(
                                        showingSeason = showingSeason,
                                        session = session,
                                        seriesId = renderKey.seriesItemId,
                                        initialSeasonId = renderKey.seasonItemId,
                                        onBackFromSeries = { popRoute() },
                                        onBackFromSeason = { popRoute() },
                                        onPlayFromSeries = {
                                            itemId,
                                            startTicks,
                                            mediaSourceId,
                                            audioStreamIndex,
                                            subtitleSelection,
                                            queue,
                                            ->
                                            openPlayer(
                                                itemId = itemId,
                                                startTicks = startTicks,
                                                mediaSourceId = mediaSourceId,
                                                initialAudioStreamIndex = audioStreamIndex,
                                                initialSubtitleSelection = subtitleSelection,
                                                queue = queue,
                                            )
                                        },
                                        onPlayFromSeason = {
                                            itemId,
                                            startTicks,
                                            mediaSourceId,
                                            audioStreamIndex,
                                            subtitleSelection,
                                            queue,
                                            ->
                                            openPlayer(
                                                itemId = itemId,
                                                startTicks = startTicks,
                                                mediaSourceId = mediaSourceId,
                                                initialAudioStreamIndex = audioStreamIndex,
                                                initialSubtitleSelection = subtitleSelection,
                                                queue = queue,
                                            )
                                        },
                                        onSeasonSelected = { selectedSeasonId ->
                                            seasonItemId = selectedSeasonId
                                            pushCurrentRoute()
                                            route = TvRoute.Season.name
                                        },
                                        onSeasonTabSelected = { selectedSeasonId -> seasonItemId = selectedSeasonId },
                                        onRelatedItemSelected = { item -> openTvItem(item) },
                                        onPlayRelatedDirect = { item -> playTvItemDirect(item) },
                                        onPersonSelected = { selectedPersonId -> openPerson(selectedPersonId) },
                                        viewModel = seriesViewModel,
                                    )
                                }
                            }
                            animatedRoute == TvRoute.Detail.name -> {
                                val renderedItemId = renderKey.detailItemId
                                DisposableEffect(detailEntryStore, renderKey.entryId) {
                                    detailEntryStore.onEntryRendered(renderKey.entryId)
                                    onDispose { detailEntryStore.onEntryDisposed(renderKey.entryId) }
                                }
                                val detailViewModel: DetailViewModel =
                                    koinViewModel(
                                        viewModelStoreOwner = detailEntryStore.ownerFor(renderKey.entryId),
                                        key = tvDetailViewModelKey(session, renderKey.entryId, renderedItemId),
                                        parameters = { parametersOf(session, renderedItemId) },
                                    )
                                BackHandler { popRoute() }
                                TvRouteScope(key = "detail-${renderKey.entryId.value}-$renderedItemId") {
                                    TvDetailAdapter(
                                        session = session,
                                        itemId = renderedItemId,
                                        onBack = { popRoute() },
                                        onPlay = { itemId, startTicks, mediaSourceId, audioStreamIndex, subtitleSelection ->
                                            openPlayer(
                                                itemId = itemId,
                                                startTicks = startTicks,
                                                mediaSourceId = mediaSourceId,
                                                initialAudioStreamIndex = audioStreamIndex,
                                                initialSubtitleSelection = subtitleSelection,
                                            )
                                        },
                                        onPlayWithSubtitleIntent = {
                                            itemId,
                                            startTicks,
                                            mediaSourceId,
                                            audioStreamIndex,
                                            subtitleSelection,
                                            ->
                                            openPlayer(
                                                itemId = itemId,
                                                startTicks = startTicks,
                                                mediaSourceId = mediaSourceId,
                                                initialAudioStreamIndex = audioStreamIndex,
                                                initialSubtitleSelection = subtitleSelection,
                                            )
                                        },
                                        onRelatedItemSelected = { item -> openDetailRelatedItem(item) },
                                        onPlayRelatedDirect = { item -> playTvItemDirect(item) },
                                        onPersonSelected = { selectedPersonId -> openPerson(selectedPersonId) },
                                        onOpenDownloads = { resetRoute(TvRoute.Downloads) },
                                        onPlayOffline = { record ->
                                            openPlayer(
                                                itemId = record.businessKey.itemId,
                                                startTicks = 0L,
                                                mediaSourceId = record.businessKey.mediaSourceId,
                                                offlineDownloadId = record.downloadId,
                                            )
                                        },
                                        viewModel = detailViewModel,
                                    )
                                }
                            }
                            animatedRoute == TvRoute.Grid.name -> {
                                BackHandler { popRoute() }
                                val row = HomeRow.values().firstOrNull { value -> value.name == gridRow } ?: HomeRow.ContinueWatching
                                gridStateHolder.SaveableStateProvider(
                                    tvPresentationStateKey(
                                        "grid-$gridRow",
                                        renderKey,
                                        focusCoordinator.presentationEpoch,
                                    ),
                                ) {
                                    TvGridScreen(
                                        session = session,
                                        row = row,
                                        onBack = { popRoute() },
                                        onHomeSelected = { resetRoute(TvRoute.Home) },
                                        onFindSelected = { resetRoute(TvRoute.Find) },
                                        onDiscoverSelected = { resetRoute(TvRoute.Discover) },
                                        onFavoritesSelected = { resetRoute(TvRoute.Favorites) },
                                        onSettingsSelected = { resetRoute(TvRoute.Settings) },
                                        onItemSelected = { item -> openTvItem(item) },
                                        onItemPlayDirect = { item -> playTvItemDirect(item) },
                                        onShuffle = { queue ->
                                            queue.firstOrNull()?.let { firstItemId ->
                                                openPlayer(
                                                    itemId = firstItemId,
                                                    startTicks = 0L,
                                                    mediaSourceId = null,
                                                    queue = queue,
                                                )
                                            }
                                        },
                                        viewModel =
                                            koinViewModel<GridViewModel>(
                                                viewModelStoreOwner = topLevelViewModelStoreOwner,
                                                key = tvTopLevelViewModelKey(session, "grid-${row.name}"),
                                                parameters = { parametersOf(session, row) },
                                            ),
                                    )
                                }
                            }
                            animatedRoute == TvRoute.Library.name -> {
                                val key = "library-${libraryParentId.orEmpty()}"
                                val currentLibraryType =
                                    LibraryCollectionType.entries.firstOrNull { type -> type.name == libraryCollectionType }
                                        ?: LibraryCollectionType.Other
                                val libraryHubViewModel =
                                    libraryParentId?.let { parentId ->
                                        koinViewModel<LibraryHubViewModel>(
                                            viewModelStoreOwner = topLevelViewModelStoreOwner,
                                            key = tvTopLevelViewModelKey(session, "hub-$key"),
                                            parameters = { parametersOf(session, parentId, currentLibraryType) },
                                        )
                                    }
                                libraryStateHolder.SaveableStateProvider(
                                    tvPresentationStateKey(key, renderKey, focusCoordinator.presentationEpoch),
                                ) {
                                    TvRouteScope(key = key) {
                                        TvLibraryScreen(
                                            session = session,
                                            parentId = null,
                                            title = libraryTitle,
                                            onBack = { resetRoute(TvRoute.Home) },
                                            onItemSelected = { item -> openTvItem(item) },
                                            onItemPlayDirect = { item -> playTvItemDirect(item) },
                                            onShuffleQueue = { queue ->
                                                queue.firstOrNull()?.let { itemId -> openPlayer(itemId, 0L, null, queue = queue) }
                                            },
                                            collectionType =
                                            currentLibraryType,
                                            gridHeroEligible = true,
                                            hubViewModel = libraryHubViewModel,
                                            onAmbientPresentationChanged = { presentation ->
                                                drawerAmbientPresentation = presentation
                                            },
                                            viewModelKey = key,
                                            viewModel =
                                                koinViewModel<LibraryBrowseViewModel>(
                                                    viewModelStoreOwner = topLevelViewModelStoreOwner,
                                                    key = tvTopLevelViewModelKey(session, key),
                                                    parameters = {
                                                        parametersOf(
                                                            session,
                                                            libraryParentId,
                                                            LibraryCollectionType.entries.firstOrNull { type ->
                                                                type.name ==
                                                                    libraryCollectionType
                                                            }
                                                                ?: LibraryCollectionType.Other,
                                                        )
                                                    },
                                                ),
                                        )
                                    }
                                }
                            }
                            animatedRoute == TvRoute.Find.name -> {
                                findStateHolder.SaveableStateProvider(
                                    tvPresentationStateKey("find", renderKey, focusCoordinator.presentationEpoch),
                                ) {
                                    TvRouteScope(key = "find") {
                                        TvFindScreen(
                                            session = session,
                                            onBack = { resetRoute(TvRoute.Home) },
                                            onItemSelected = { item -> openTvItem(item) },
                                            onItemPlayDirect = { item -> playTvItemDirect(item) },
                                            viewModel =
                                                koinViewModel<FindViewModel>(
                                                    viewModelStoreOwner = topLevelViewModelStoreOwner,
                                                    key = tvTopLevelViewModelKey(session, "find"),
                                                    parameters = { parametersOf(session) },
                                                ),
                                        )
                                    }
                                }
                            }
                            animatedRoute == TvRoute.Favorites.name -> {
                                val favoritesFilters =
                                    LibraryFilterSelection(itemFilters = listOf(LibraryItemFilter.Favorite))
                                libraryStateHolder.SaveableStateProvider(
                                    tvPresentationStateKey("favorites", renderKey, focusCoordinator.presentationEpoch),
                                ) {
                                    TvRouteScope(key = "favorites") {
                                        TvLibraryScreen(
                                            session = session,
                                            parentId = libraryParentId,
                                            title = stringResource(R.string.tv_favorites),
                                            initialFilters = favoritesFilters,
                                            viewModelKey = "library-favorites",
                                            onBack = { resetRoute(TvRoute.Home) },
                                            onItemSelected = { item -> openTvItem(item) },
                                            onItemPlayDirect = { item -> playTvItemDirect(item) },
                                            onShuffleQueue = {},
                                            collectionType = LibraryCollectionType.Other,
                                            viewModel =
                                                koinViewModel<LibraryBrowseViewModel>(
                                                    viewModelStoreOwner = topLevelViewModelStoreOwner,
                                                    key = tvTopLevelViewModelKey(session, "library-favorites"),
                                                    parameters = { parametersOf(session, null, LibraryCollectionType.Other) },
                                                ),
                                        )
                                    }
                                }
                            }
                            animatedRoute == TvRoute.Downloads.name && isTvDownloadsVisible(session) -> {
                                downloadsStateHolder.SaveableStateProvider(
                                    tvPresentationStateKey("downloads", renderKey, focusCoordinator.presentationEpoch),
                                ) {
                                    TvRouteScope(key = "downloads") {
                                        TvDownloadsScreen(
                                            session = session,
                                            onBack = { resetRoute(TvRoute.Home) },
                                            onPlayOffline = { record ->
                                                openPlayer(
                                                    itemId = record.businessKey.itemId,
                                                    startTicks = 0L,
                                                    mediaSourceId = record.businessKey.mediaSourceId,
                                                    offlineDownloadId = record.downloadId,
                                                )
                                            },
                                            viewModel =
                                                koinViewModel<DownloadsViewModel>(
                                                    viewModelStoreOwner = topLevelViewModelStoreOwner,
                                                    key = tvTopLevelViewModelKey(session, "downloads"),
                                                    parameters = { parametersOf(session) },
                                                ),
                                        )
                                    }
                                }
                            }
                            animatedRoute == TvRoute.Player.name -> {
                                TvRouteScope(
                                    key =
                                        "player-$playerRouteKey-$playerItemId-$playerStartTicks-" +
                                            "${playerQueueIds.size}-${playerOfflineDownloadId.orEmpty()}",
                                ) {
                                    TvPlayerScreen(
                                        session = session,
                                        itemId = playerItemId,
                                        startPositionTicks = playerStartTicks,
                                        mediaSourceId = playerMediaSourceId,
                                        initialAudioStreamIndex = playerInitialAudioStreamIndex,
                                        initialSubtitleSelection =
                                            playerInitialSubtitleAssetId
                                                ?.let(SubtitleSelectionIntent::LocalAsset)
                                                ?: SubtitleSelectionIntent.fromWireIndex(playerInitialSubtitleStreamIndex),
                                        queue = playerQueueIds,
                                        offlineDownloadId = playerOfflineDownloadId.toTvOfflineDownloadIdOrNull(),
                                        onBack = {
                                            onPlaybackStopped()
                                            popRoute()
                                            playerQueueIds = emptyList()
                                            playerOfflineDownloadId = null
                                        },
                                        onOpenPlaybackSettings = {
                                            onPlaybackStopped()
                                            playerQueueIds = emptyList()
                                            playerOfflineDownloadId = null
                                            resetRoute(TvRoute.Settings)
                                        },
                                    )
                                }
                            }
                            animatedRoute == TvRoute.Discover.name -> {
                                discoverStateHolder.SaveableStateProvider(
                                    tvPresentationStateKey("discover", renderKey, focusCoordinator.presentationEpoch),
                                ) {
                                    TvRouteScope(key = "discover") {
                                        TvDiscoverScreen(
                                            session = session,
                                            onBack = {},
                                            onGenreSelected = { genre ->
                                                openFilteredLibrary(
                                                    title = genre.name,
                                                    genreId = genre.id ?: genre.name,
                                                )
                                            },
                                            onStudioSelected = { studio ->
                                                openFilteredLibrary(
                                                    title = studio.name,
                                                    studioId = studio.id ?: studio.name,
                                                )
                                            },
                                            onCollectionSelected = { item -> openCollection(item) },
                                            onItemSelected = { item -> openTvItem(item) },
                                            onItemPlayDirect = { item -> playTvItemDirect(item) },
                                            viewModel =
                                                koinViewModel<DiscoverViewModel>(
                                                    viewModelStoreOwner = topLevelViewModelStoreOwner,
                                                    key = tvTopLevelViewModelKey(session, "discover"),
                                                    parameters = { parametersOf(session, null) },
                                                ),
                                        )
                                    }
                                }
                            }
                            animatedRoute == TvRoute.FilteredLibrary.name -> {
                                BackHandler { popRoute(TvRoute.Discover) }
                                val filters =
                                    LibraryFilterSelection(
                                        genreIds = libraryFilterGenreId?.let { genreId -> listOf(genreId) }.orEmpty(),
                                        studioIds = libraryFilterStudioId?.let { studioId -> listOf(studioId) }.orEmpty(),
                                    )
                                val key = "library-filter-${libraryFilterGenreId.orEmpty()}-${libraryFilterStudioId.orEmpty()}"
                                libraryStateHolder.SaveableStateProvider(
                                    tvPresentationStateKey(key, renderKey, focusCoordinator.presentationEpoch),
                                ) {
                                    TvRouteScope(key = key) {
                                        TvLibraryScreen(
                                            session = session,
                                            parentId = libraryParentId,
                                            title = libraryTitle,
                                            initialFilters = filters,
                                            viewModelKey = key,
                                            onBack = { popRoute(TvRoute.Discover) },
                                            onItemSelected = { item -> openTvItem(item) },
                                            onItemPlayDirect = { item -> playTvItemDirect(item) },
                                            onShuffleQueue = {},
                                            collectionType =
                                                LibraryCollectionType.entries.firstOrNull { type -> type.name == libraryCollectionType }
                                                    ?: LibraryCollectionType.Other,
                                            gridHeroEligible = libraryParentId != null,
                                        )
                                    }
                                }
                            }
                            animatedRoute == TvRoute.Collection.name -> {
                                BackHandler { popRoute(TvRoute.Discover) }
                                TvRouteScope(key = "collection-$collectionItemId") {
                                    TvCollectionScreen(
                                        session = session,
                                        collectionId = collectionItemId,
                                        title = collectionTitle,
                                        onBack = { popRoute(TvRoute.Discover) },
                                        onHomeSelected = { resetRoute(TvRoute.Home) },
                                        onFindSelected = { resetRoute(TvRoute.Find) },
                                        onFavoritesSelected = { resetRoute(TvRoute.Favorites) },
                                        onSettingsSelected = { resetRoute(TvRoute.Settings) },
                                        onItemSelected = { item -> openTvItem(item) },
                                        onItemPlayDirect = { item -> playTvItemDirect(item) },
                                        gridHeroEligible = collectionOriginLibraryId != null,
                                    )
                                }
                            }
                            animatedRoute == TvRoute.Person.name -> {
                                BackHandler { popRoute(TvRoute.Discover) }
                                TvRouteScope(key = "person-$personItemId") {
                                    TvPersonScreen(
                                        session = session,
                                        personId = personItemId,
                                        onBack = { popRoute(TvRoute.Discover) },
                                        onHomeSelected = { resetRoute(TvRoute.Home) },
                                        onFindSelected = { resetRoute(TvRoute.Find) },
                                        onFavoritesSelected = { resetRoute(TvRoute.Favorites) },
                                        onSettingsSelected = { resetRoute(TvRoute.Settings) },
                                        onItemSelected = { item -> openTvItem(item) },
                                        onItemPlayDirect = { item -> playTvItemDirect(item) },
                                    )
                                }
                            }
                            animatedRoute == TvRoute.Settings.name -> {
                                TvRouteScope(key = "settings") {
                                    TvSettingsScreen(
                                        session = session,
                                        onLogoutComplete = { resetRoute(TvRoute.Home) },
                                    )
                                }
                            }
                            else -> {
                                // Home owns full-bleed background and saveable row state.
                                val homePresentationEpoch = focusCoordinator.presentationEpoch
                                homeStateHolder.SaveableStateProvider(
                                    tvPresentationStateKey("home", renderKey, homePresentationEpoch),
                                ) {
                                    val pendingHomeRestore =
                                        focusCoordinator.readyRestore
                                            ?.takeIf { restore -> restore.routeEntryId == renderKey.entryId }
                                            ?.takeIf { restore -> restore.path.scopes.firstOrNull() == "home" }
                                    val entryHomePath =
                                        remember(
                                            renderKey.entryId,
                                            homePresentationEpoch,
                                            pendingHomeRestore?.token,
                                        ) {
                                            pendingHomeRestore?.path
                                                ?: Snapshot.withoutReadObservation {
                                                    focusCoordinator.activePath
                                                        ?.takeIf {
                                                            focusCoordinator.activeRouteEntryId == renderKey.entryId
                                                        }?.takeIf { path -> path.scopes.firstOrNull() == "home" }
                                                }
                                        }
                                    val pendingHomeRow =
                                        entryHomePath
                                            ?.scopes
                                            ?.firstOrNull { scope -> scope.startsWith("row:") }
                                            ?.removePrefix("row:")
                                    val homeViewModel =
                                        koinViewModel<HomeViewModel>(
                                            viewModelStoreOwner = topLevelViewModelStoreOwner,
                                            key = tvTopLevelViewModelKey(session, "home"),
                                            parameters = { parametersOf(session) },
                                        )
                                    TvHomeScreen(
                                        session = session,
                                        restoredFocusItemId =
                                            entryHomePath
                                                ?.targetKey
                                                ?.takeIf {
                                                    entryHomePath.targetKind ==
                                                        com.jellyscope.tv.ui.focus.TvFocusTargetKind.Item
                                                }?.removePrefix("item:"),
                                        restoredFocusRow = pendingHomeRow,
                                        restoredFocusIndex = entryHomePath?.fallbackIndex ?: 0,
                                        restoredFocusViewAll =
                                            entryHomePath?.targetKind ==
                                                com.jellyscope.tv.ui.focus.TvFocusTargetKind.ViewAll,
                                        onViewAllSelected = { row ->
                                            pushCurrentRoute()
                                            gridRow = row.name
                                            route = TvRoute.Grid.name
                                        },
                                        onItemSelected = { item ->
                                            if (item.supported) {
                                                openTvItem(item)
                                            }
                                        },
                                        onItemPlayDirect = { item ->
                                            playTvItemDirect(item)
                                        },
                                        onAmbientPresentationChanged = { presentation ->
                                            drawerAmbientPresentation = presentation
                                        },
                                        viewModel = homeViewModel,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (railEnabled) {
                TvNavigationDrawer(
                    session = session,
                    drawerState = drawerState,
                    selected = selectedRailDestination,
                    onSearchSelected = { selectRailTarget(TvRailTarget.Find) },
                    onDiscoverSelected = { selectRailTarget(TvRailTarget.Discover) },
                    onFavoritesSelected = { selectRailTarget(TvRailTarget.Favorites) },
                    onHomeSelected = { selectRailTarget(TvRailTarget.Home) },
                    onDownloadsSelected = { selectRailTarget(TvRailTarget.Downloads) },
                    onSettingsSelected = { selectRailTarget(TvRailTarget.Settings) },
                    modifier = Modifier.fillMaxSize(),
                    libraries = navigationLibraries,
                    onLibrarySelected = { library -> selectRailTarget(TvRailTarget.Library(library.id)) },
                    selectedLibraryId = selectedRailLibraryId,
                    focusFallbackDestination = railFocusFallbackDestination,
                    onFocusStateChanged = { hasFocus -> railHasFocus = hasFocus },
                    onTargetFocused = { target -> selectRailTarget(target) },
                    selectedItemFocusRequester = railSelectedTabRequester,
                    contentRightFocusRequester = activeContentRightFocusRequester,
                    onExitRight = activeContentRightFocusAction,
                    underlay = {
                        when (route) {
                            TvRoute.Home.name,
                            TvRoute.Library.name,
                            -> {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .tvHomeAmbientBackground(drawerAmbientPresentation),
                                )
                            }
                            TvRoute.Settings.name -> TvSettingsBackdrop()
                            else -> Unit
                        }
                    },
                ) {
                    routeContent()
                }
            } else {
                routeContent()
            }
        }
    }
}

private enum class TvRailSelectionOrigin {
    Content,
    Rail,
}
