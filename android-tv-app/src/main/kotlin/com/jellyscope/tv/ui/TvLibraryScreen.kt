// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.TvUiPreferencesStore
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.library.LibraryBrowseEvent
import com.jellyscope.ui.screen.library.LibraryBrowseUiState
import com.jellyscope.ui.screen.library.LibraryBrowseViewModel
import com.jellyscope.ui.screen.library.LibraryHubUiState
import com.jellyscope.ui.screen.library.LibraryHubViewModel
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

internal enum class TvLibraryFocusRegion {
    Chrome,
    TopContent,
    LowerContent,
}

internal fun Modifier.tvLibraryChromeVisibility(visible: Boolean?): Modifier =
    if (visible == null) {
        this
    } else {
        graphicsLayer {
            alpha = if (visible) 1f else 0f
        }
    }

@Composable
private fun TvLibraryAlternateContent(
    session: Session,
    state: LibraryHubUiState,
    tabRequester: FocusRequester,
    onBack: () -> Unit,
    onContentFocusActionChanged: ((() -> Boolean)?) -> Unit,
    onFocusRegionChanged: (TvLibraryFocusRegion) -> Unit,
    onAmbientPresentationChanged: (TvHeroAmbientPresentation) -> Unit,
    onRetryRecommendation: (com.jellyscope.core.domain.model.LibraryRecommendationSection) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostedRailController = LocalTvHostedRailController.current
    val railVisible = hostedRailController.visible
    val railHasFocus = hostedRailController.railHasFocus
    val contentRequester = remember { FocusRequester() }
    val contentStartPadding = if (railVisible) TvDimens.drawerContentStartPadding else TvDimens.overscanHorizontal

    DisposableEffect(contentRequester) {
        onContentFocusActionChanged { contentRequester.requestFocusSafely() }
        onDispose {
            onContentFocusActionChanged(null)
        }
    }
    BackHandler(enabled = !railHasFocus) {
        if (!hostedRailController.requestRailFocus()) onBack()
    }

    Box(
        modifier = modifier.fillMaxSize().background(LocalAppBackgroundBrush.current).focusGroup(),
    ) {
        when (state.selectedView) {
            LibraryInnerView.Recommended ->
                TvLibraryRecommendedContent(
                    session = session,
                    states = state.recommendationSections,
                    contentRequester = contentRequester,
                    tabsRequester = tabRequester,
                    onFocusRegionChanged = onFocusRegionChanged,
                    onRetry = onRetryRecommendation,
                    onItemSelected = onItemSelected,
                    onItemPlayDirect = onItemPlayDirect,
                    onAmbientPresentationChanged = onAmbientPresentationChanged,
                    contentStartPadding = contentStartPadding,
                    modifier = Modifier.fillMaxSize(),
                )
            LibraryInnerView.Library,
            LibraryInnerView.Genres,
            LibraryInnerView.Collections,
            -> Unit
        }
    }
}

@Composable
internal fun TvLibraryScreen(
    session: Session,
    parentId: String?,
    title: String?,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onShuffleQueue: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    initialFilters: LibraryFilterSelection? = null,
    collectionType: LibraryCollectionType = LibraryCollectionType.Other,
    gridHeroEligible: Boolean = false,
    hubViewModel: LibraryHubViewModel? = null,
    onAmbientPresentationChanged: (TvHeroAmbientPresentation) -> Unit = {},
    viewModelKey: String = "library-${parentId ?: "root"}",
    viewModel: LibraryBrowseViewModel? = null,
) {
    val hubState = hubViewModel?.state?.collectAsStateWithLifecycle()?.value
    OnResumeEffect {
        hubViewModel?.refreshRecommendedSilently()
    }
    val resolvedTitle = title ?: stringResource(R.string.tv_library_title)
    if (hubViewModel == null || hubState == null) {
        TvLibraryBrowseRoute(
            session = session,
            parentId = parentId,
            title = resolvedTitle,
            collectionType = collectionType,
            gridHeroEligible = gridHeroEligible,
            initialFilters = initialFilters,
            viewModelKey = viewModelKey,
            viewModel = viewModel,
            onBack = onBack,
            onItemSelected = onItemSelected,
            onItemPlayDirect = onItemPlayDirect,
            onShuffleQueue = onShuffleQueue,
            onAmbientPresentationChanged = onAmbientPresentationChanged,
            modifier = modifier,
        )
        return
    }

    val hostedRailController = LocalTvHostedRailController.current
    val hostedRailVisible = hostedRailController.visible
    val railHasFocus = hostedRailController.railHasFocus
    val registrationKey = remember { hostedRailController.contentRegistrationKey }
    val tabRequesters = remember { LibraryInnerView.entries.associateWith { FocusRequester() } }
    val selectedTabRequester = tabRequesters.getValue(hubState.selectedView)
    val railEntryFocusScope = rememberCoroutineScope()
    val trailingTabRequester = tabRequesters.getValue(hubState.availableViews.last())
    var contentFocusAction by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val currentContentFocusAction = rememberUpdatedState(contentFocusAction)
    var headerFocusAction by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var focusRegion by remember { mutableStateOf(TvLibraryFocusRegion.Chrome) }
    val chromeVisible = !railHasFocus && focusRegion != TvLibraryFocusRegion.LowerContent
    val contentStartPadding =
        if (hostedRailVisible) TvDimens.drawerContentStartPadding else TvDimens.overscanHorizontal

    LaunchedEffect(hostedRailVisible, registrationKey, selectedTabRequester) {
        if (hostedRailVisible) {
            hostedRailController.setContentRightFocusRequester(registrationKey, selectedTabRequester)
            hostedRailController.setContentRightFocusAction(registrationKey) {
                val focused = currentContentFocusAction.value?.invoke() == true
                if (!focused) {
                    railEntryFocusScope.launch {
                        requestTvFocusWithRetry {
                            currentContentFocusAction.value?.invoke() == true
                        }
                    }
                }
                true
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (hubState.selectedView == LibraryInnerView.Library) {
            TvLibraryBrowseRoute(
                session = session,
                parentId = parentId,
                title = resolvedTitle,
                collectionType = collectionType,
                gridHeroEligible = gridHeroEligible,
                initialFilters = initialFilters,
                viewModelKey = viewModelKey,
                viewModel = viewModel,
                onBack = onBack,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                onShuffleQueue = onShuffleQueue,
                hideTitle = true,
                contentAutofocusEnabled = false,
                reserveLeadingSpaceForTabs = true,
                registerHostedRailContentEntry = false,
                onContentFocusActionChanged = { contentFocusAction = it },
                onHeaderFocusActionChanged = { headerFocusAction = it },
                onFocusRegionChanged = { focusRegion = it },
                chromeVisible = chromeVisible,
                headerUpRequester = selectedTabRequester,
                headerStartRequester = trailingTabRequester,
                onAmbientPresentationChanged = onAmbientPresentationChanged,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            TvLibraryAlternateContent(
                session = session,
                state = hubState,
                tabRequester = selectedTabRequester,
                onBack = onBack,
                onContentFocusActionChanged = { contentFocusAction = it },
                onFocusRegionChanged = { focusRegion = it },
                onAmbientPresentationChanged = onAmbientPresentationChanged,
                onRetryRecommendation = hubViewModel::retryRecommendation,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                modifier = Modifier.fillMaxSize(),
            )
        }

        TvLibraryInnerTabs(
            views = hubState.availableViews,
            selectedView = hubState.selectedView,
            focusRequesterFor = tabRequesters::getValue,
            onRequestContentFocus = contentFocusAction,
            onRequestTrailingFocus = headerFocusAction,
            onFocusRegionChanged = { focusRegion = it },
            chromeVisible = chromeVisible,
            onSelected = hubViewModel::selectView,
            modifier =
                Modifier.padding(
                    start = contentStartPadding,
                    top = TvDimens.overscanVertical,
                    end = TvDimens.overscanHorizontal,
                ),
        )
    }
}

@Composable
private fun TvLibraryBrowseRoute(
    session: Session,
    parentId: String?,
    title: String,
    collectionType: LibraryCollectionType,
    gridHeroEligible: Boolean,
    initialFilters: LibraryFilterSelection?,
    viewModelKey: String,
    viewModel: LibraryBrowseViewModel?,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onShuffleQueue: (List<String>) -> Unit,
    hideTitle: Boolean = false,
    contentAutofocusEnabled: Boolean = true,
    reserveLeadingSpaceForTabs: Boolean = false,
    registerHostedRailContentEntry: Boolean = true,
    onContentFocusActionChanged: ((() -> Boolean)?) -> Unit = {},
    onHeaderFocusActionChanged: ((() -> Boolean)?) -> Unit = {},
    onFocusRegionChanged: (TvLibraryFocusRegion) -> Unit = {},
    chromeVisible: Boolean? = null,
    headerUpRequester: FocusRequester? = null,
    headerStartRequester: FocusRequester? = null,
    onAmbientPresentationChanged: (TvHeroAmbientPresentation) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val libraryViewModel =
        viewModel ?: koinViewModel(
            key = viewModelKey,
            parameters = { parametersOf(session, parentId, collectionType) },
        )
    val state by libraryViewModel.state.collectAsStateWithLifecycle()
    val tvUiPreferencesStore = koinInject<TvUiPreferencesStore>()
    val showGridHero by tvUiPreferencesStore.showLibraryGridHero.collectAsStateWithLifecycle()
    var shuffleNotice by remember { mutableStateOf<String?>(null) }
    val emptyMessage = stringResource(R.string.tv_library_shuffle_empty)
    val unavailableMessage = stringResource(R.string.tv_library_shuffle_unavailable)
    val currentOnShuffleQueue = rememberUpdatedState(onShuffleQueue)

    OnResumeEffect {
        libraryViewModel.refreshSilently()
    }

    LaunchedEffect(libraryViewModel, emptyMessage, unavailableMessage) {
        libraryViewModel.events.collect { event ->
            when (event) {
                is LibraryBrowseEvent.LaunchShuffle -> {
                    shuffleNotice = null
                    currentOnShuffleQueue.value(event.itemIds)
                }
                LibraryBrowseEvent.ShuffleEmpty -> shuffleNotice = emptyMessage
                LibraryBrowseEvent.ShuffleUnavailable -> shuffleNotice = unavailableMessage
            }
        }
    }

    LaunchedEffect(initialFilters, state.filters) {
        val filters = initialFilters ?: return@LaunchedEffect
        if (!state.filters.containsAll(filters)) {
            libraryViewModel.setFilters(filters)
        }
    }

    TvLibraryContent(
        session = session,
        title = title,
        state = state,
        onBack = onBack,
        onItemSelected = onItemSelected,
        onItemPlayDirect = onItemPlayDirect,
        onRetry = libraryViewModel::retry,
        onLoadMore = libraryViewModel::loadMore,
        onSortSelected = libraryViewModel::setSort,
        onFiltersSelected = libraryViewModel::setFilters,
        onShuffleAll = {
            shuffleNotice = null
            libraryViewModel.shuffleAll()
        },
        shuffleNotice = shuffleNotice,
        showGridHero = showGridHero && gridHeroEligible,
        hideTitle = hideTitle,
        contentAutofocusEnabled = contentAutofocusEnabled,
        reserveLeadingSpaceForTabs = reserveLeadingSpaceForTabs,
        registerHostedRailContentEntry = registerHostedRailContentEntry,
        onContentFocusActionChanged = onContentFocusActionChanged,
        onHeaderFocusActionChanged = onHeaderFocusActionChanged,
        onFocusRegionChanged = onFocusRegionChanged,
        chromeVisible = chromeVisible,
        headerUpRequester = headerUpRequester,
        headerStartRequester = headerStartRequester,
        onAmbientPresentationChanged = onAmbientPresentationChanged,
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvLibraryContent(
    session: Session,
    title: String,
    state: LibraryBrowseUiState,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSortSelected: (LibrarySortBy, LibrarySortOrder) -> Unit,
    onFiltersSelected: (LibraryFilterSelection) -> Unit,
    onShuffleAll: () -> Unit,
    shuffleNotice: String?,
    showGridHero: Boolean = true,
    hideTitle: Boolean = false,
    contentAutofocusEnabled: Boolean = true,
    innerTabs: @Composable () -> Unit = {},
    reserveLeadingSpaceForTabs: Boolean = false,
    registerHostedRailContentEntry: Boolean = true,
    onContentFocusActionChanged: ((() -> Boolean)?) -> Unit = {},
    onHeaderFocusActionChanged: ((() -> Boolean)?) -> Unit = {},
    onFocusRegionChanged: (TvLibraryFocusRegion) -> Unit = {},
    chromeVisible: Boolean? = null,
    headerUpRequester: FocusRequester? = null,
    headerStartRequester: FocusRequester? = null,
    onAmbientPresentationChanged: (TvHeroAmbientPresentation) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var focusedItem by remember { mutableStateOf<MediaCardUi?>(null) }
    var heroItem by remember { mutableStateOf<MediaCardUi?>(null) }
    var sortPickerOpen by rememberSaveable { mutableStateOf(false) }
    var filterPickerOpen by rememberSaveable { mutableStateOf(false) }
    val sortButtonRequester = remember { FocusRequester() }
    val filterButtonRequester = remember { FocusRequester() }
    val shuffleButtonRequester = remember { FocusRequester() }
    val gridFocusRequester = remember { FocusRequester() }
    val loadingFocusRequester = remember { FocusRequester() }

    LaunchedEffect(state.items, focusedItem) {
        val target = focusedItem ?: state.items.firstOrNull() ?: return@LaunchedEffect
        if (heroItem != null && heroItem?.id != target.id) delay(HERO_SETTLE_MS)
        heroItem = target
    }
    val heroVisible = showGridHero && state.items.isNotEmpty()
    val ambientState =
        rememberTvHeroAmbientState(
            item = heroItem.takeIf { heroVisible },
            surface = TvHeroAmbientSurface.Library,
            accountKey = "${session.serverId}|${session.userId}",
        )
    SideEffect {
        onAmbientPresentationChanged(ambientState.presentation)
    }
    DisposableEffect(Unit) {
        onDispose { onAmbientPresentationChanged(TvHeroAmbientPresentation.Empty) }
    }
    var requestGridFocus by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val contentEntryFocusRequests = remember { mutableStateOf(0) }
    // Survive grid reloads so sort/filter actions keep header focus.
    val gridInitialFocusRequested = remember { mutableStateOf(false) }
    val hostedRailController = LocalTvHostedRailController.current
    val hostedRail = hostedRailController.enabled
    val hostedRailVisible = hostedRailController.visible
    val requestInitialContentFocus =
        contentAutofocusEnabled && !hostedRailController.contentAutofocusSuppressed
    val hostedRailContentRegistrationKey = remember { hostedRailController.contentRegistrationKey }
    val railHasFocus = hostedRailController.railHasFocus
    var sortPickerWasOpen by remember { mutableStateOf(false) }
    var filterPickerWasOpen by remember { mutableStateOf(false) }
    val loadingFocusParkActive = hostedRailVisible && state.isLoading && state.items.isEmpty()
    val currentLoadingFocusParkActive = rememberUpdatedState(loadingFocusParkActive)
    val currentLibraryHasItems = rememberUpdatedState(state.items.isNotEmpty())
    val currentRequestGridFocus = rememberUpdatedState(requestGridFocus)
    var focusRescueTick by remember { mutableIntStateOf(0) }
    val contentRightFocusRequester =
        when {
            loadingFocusParkActive -> loadingFocusRequester
            state.items.isNotEmpty() -> gridFocusRequester
            else -> sortButtonRequester
        }
    val headerEntryRequester =
        if (state.collectionType == LibraryCollectionType.Movies) {
            shuffleButtonRequester
        } else {
            sortButtonRequester
        }
    val contentStartPadding =
        if (hostedRailVisible) {
            TvDimens.drawerContentStartPadding
        } else {
            TvDimens.overscanHorizontal
        }

    // Replace removed focused cards locally; fall back to Sort when the grid empties.
    // The long-lived effect reads the latest plain state through rememberUpdatedState.
    val currentBrowseState = rememberUpdatedState(state)
    LaunchedEffect(Unit) {
        snapshotFlow {
            val currentId = focusedItem?.id ?: return@snapshotFlow null to null
            val browseState = currentBrowseState.value
            when {
                browseState.isLoading || browseState.isLoadingMore -> currentId to null
                else -> currentId to browseState.items.any { item -> item.id == currentId }
            }
        }.distinctUntilChanged()
            .collect { (_, present) ->
                if (present == false) {
                    focusRescueTick++
                }
            }
    }

    // Retry after layout before falling back from an unattached grid tile to Sort.
    val focusContent =
        remember(loadingFocusRequester, sortButtonRequester) {
            {
                if (
                    requestLibraryContentFocus(
                        loadingFocusParkActive = currentLoadingFocusParkActive.value,
                        hasItems = currentLibraryHasItems.value,
                        loadingFocusRequester = loadingFocusRequester,
                        requestGridFocus = currentRequestGridFocus.value,
                        sortButtonRequester = sortButtonRequester,
                        allowSortFallback = false,
                    )
                ) {
                    true
                } else {
                    contentEntryFocusRequests.value += 1
                    true
                }
            }
        }

    DisposableEffect(focusContent) {
        onContentFocusActionChanged(focusContent)
        onDispose { onContentFocusActionChanged(null) }
    }
    DisposableEffect(headerEntryRequester) {
        onHeaderFocusActionChanged { headerEntryRequester.requestFocusSafely() }
        onDispose { onHeaderFocusActionChanged(null) }
    }

    LaunchedEffect(hostedRailVisible, hostedRailContentRegistrationKey, contentRightFocusRequester, focusContent) {
        if (hostedRailVisible && registerHostedRailContentEntry) {
            hostedRailController.setContentRightFocusRequester(
                hostedRailContentRegistrationKey,
                contentRightFocusRequester,
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

    BackHandler(enabled = sortPickerOpen || filterPickerOpen) {
        sortPickerOpen = false
        filterPickerOpen = false
    }
    BackHandler(enabled = hostedRailVisible && !railHasFocus && !sortPickerOpen && !filterPickerOpen) {
        if (!requestRailFocus()) {
            onBack()
        }
    }

    // Retry focus after picker disposal and the following grid reload.
    LaunchedEffect(sortPickerOpen) {
        if (sortPickerOpen) {
            sortPickerWasOpen = true
        } else if (sortPickerWasOpen) {
            sortPickerWasOpen = false
            requestTvFocusWithRetry(attempts = LIBRARY_DRAWER_ENTRY_FOCUS_ATTEMPTS) {
                sortButtonRequester.requestFocusSafely()
            }
        }
    }
    LaunchedEffect(filterPickerOpen) {
        if (filterPickerOpen) {
            filterPickerWasOpen = true
        } else if (filterPickerWasOpen) {
            filterPickerWasOpen = false
            requestTvFocusWithRetry(attempts = LIBRARY_DRAWER_ENTRY_FOCUS_ATTEMPTS) {
                filterButtonRequester.requestFocusSafely()
            }
        }
    }

    LaunchedEffect(contentEntryFocusRequests.value) {
        if (contentEntryFocusRequests.value <= 0) {
            return@LaunchedEffect
        }
        val focused =
            requestTvFocusWithRetry(attempts = LIBRARY_DRAWER_ENTRY_FOCUS_ATTEMPTS) {
                requestLibraryContentFocus(
                    loadingFocusParkActive = currentLoadingFocusParkActive.value,
                    hasItems = currentLibraryHasItems.value,
                    loadingFocusRequester = loadingFocusRequester,
                    requestGridFocus = currentRequestGridFocus.value,
                    sortButtonRequester = sortButtonRequester,
                    allowSortFallback = false,
                )
            }
        if (!focused) {
            requestLibraryContentFocus(
                loadingFocusParkActive = currentLoadingFocusParkActive.value,
                hasItems = currentLibraryHasItems.value,
                loadingFocusRequester = loadingFocusRequester,
                requestGridFocus = currentRequestGridFocus.value,
                sortButtonRequester = sortButtonRequester,
                allowSortFallback = true,
            )
        }
    }

    LaunchedEffect(focusRescueTick) {
        if (focusRescueTick <= 0) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        val focused =
            requestTvFocusWithRetry(attempts = LIBRARY_DRAWER_ENTRY_FOCUS_ATTEMPTS) {
                requestLibraryContentFocus(
                    loadingFocusParkActive = currentLoadingFocusParkActive.value,
                    hasItems = currentLibraryHasItems.value,
                    loadingFocusRequester = loadingFocusRequester,
                    requestGridFocus = currentRequestGridFocus.value,
                    sortButtonRequester = sortButtonRequester,
                    allowSortFallback = false,
                )
            }
        if (!focused) {
            sortButtonRequester.requestFocusSafely()
        }
    }

    // Use Sort as the local focus fallback while the grid is unavailable.
    LaunchedEffect(state.items.isEmpty(), state.isLoading, state.error, sortPickerOpen, filterPickerOpen) {
        if (requestInitialContentFocus && state.items.isEmpty() && !state.isLoading && !sortPickerOpen && !filterPickerOpen) {
            withFrameNanos { }
            sortButtonRequester.requestFocusSafely()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .tvHomeAmbientBackground(ambientState.presentation)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Back) {
                        false
                    } else {
                        when {
                            sortPickerOpen || filterPickerOpen -> {
                                sortPickerOpen = false
                                filterPickerOpen = false
                                true
                            }
                            hostedRailVisible && !railHasFocus -> {
                                if (!requestRailFocus()) {
                                    onBack()
                                }
                                true
                            }
                            !hostedRailVisible -> {
                                onBack()
                                true
                            }
                            else -> false
                        }
                    }
                },
    ) {
        AmbientLayer(
            ambientColor = ambientState.presentation.color,
            animationOwnerKey = ambientState.presentation.animationOwnerKey,
            clearWhenColorMissing = ambientState.presentation.clearWhenColorMissing,
        )
        if (heroVisible) {
            TvHeroBackdrop(
                session = session,
                item = heroItem,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    // Keep header controls focusable while a picker owns the grid.
                    .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (heroVisible) {
                                Modifier.height(TvDimens.libraryHeroHeight)
                            } else {
                                Modifier
                            },
                        ),
            ) {
                if (heroVisible) {
                    TvLibraryGridHero(
                        session = session,
                        item = heroItem,
                        height = TvDimens.libraryHeroHeight,
                        showBackdrop = false,
                        contentStartPadding = contentStartPadding,
                        contentEndPadding = TvDimens.overscanHorizontal,
                        contentTopPadding = TvDimens.libraryHeroChromeContentInset,
                    )
                }
                TvLibraryHeader(
                    title = title.takeUnless { hideTitle || heroVisible },
                    state = state,
                    sortButtonRequester = sortButtonRequester,
                    filterButtonRequester = filterButtonRequester,
                    shuffleButtonRequester = shuffleButtonRequester,
                    gridFocusRequester = gridFocusRequester,
                    onOpenSortPicker = { sortPickerOpen = true },
                    onOpenFilterPicker = { filterPickerOpen = true },
                    onShuffleAll = onShuffleAll,
                    onChromeFocused = { onFocusRegionChanged(TvLibraryFocusRegion.Chrome) },
                    chromeVisible = chromeVisible,
                    innerTabs = innerTabs,
                    reserveLeadingSpaceForTabs = reserveLeadingSpaceForTabs,
                    tabsUpRequester = headerUpRequester,
                    tabsStartRequester = headerStartRequester,
                    onRequestGridFocus = focusContent,
                    modifier =
                        Modifier.padding(
                            start = contentStartPadding,
                            top = TvDimens.overscanVertical,
                            end = TvDimens.overscanHorizontal,
                        ),
                )
            }
            TvLibraryBody(
                session = session,
                state = state,
                pickerOpen = sortPickerOpen || filterPickerOpen,
                constrainedByHero = heroVisible,
                parkLoadingFocus = hostedRailVisible,
                requestInitialFocus = requestInitialContentFocus,
                contentStartPadding = contentStartPadding,
                loadingFocusRequester = loadingFocusRequester,
                gridFocusRequester = gridFocusRequester,
                onRequestGridFocusChanged = { requestGridFocus = it },
                initialFocusRequested = gridInitialFocusRequested,
                gridUpTarget = headerUpRequester ?: sortButtonRequester,
                onRequestRailFocus = ::requestRailFocus,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                onItemFocused = { item -> focusedItem = item },
                onFocusRegionChanged = onFocusRegionChanged,
                onPosterLoaded = ambientState.onPosterLoaded,
                modifier = Modifier.weight(1f),
            )
        }

        if (sortPickerOpen) {
            TvLibrarySortPickerOverlay(
                selectedSortBy = state.sortBy,
                selectedSortOrder = state.sortOrder,
                collectionType = state.collectionType,
                onSelectSort = { sortBy, sortOrder ->
                    onSortSelected(sortBy, sortOrder)
                    sortPickerOpen = false
                },
                onDismiss = {
                    sortPickerOpen = false
                },
            )
        }
        if (filterPickerOpen) {
            TvLibraryFilterPickerOverlay(
                filters = state.filters,
                facets = state.facets,
                onSelectFilters = onFiltersSelected,
                onDismiss = {
                    filterPickerOpen = false
                },
            )
        }
        shuffleNotice?.let { notice ->
            TvText(
                text = notice,
                style = TvSecondaryStyle,
                color = LocalJellyfinPalette.current.textSecondary,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = TvDimens.overscanVertical),
            )
        }
    }
}

private fun requestLibraryContentFocus(
    loadingFocusParkActive: Boolean,
    hasItems: Boolean,
    loadingFocusRequester: FocusRequester,
    requestGridFocus: (() -> Boolean)?,
    sortButtonRequester: FocusRequester,
    allowSortFallback: Boolean,
): Boolean =
    when {
        loadingFocusParkActive ->
            loadingFocusRequester.requestFocusSafely() ||
                (allowSortFallback && sortButtonRequester.requestFocusSafely())
        hasItems ->
            requestGridFocus?.invoke() == true ||
                (allowSortFallback && sortButtonRequester.requestFocusSafely())
        else -> sortButtonRequester.requestFocusSafely()
    }

private const val LIBRARY_DRAWER_ENTRY_FOCUS_ATTEMPTS = 8

internal fun LibraryFilterSelection.containsAll(required: LibraryFilterSelection): Boolean =
    genres.containsAll(required.genres) &&
        genreIds.containsAll(required.genreIds) &&
        years.containsAll(required.years) &&
        officialRatings.containsAll(required.officialRatings) &&
        studioIds.containsAll(required.studioIds) &&
        tags.containsAll(required.tags) &&
        itemFilters.containsAll(required.itemFilters) &&
        seriesStatus.containsAll(required.seriesStatus) &&
        (!required.hasSubtitles || hasSubtitles) &&
        (!required.hasTrailer || hasTrailer) &&
        (!required.hasSpecialFeature || hasSpecialFeature)
