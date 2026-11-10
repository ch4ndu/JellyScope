// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrConnectionPhase
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrQuality
import com.jellyscope.core.domain.model.SeerrQuotaKind
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.ui.focus.LocalTvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusScopeNode
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.TvFocusTrapEffect
import com.jellyscope.tv.ui.focus.rememberChildRequester
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.DetailText
import com.jellyscope.ui.component.DetailTitleStyle
import com.jellyscope.ui.component.FocusableBox
import com.jellyscope.ui.component.LoadMoreOnApproachEnd
import com.jellyscope.ui.component.MeasuredAsyncImage
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.PersonCard
import com.jellyscope.ui.focus.FocusRestoreRequest
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.library_filters
import com.jellyscope.ui.generated.resources.seerr_back
import com.jellyscope.ui.generated.resources.seerr_cancel
import com.jellyscope.ui.generated.resources.seerr_cast
import com.jellyscope.ui.generated.resources.seerr_choose_season
import com.jellyscope.ui.generated.resources.seerr_choose_seasons_action
import com.jellyscope.ui.generated.resources.seerr_close
import com.jellyscope.ui.generated.resources.seerr_confirm
import com.jellyscope.ui.generated.resources.seerr_connected
import com.jellyscope.ui.generated.resources.seerr_disconnect
import com.jellyscope.ui.generated.resources.seerr_email
import com.jellyscope.ui.generated.resources.seerr_empty
import com.jellyscope.ui.generated.resources.seerr_endpoint
import com.jellyscope.ui.generated.resources.seerr_filtered_empty
import com.jellyscope.ui.generated.resources.seerr_filters
import com.jellyscope.ui.generated.resources.seerr_hide_available
import com.jellyscope.ui.generated.resources.seerr_hide_available_hint
import com.jellyscope.ui.generated.resources.seerr_http
import com.jellyscope.ui.generated.resources.seerr_jellyfin_login
import com.jellyscope.ui.generated.resources.seerr_library_quality
import com.jellyscope.ui.generated.resources.seerr_load_more
import com.jellyscope.ui.generated.resources.seerr_loading
import com.jellyscope.ui.generated.resources.seerr_local_explanation
import com.jellyscope.ui.generated.resources.seerr_local_login
import com.jellyscope.ui.generated.resources.seerr_manage
import com.jellyscope.ui.generated.resources.seerr_open_library
import com.jellyscope.ui.generated.resources.seerr_owner_changed
import com.jellyscope.ui.generated.resources.seerr_password
import com.jellyscope.ui.generated.resources.seerr_quality_choice
import com.jellyscope.ui.generated.resources.seerr_quality_title
import com.jellyscope.ui.generated.resources.seerr_quick_connect
import com.jellyscope.ui.generated.resources.seerr_quick_connect_code
import com.jellyscope.ui.generated.resources.seerr_quick_connect_wait
import com.jellyscope.ui.generated.resources.seerr_quota_exceeded
import com.jellyscope.ui.generated.resources.seerr_reconcile
import com.jellyscope.ui.generated.resources.seerr_reconciled
import com.jellyscope.ui.generated.resources.seerr_refresh
import com.jellyscope.ui.generated.resources.seerr_refresh_status
import com.jellyscope.ui.generated.resources.seerr_related_error
import com.jellyscope.ui.generated.resources.seerr_related_interrupted
import com.jellyscope.ui.generated.resources.seerr_request
import com.jellyscope.ui.generated.resources.seerr_request_created
import com.jellyscope.ui.generated.resources.seerr_requests_empty
import com.jellyscope.ui.generated.resources.seerr_restricted
import com.jellyscope.ui.generated.resources.seerr_retry
import com.jellyscope.ui.generated.resources.seerr_search_prompt
import com.jellyscope.ui.generated.resources.seerr_search_results
import com.jellyscope.ui.generated.resources.seerr_season_selection
import com.jellyscope.ui.generated.resources.seerr_seasons
import com.jellyscope.ui.generated.resources.seerr_setup
import com.jellyscope.ui.generated.resources.seerr_sign_in
import com.jellyscope.ui.generated.resources.seerr_similar
import com.jellyscope.ui.generated.resources.seerr_uncertain
import com.jellyscope.ui.generated.resources.seerr_untitled
import com.jellyscope.ui.generated.resources.seerr_url
import com.jellyscope.ui.generated.resources.seerr_username
import com.jellyscope.ui.generated.resources.seerr_validate
import com.jellyscope.ui.generated.resources.seerr_validated
import com.jellyscope.ui.generated.resources.seerr_verify_connection
import com.jellyscope.ui.generated.resources.seerr_working
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.seerr.SeerrAvailabilityFact
import com.jellyscope.ui.screen.seerr.SeerrConnectionUi
import com.jellyscope.ui.screen.seerr.SeerrDetailHero
import com.jellyscope.ui.screen.seerr.SeerrDetailSurface
import com.jellyscope.ui.screen.seerr.SeerrDetailUi
import com.jellyscope.ui.screen.seerr.SeerrIntent
import com.jellyscope.ui.screen.seerr.SeerrNotice
import com.jellyscope.ui.screen.seerr.SeerrPersonArtwork
import com.jellyscope.ui.screen.seerr.SeerrPersonUi
import com.jellyscope.ui.screen.seerr.SeerrPoster
import com.jellyscope.ui.screen.seerr.SeerrRelatedPhase
import com.jellyscope.ui.screen.seerr.SeerrScreenEffects
import com.jellyscope.ui.screen.seerr.SeerrScrollPosition
import com.jellyscope.ui.screen.seerr.SeerrSeasonArtwork
import com.jellyscope.ui.screen.seerr.SeerrSection
import com.jellyscope.ui.screen.seerr.SeerrUiState
import com.jellyscope.ui.screen.seerr.SeerrViewModel
import com.jellyscope.ui.screen.seerr.libraryAvailable
import com.jellyscope.ui.screen.seerr.relatedEnabled
import com.jellyscope.ui.screen.seerr.reviewNeedsRefresh
import com.jellyscope.ui.screen.seerr.seerrArtworkUrl
import com.jellyscope.ui.screen.seerr.seerrAvailabilityLine
import com.jellyscope.ui.screen.seerr.seerrCatalogText
import com.jellyscope.ui.screen.seerr.seerrErrorText
import com.jellyscope.ui.screen.seerr.seerrQualityText
import com.jellyscope.ui.screen.seerr.seerrQuotaText
import com.jellyscope.ui.screen.seerr.seerrRequestLine
import com.jellyscope.ui.screen.seerr.seerrReviewLines
import com.jellyscope.ui.screen.seerr.seerrSeasonNumbersText
import com.jellyscope.ui.screen.seerr.seerrSeasonText
import com.jellyscope.ui.screen.seerr.seerrSectionText
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun TvSeerrScreen(
    viewModel: SeerrViewModel,
    session: Session,
    onBack: () -> Unit,
    onOpenLibrary: (SeerrLibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SeerrScreenEffects(viewModel, onOpenLibrary)
    TvSeerrContent(state, session, viewModel::accept, onBack, modifier)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TvSeerrContent(
    state: SeerrUiState,
    session: Session,
    onIntent: (SeerrIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    state.person?.let { person ->
        key(person.entryId) { TvSeerrPerson(person, session, onIntent, modifier) }
        return
    }
    val detail = state.detail
    val heroEntry = "requests:${state.section}:${state.catalog}:${state.feed.resetRevision}"
    var focusedHeroItem by remember(session.serverId, session.userId, heroEntry, detail?.entryId) { mutableStateOf<MediaCardUi?>(null) }
    val feedAvailable =
        detail == null &&
            state.section != SeerrSection.Connection &&
            state.ownerValid &&
            !state.restricted &&
            state.connected
    val currentHeroItem =
        focusedHeroItem?.takeIf { feedAvailable }?.let { focused ->
            state.feed.rows
                .firstOrNull { it.id == focused.id }
                ?.card
        }
    val hero = rememberTvBrowseHero(session, currentHeroItem, TvHeroAmbientSurface.Requests, heroEntry, publicArtwork = true)
    val heroVisible = feedAvailable && hero.enabled && state.feed.rows.isNotEmpty()
    val rail = LocalTvHostedRailController.current
    val header = rememberTvFocusScopeNode(listOf("requests", "sections"))
    val selectedTab =
        header.rememberChildRequester(
            if (state.section ==
                SeerrSection.Connection
            ) {
                SeerrSection.Discover.name
            } else {
                state.section.name
            },
        )
    val surface =
        when {
            state.section == SeerrSection.Connection -> "connection"
            state.detail != null -> "detail"
            state.section == SeerrSection.Requests -> "my-requests"
            state.section == SeerrSection.Search -> "search"
            else -> "discover"
        }
    val body = rememberTvFocusScopeNode(listOf("requests", surface))
    val currentState by rememberUpdatedState(state)
    val currentBody by rememberUpdatedState(body)
    val currentTab by rememberUpdatedState(selectedTab)
    var pendingEntry by remember { mutableStateOf(false) }
    var entryGeneration by remember { mutableStateOf(0L) }
    var entrySurface by remember { mutableStateOf("") }
    val currentSurface by rememberUpdatedState(surface)
    var requestBodyEntry by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var lowerContentFocused by remember { mutableStateOf(false) }
    val pickerEligible =
        state.ownerValid &&
            !state.restricted &&
            state.connected &&
            !state.connection.busy &&
            state.section == SeerrSection.Discover &&
            detail == null
    var picker by remember(pickerEligible) { mutableStateOf<String?>(null) }
    val refreshRequester = header.rememberChildRequester("refresh")
    val manageRequester = header.rememberChildRequester(SeerrSection.Connection.name)
    val catalogRequester = header.rememberChildRequester("catalog")
    val filterRequester = header.rememberChildRequester("filters")
    val lifecycleOwner = LocalLifecycleOwner.current
    var headerHasFocus by remember { mutableStateOf(false) }
    val registrationKey = remember { rail.contentRegistrationKey }
    var editing by remember { mutableStateOf(false) }
    var exitEditing by remember { mutableStateOf(0) }
    val startPadding = if (rail.visible) TvDimens.drawerContentStartPadding else TvDimens.overscanHorizontal
    val enterContent =
        remember {
            {
                entryGeneration = rail.inputGeneration
                entrySurface = currentSurface
                pendingEntry = true
                true
            }
        }
    var confirmationWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect((state.detail?.modalVisible == true)) {
        if ((state.detail?.modalVisible == true)) {
            confirmationWasOpen = true
        } else if (confirmationWasOpen) {
            confirmationWasOpen = false
            requestBodyEntry?.invoke()
        }
    }
    DisposableEffect(rail, registrationKey, selectedTab, enterContent, detail != null) {
        rail.setContentRightFocusRequester(registrationKey, body.entryRequester)
        rail.setContentRightFocusAction(registrationKey, enterContent)
        onDispose {
            rail.setContentRightFocusRequester(registrationKey, null)
            rail.setContentRightFocusAction(registrationKey, null)
        }
    }
    if (picker != null && pickerEligible) {
        val kind = picker
        val first = remember { FocusRequester() }
        val dismiss = { picker = null }
        TvFocusTrapEffect()
        LaunchedEffect(first) { requestTvFocusWithRetry { first.requestFocusSafely() } }
        TvSettingsDialogFrame(
            title = if (kind == "filters") stringResource(Res.string.library_filters) else seerrCatalogText(state.catalog),
            onDismiss = dismiss,
        ) {
            if (kind == "filters") {
                TvSettingsDialogAction(
                    stringResource(Res.string.seerr_hide_available),
                    first,
                    { onIntent(SeerrIntent.ToggleHideAvailable) },
                    selected = state.hideAvailable,
                )
                DetailText(stringResource(Res.string.seerr_hide_available_hint))
            } else {
                listOf(SeerrCatalog.Trending, SeerrCatalog.Movies, SeerrCatalog.Tv).forEachIndexed { index, catalog ->
                    val requester = if (index == 0) first else remember(catalog) { FocusRequester() }
                    TvSettingsDialogAction(seerrCatalogText(catalog), requester, {
                        onIntent(SeerrIntent.SelectCatalog(catalog))
                        dismiss()
                    }, selected = state.catalog == catalog)
                }
            }
        }
        DisposableEffect(kind) {
            onDispose { pendingEntry = false }
        }
    }
    var dismissedPicker by remember { mutableStateOf<String?>(null) }
    var pickerOpenerValid by remember { mutableStateOf(false) }
    LaunchedEffect(picker, pickerEligible) {
        if (picker != null) {
            dismissedPicker = picker
            pickerOpenerValid = true
        } else {
            if (!pickerEligible) pickerOpenerValid = false
            dismissedPicker?.let { kind ->
                val restoreOpener = pickerOpenerValid && pickerEligible
                requestTvFocusWithRetry {
                    val latest = currentState
                    val openerValid =
                        restoreOpener &&
                            latest.ownerValid &&
                            !latest.restricted &&
                            latest.connected &&
                            !latest.connection.busy &&
                            latest.section == SeerrSection.Discover &&
                            latest.detail == null
                    when {
                        openerValid -> (if (kind == "filters") filterRequester else catalogRequester).requestFocusSafely()
                        !latest.ownerValid -> rail.requestRailFocus()
                        latest.detail == null -> currentTab.requestFocusSafely()
                        else -> currentBody.requestEntry(tvSeerrFocusSlots(latest, canLogin = false).keys.toList())
                    }
                }
                dismissedPicker = null
            }
        }
    }
    BackHandler(enabled = picker == null && (!rail.railHasFocus || state.detail != null || state.connection.authenticating)) {
        when {
            editing -> exitEditing++
            state.detail != null -> onIntent(SeerrIntent.Back)
            state.section == SeerrSection.Connection -> onIntent(SeerrIntent.SelectSection(SeerrSection.Discover))
            state.connection.authenticating -> onIntent(SeerrIntent.CancelAuthentication)
            !rail.requestRailFocus() -> onBack()
        }
    }
    Box(modifier.fillMaxSize().tvHomeAmbientBackground(hero.ambient.presentation, overlay = true, enabled = heroVisible)) {
        if (heroVisible) {
            TvHeroBackdrop(
                session,
                hero.item,
                Modifier.align(Alignment.TopEnd),
                publicArtworkEntry = hero.artworkEntry,
                onArtworkLoaded = hero.ambient.onArtworkLoaded,
            )
        }
        Column(
            Modifier.fillMaxSize().focusGroup(),
            verticalArrangement =
                Arrangement.spacedBy(
                    if (state.section ==
                        SeerrSection.Search
                    ) {
                        TvDimens.heroCompactGap
                    } else {
                        TvDimens.rowGap
                    },
                ),
        ) {
            if (detail == null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = startPadding, top = TvDimens.overscanVertical, end = TvDimens.overscanHorizontal)
                        .tvLibraryChromeVisibility(!rail.railHasFocus && !lowerContentFocused)
                        .onFocusChanged {
                            headerHasFocus = it.hasFocus
                            if (it.hasFocus) lowerContentFocused = false
                        }.tvFocusScope(header) {
                            listOf(
                                if (state.section ==
                                    SeerrSection.Connection
                                ) {
                                    SeerrSection.Discover.name
                                } else {
                                    state.section.name
                                },
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                ) {
                    FlowRow(
                        Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && requestBodyEntry?.invoke() == true
                        },
                        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                        verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                    ) {
                        val sections =
                            if (state.section ==
                                SeerrSection.Connection
                            ) {
                                listOf(SeerrSection.Discover)
                            } else {
                                listOf(SeerrSection.Discover, SeerrSection.Requests, SeerrSection.Search)
                            }
                        sections.forEachIndexed { index, section ->
                            val requester =
                                if (section == state.section ||
                                    state.section == SeerrSection.Connection
                                ) {
                                    selectedTab
                                } else {
                                    header.rememberChildRequester(section.name)
                                }
                            TvButton(
                                text =
                                    if (state.section ==
                                        SeerrSection.Connection
                                    ) {
                                        stringResource(Res.string.seerr_back)
                                    } else {
                                        seerrSectionText(section)
                                    },
                                selected = state.section == section,
                                enabled = true,
                                onClick = {
                                    if (state.ownerValid) {
                                        onIntent(
                                            SeerrIntent.SelectSection(section),
                                        )
                                    } else {
                                        rail.requestRailFocus()
                                    }
                                },
                                modifier =
                                    Modifier
                                        .focusRequester(requester)
                                        .onPreviewKeyEvent { event ->
                                            when {
                                                event.type != KeyEventType.KeyDown -> false
                                                event.key == Key.DirectionDown -> requestBodyEntry?.invoke() == true
                                                event.key == Key.DirectionRight && index == sections.lastIndex ->
                                                    (
                                                        if (pickerEligible) {
                                                            catalogRequester
                                                        } else if (state.connected &&
                                                            !state.restricted
                                                        ) {
                                                            refreshRequester
                                                        } else {
                                                            manageRequester
                                                        }
                                                    ).requestFocusSafely()
                                                else -> false
                                            }
                                        }.onFocusChanged {
                                            if (it.isFocused) {
                                                header.onChildFocused(
                                                    section.name,
                                                    TvFocusTargetKind.Tab,
                                                    index,
                                                )
                                            }
                                        }.onKeyEvent { event ->
                                            event.type == KeyEventType.KeyDown &&
                                                event.key == Key.DirectionLeft &&
                                                index == 0 &&
                                                rail.requestRailFocus()
                                        },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.section == SeerrSection.Discover) {
                            TvButton(seerrCatalogText(state.catalog), {
                                if (pickerEligible) picker = "catalog"
                            }, Modifier.focusRequester(catalogRequester).focusProperties { up = selectedTab }, enabled = pickerEligible)
                            TvButton(stringResource(Res.string.seerr_filters, if (state.hideAvailable) 1 else 0), {
                                if (pickerEligible) picker = "filters"
                            }, Modifier.focusRequester(filterRequester).focusProperties { up = selectedTab }, enabled = pickerEligible)
                        }
                        if (state.section != SeerrSection.Connection) {
                            if (state.connected && state.ownerValid && !state.restricted) {
                                TvButton(
                                    stringResource(Res.string.seerr_refresh),
                                    { if (!state.feed.loading) onIntent(SeerrIntent.Refresh) },
                                    Modifier.focusRequester(refreshRequester).focusProperties { up = selectedTab },
                                )
                            }
                            FocusableBox(
                                onClick = { onIntent(SeerrIntent.SelectSection(SeerrSection.Connection)) },
                                modifier =
                                    Modifier
                                        .size(
                                            TvDimens.minButtonHeight,
                                        ).focusRequester(manageRequester)
                                        .focusProperties { up = selectedTab },
                                contentDescription = stringResource(Res.string.seerr_manage),
                                backgroundColor = LocalJellyfinPalette.current.surfaceRaised,
                                contentAlignment = Alignment.Center,
                            ) { _ ->
                                Icon(TvIcons.Cog, contentDescription = null, tint = LocalJellyfinPalette.current.textPrimary)
                            }
                        }
                    }
                }
            }
            key(surface, state.detail?.entryId, state.catalog, state.submittedQuery, state.feed.resetRevision) {
                val position =
                    state.detail?.scroll ?: if (state.section == SeerrSection.Connection) SeerrScrollPosition() else state.feed.scroll
                val scroll = rememberLazyListState(position.index, position.offset)
                val gridScroll = rememberLazyGridState(position.index, position.offset)
                val isFeed =
                    state.detail == null &&
                        state.section != SeerrSection.Connection &&
                        state.ownerValid &&
                        !state.restricted &&
                        state.connected &&
                        !(state.section == SeerrSection.Requests && state.feed.loading && state.feed.rows.isEmpty())
                val seasonScroll = rememberLazyListState(detail?.seasonScroll?.index ?: 0, detail?.seasonScroll?.offset ?: 0)
                val castScroll = rememberLazyListState(detail?.castScroll?.index ?: 0, detail?.castScroll?.offset ?: 0)
                val relatedScroll = rememberLazyListState(detail?.related?.scroll?.index ?: 0, detail?.related?.scroll?.offset ?: 0)
                val coordinator = LocalTvFocusCoordinator.current
                val restore = coordinator?.readyRestore?.takeIf { it.path.scopes == body.scopes }
                val savedFocus = remember { state.detail?.focusedId ?: state.feed.selectedId ?: state.lastFocusedId }
                var identifier by remember(state.connection.credentialRevision, state.ownerValid) { mutableStateOf("") }
                var password by remember(state.connection.credentialRevision, state.ownerValid) { mutableStateOf("") }
                DisposableEffect(state.connection.credentialRevision, state.ownerValid) {
                    onDispose {
                        identifier = ""
                        password = ""
                    }
                }
                val slots = tvSeerrFocusSlots(state, identifier.isNotBlank() && password.isNotEmpty())
                val latestSlots by rememberUpdatedState(slots)
                val workScope = rememberCoroutineScope()
                var entered by remember { mutableStateOf(false) }
                var bodyOwned by remember { mutableStateOf(false) }
                var focusJob by remember { mutableStateOf<Job?>(null) }
                var focusJobGeneration by remember { mutableStateOf(-1L) }
                val availability = Triple(state.ownerValid, state.connected, state.restricted)
                var focusJobAvailability by remember { mutableStateOf(availability) }
                var focusedBodyId by remember { mutableStateOf<String?>(null) }
                val bodyIntent: (SeerrIntent) -> Unit = { intent ->
                    if (intent is SeerrIntent.Focused) {
                        focusedBodyId = intent.id
                        entered = true
                    }
                    onIntent(intent)
                }
                val savedIndex = remember { coordinator?.activePath?.takeIf { it.scopes == body.scopes }?.fallbackIndex ?: 0 }
                var localInputGeneration by remember { mutableStateOf(0L) }
                val currentLocalGeneration by rememberUpdatedState(localInputGeneration)

                suspend fun revealTarget(
                    preferred: String?,
                    fallbackIndex: Int = 0,
                ): Boolean {
                    val generation = rail.inputGeneration
                    val localGeneration = currentLocalGeneration
                    val keys = latestSlots.keys.toList()
                    val sameGroup =
                        preferred
                            ?.substringBefore(':')
                            ?.let { group ->
                                keys.filter { it.substringBefore(':') == group }
                            }.orEmpty()
                    val selectedSeason =
                        currentState.detail
                            ?.selectedSeasons
                            ?.firstOrNull()
                            ?.let { "season:$it" }
                    val target =
                        preferred?.takeIf { it in keys }
                            ?: selectedSeason?.takeIf { preferred?.startsWith("season:") == true && it in keys }
                            ?: sameGroup.getOrNull(fallbackIndex.coerceIn(0, (sameGroup.size - 1).coerceAtLeast(0)))
                            ?: keys.firstOrNull() ?: return false
                    val semanticKeys =
                        if (!isFeed &&
                            ':' in target
                        ) {
                            keys.filter { it.substringBefore(':') == target.substringBefore(':') }
                        } else {
                            keys
                        }
                    val index = semanticKeys.indexOf(target)
                    return body.restoreFocus(
                        FocusRestoreRequest(target, index),
                        semanticKeys,
                        { latestSlots.getValue(semanticKeys[it]) },
                        { slot ->
                            if (generation != rail.inputGeneration ||
                                localGeneration != currentLocalGeneration
                            ) {
                                throw CancellationException("Focus ownership changed")
                            }
                            if (target != "loading") {
                                if (isFeed) gridScroll.scrollToItem(slot) else scroll.scrollToItem(slot)
                            }
                            val d = currentState.detail
                            when {
                                target.startsWith("season:") ->
                                    seasonScroll.scrollToItem(
                                        d?.seasons?.indexOfFirst { "season:${it.season.number}" == target }?.coerceAtLeast(0) ?: 0,
                                    )
                                target.startsWith("cast:") ->
                                    castScroll.scrollToItem(
                                        target.substringAfter(':').substringBefore(':').toIntOrNull() ?: 0,
                                    )
                                target.startsWith("similar:") ->
                                    relatedScroll.scrollToItem(
                                        d
                                            ?.related
                                            ?.rows
                                            ?.indexOfFirst { "similar:${it.id}" == target }
                                            ?.coerceAtLeast(0) ?: 0,
                                    )
                            }
                        },
                    )
                }
                val entryAction: () -> Boolean = {
                    val generation = rail.inputGeneration
                    val localGeneration = localInputGeneration
                    focusJob?.cancel()
                    focusJobGeneration = rail.inputGeneration
                    focusJobAvailability = availability
                    focusJob =
                        workScope.launch {
                            val preferred = currentState.detail?.focusedId ?: currentState.feed.selectedId ?: savedFocus
                            val focused = revealTarget(preferred, savedIndex)
                            if (focused) {
                                entered = true
                            } else if (generation == rail.inputGeneration &&
                                localGeneration == currentLocalGeneration
                            ) {
                                if (currentState.detail != null) {
                                    revealTarget(null)
                                } else {
                                    currentTab.requestFocusSafely()
                                }
                            }
                        }
                    true
                }
                LaunchedEffect(
                    rail.inputGeneration,
                    headerHasFocus,
                    rail.railHasFocus,
                    picker,
                    state.detail?.modalVisible,
                    state.ownerValid,
                    state.connected,
                    state.restricted,
                ) {
                    if (focusJobGeneration != rail.inputGeneration ||
                        picker != null ||
                        (state.detail?.modalVisible == true) ||
                        focusJobAvailability != availability
                    ) {
                        focusJob?.cancel()
                    }
                    if (headerHasFocus || rail.railHasFocus || picker != null || (state.detail?.modalVisible == true)) bodyOwned = false
                }
                DisposableEffect(body, lifecycleOwner) {
                    val observer =
                        LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                                focusJob?.cancel()
                                pendingEntry = false
                                localInputGeneration++
                            }
                        }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        focusJob?.cancel()
                    }
                }
                DisposableEffect(body, slots) {
                    requestBodyEntry = entryAction
                    onDispose { requestBodyEntry = null }
                }
                LaunchedEffect(
                    pendingEntry,
                    entryGeneration,
                    rail.inputGeneration,
                    body,
                    state.detail?.modalVisible,
                    picker,
                    surface,
                    slots,
                ) {
                    if (!pendingEntry) return@LaunchedEffect
                    if (entryGeneration != rail.inputGeneration ||
                        entrySurface != surface ||
                        picker != null ||
                        (state.detail?.modalVisible == true)
                    ) {
                        pendingEntry = false
                        return@LaunchedEffect
                    }
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        val focused = revealTarget(currentState.detail?.focusedId ?: currentState.feed.selectedId ?: savedFocus)
                        pendingEntry = false
                        if (focused) {
                            entered = true
                        } else if (entryGeneration == rail.inputGeneration) {
                            rail.requestRailFocus()
                        }
                    }
                }
                LaunchedEffect(
                    restore?.token,
                    state.detail?.loading,
                    state.feed.loading,
                    slots,
                    rail.inputGeneration,
                    localInputGeneration,
                ) {
                    if (entered ||
                        pendingEntry ||
                        picker != null ||
                        headerHasFocus ||
                        rail.railHasFocus ||
                        rail.contentAutofocusSuppressed ||
                        (state.detail?.modalVisible == true)
                    ) {
                        return@LaunchedEffect
                    }
                    val preferred = restore?.path?.targetKey ?: savedFocus
                    entered = revealTarget(preferred, restore?.path?.fallbackIndex ?: savedIndex)
                }
                LaunchedEffect(slots, state.detail?.loading, state.feed.loading, rail.inputGeneration, localInputGeneration) {
                    val focusedId = focusedBodyId
                    if (bodyOwned &&
                        !pendingEntry &&
                        picker == null &&
                        (currentState.detail?.modalVisible != true) &&
                        !headerHasFocus &&
                        !rail.railHasFocus &&
                        focusedId != null &&
                        focusedId !in slots &&
                        currentState.detail?.loading != true &&
                        !currentState.feed.loading
                    ) {
                        revealTarget(focusedId, coordinator?.activePath?.fallbackIndex ?: savedIndex)
                    }
                }
                LaunchedEffect(scroll, gridScroll, isFeed) {
                    if (state.section != SeerrSection.Connection) {
                        snapshotFlow {
                            if (isFeed) {
                                if (gridScroll.isScrollInProgress) {
                                    null
                                } else {
                                    SeerrScrollPosition(
                                        gridScroll.firstVisibleItemIndex,
                                        gridScroll.firstVisibleItemScrollOffset,
                                    )
                                }
                            } else {
                                if (scroll.isScrollInProgress) {
                                    null
                                } else {
                                    SeerrScrollPosition(
                                        scroll.firstVisibleItemIndex,
                                        scroll.firstVisibleItemScrollOffset,
                                    )
                                }
                            }
                        }.filterNotNull().distinctUntilChanged().collect {
                            onIntent(
                                SeerrIntent.RememberScroll(!isFeed, it, state.section),
                            )
                        }
                    }
                }
                val padding =
                    PaddingValues(
                        start = startPadding,
                        end = TvDimens.overscanHorizontal,
                        top = if (detail != null) TvDimens.overscanVertical + TvDimens.heroTopInset else TvDimens.gridContentTopPadding,
                        bottom = TvDimens.overscanVertical,
                    )
                val bodyModifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                localInputGeneration++
                                focusJob?.cancel()
                                pendingEntry = false
                            }
                            false
                        }.onFocusChanged { if (it.hasFocus) bodyOwned = true }
                        .tvFocusScope(body) { slots.keys.toList() }
                when {
                    !state.ownerValid || (state.section != SeerrSection.Connection && (state.restricted || !state.connected)) -> {
                        LazyColumn(
                            state = scroll,
                            modifier = bodyModifier,
                            contentPadding = padding,
                            verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
                        ) {
                            item {
                                DetailText(
                                    stringResource(
                                        when {
                                            !state.ownerValid -> Res.string.seerr_owner_changed
                                            state.restricted -> Res.string.seerr_restricted
                                            else -> Res.string.seerr_setup
                                        },
                                    ),
                                )
                                state.connection.error?.let { DetailText(seerrErrorText(it)) }
                            }
                            item {
                                TvButton(
                                    stringResource(if (state.ownerValid) Res.string.seerr_manage else Res.string.seerr_back),
                                    {
                                        if (state.ownerValid) {
                                            onIntent(
                                                SeerrIntent.SelectSection(SeerrSection.Connection),
                                            )
                                        } else {
                                            rail.requestRailFocus()
                                        }
                                    },
                                    modifier =
                                        seerrFocus(
                                            body,
                                            if (state.ownerValid) "manage" else "owner-return",
                                            0,
                                            onIntent,
                                            rail::requestRailFocus,
                                        ),
                                )
                            }
                        }
                    }
                    state.section == SeerrSection.Connection ->
                        TvSeerrConnection(
                            state.connection,
                            identifier,
                            { identifier = it },
                            password,
                            { password = it },
                            bodyIntent,
                            body,
                            scroll,
                            padding,
                            bodyModifier,
                            exitEditing,
                            { editing = it },
                            rail::requestRailFocus,
                        )
                    (detail?.loading == true && detail.detail == null) ||
                        (detail == null && state.section == SeerrSection.Requests && state.feed.loading && state.feed.rows.isEmpty()) -> {
                        val loading = body.rememberChildRequester("loading")
                        TvLoadingFocusPark(
                            focusRequester = loading,
                            onRequestRailFocus = rail::requestRailFocus,
                            requestFocusOnAttach =
                                !rail.contentAutofocusSuppressed &&
                                    !rail.railHasFocus &&
                                    !pendingEntry &&
                                    (detail != null || !headerHasFocus),
                            modifier =
                                bodyModifier
                                    .onPreviewKeyEvent { event ->
                                        detail == null &&
                                            event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionUp &&
                                            selectedTab.requestFocusSafely()
                                    }.onFocusChanged {
                                        if (it.hasFocus) {
                                            body.onChildFocused("loading", TvFocusTargetKind.Loading, 0)
                                            bodyIntent(SeerrIntent.Focused("loading"))
                                        }
                                    },
                        )
                    }
                    detail != null ->
                        TvSeerrDetail(
                            detail,
                            session,
                            bodyIntent,
                            body,
                            scroll,
                            seasonScroll,
                            castScroll,
                            relatedScroll,
                            padding,
                            bodyModifier,
                            rail::requestRailFocus,
                            { target ->
                                focusJob?.cancel()
                                focusJobGeneration = rail.inputGeneration
                                focusJobAvailability = availability
                                focusJob = workScope.launch { revealTarget(target) }
                                true
                            },
                        )
                    else ->
                        TvSeerrFeed(
                            state,
                            session,
                            bodyIntent,
                            body,
                            gridScroll,
                            padding,
                            bodyModifier,
                            exitEditing,
                            { editing = it },
                            rail::requestRailFocus,
                            selectedTab,
                            { lowerContentFocused = it },
                            headerHasFocus || rail.railHasFocus || picker != null || (state.detail?.modalVisible == true),
                            refreshRequester,
                            hero,
                            { focusedHeroItem = it },
                        )
                }
            }
        }
    }
    state.detail?.takeIf { it.reviewVisible }?.let { TvSeerrConfirmation(it, onIntent) }
    state.detail?.takeIf { it.libraryPickerVisible || it.qualityPickerVisible }?.let { TvSeerrQualityPicker(it, onIntent) }
}

/** Lazy slots are layout positions, separate from the stable focus keys. */
private fun tvSeerrFocusSlots(
    state: SeerrUiState,
    canLogin: Boolean,
): Map<String, Int> =
    buildMap {
        val detail = state.detail
        if (!state.ownerValid) {
            put("owner-return", 1)
        } else if (state.section == SeerrSection.Connection) {
            val connection = state.connection
            val service = connection.connection.service
            if (!connection.busy) {
                if (connection.connection.phase == SeerrConnectionPhase.Connected ||
                    connection.connection.phase == SeerrConnectionPhase.VerificationRequired
                ) {
                    put("verify", 0)
                    put("disconnect", 0)
                } else {
                    put("url", 0)
                    if (connection.url.isNotBlank()) put("validate", 0)
                    if (service != null) {
                        if (service.jellyfinLoginEnabled) put("jellyfin-login", 0)
                        if (service.localLoginEnabled) put("local-login", 0)
                        if (service.jellyfinLoginEnabled || service.localLoginEnabled) {
                            put("identifier", 0)
                            put("password", 0)
                            if (canLogin) put("login", 0)
                        }
                        if (service.quickConnectSupported) put("quick-connect", 0)
                    }
                }
            }
            put("cancel-auth", 0)
        } else if (state.restricted || !state.connected || !state.ownerValid) {
            put("manage", 1)
        } else if ((detail?.loading == true && detail.detail == null) ||
            (detail == null && state.section == SeerrSection.Requests && state.feed.loading && state.feed.rows.isEmpty())
        ) {
            put("loading", 0)
        } else if (detail != null) {
            if ((detail.error != null || !detail.admissionFresh) &&
                !detail.loading &&
                !detail.submitting &&
                !detail.reconciling
            ) {
                put("recovery", 0)
            }
            if (detail.loading) put("loading", 0)
            val value = detail.detail
            if (value != null) {
                if (detail.reviewEnabled) put("request", 0)
                if (detail.libraryAvailable &&
                    detail.admissionFresh &&
                    !detail.loading &&
                    !detail.submitting &&
                    !detail.resolvingLibrary &&
                    !detail.reconciling
                ) {
                    put("library", 0)
                }
                if (detail.selectionEnabled && detail.requestQualities.size > 1) {
                    put("quality", 0)
                }
                var slot = 1
                if (detail.seasons.isNotEmpty()) {
                    detail.seasons.forEach { put("season:${it.season.number}", slot) }
                    slot++
                }
                if (detail.uncertain) {
                    if (!detail.loading && !detail.reconciling && !detail.submitting) put("reconcile", slot)
                    slot++
                }
                if (value.cast.isNotEmpty()) {
                    value.cast.forEachIndexed { index, member -> put("cast:$index:${member.id}", slot) }
                    slot++
                }
                if (detail.relatedEnabled) {
                    if (detail.related.phase == SeerrRelatedPhase.Error ||
                        detail.related.phase == SeerrRelatedPhase.Interrupted
                    ) {
                        put("related-retry", slot)
                    }
                    detail.related.rows.forEach { put("similar:${it.id}", slot) }
                }
            }
        } else {
            state.feed.rows.forEachIndexed { index, row -> put(row.id, index) }
            if (state.section == SeerrSection.Search) put("search", 0)
            if (state.feed.loading && state.feed.rows.isEmpty()) put("feed-loading", 0)
            if (state.feed.error != null) put("feed-retry", state.feed.rows.size)
            if (!state.feed.loading && state.feed.error == null && state.feed.rows.isEmpty()) put("feed-empty", 0)
        }
    }

@Composable
private fun seerrFocus(
    scope: TvFocusScopeNode,
    id: String,
    index: Int,
    onIntent: (SeerrIntent) -> Unit,
    onLeft: () -> Boolean,
    kind: TvFocusTargetKind = TvFocusTargetKind.Action,
): Modifier {
    val requester = scope.rememberChildRequester(id)
    return Modifier
        .focusRequester(requester)
        .onFocusChanged {
            if (it.isFocused) {
                scope.onChildFocused(id, kind, index)
                onIntent(SeerrIntent.Focused(id))
            }
        }.onKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && onLeft() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvSeerrFeed(
    state: SeerrUiState,
    session: Session,
    onIntent: (SeerrIntent) -> Unit,
    scope: TvFocusScopeNode,
    scroll: LazyGridState,
    padding: PaddingValues,
    modifier: Modifier,
    exitEditing: Int,
    onEditing: (Boolean) -> Unit,
    onLeft: () -> Boolean,
    selectedTab: FocusRequester,
    onLowerContentFocused: (Boolean) -> Unit,
    entryBlocked: Boolean,
    refreshRequester: FocusRequester,
    hero: TvBrowseHeroState,
    onMediaFocused: (MediaCardUi) -> Unit,
) {
    val feed = state.feed
    val heroVisible = hero.enabled && feed.rows.isNotEmpty()
    var requestedSize by remember { mutableStateOf<Int?>(null) }
    var requestedPage by remember { mutableStateOf(0) }
    var noGrowth by remember { mutableStateOf(false) }
    LaunchedEffect(feed.loading, feed.page, feed.error, feed.rows.size) {
        val size = requestedSize
        if (!feed.loading && size != null && (feed.page != requestedPage || feed.error != null)) {
            noGrowth = feed.error == null && feed.rows.size <= size
            requestedSize = null
        }
    }

    fun loadMore(deliberate: Boolean = false) {
        if (feed.loading || requestedSize != null || !feed.hasMore || (!deliberate && (feed.error != null || noGrowth))) return
        requestedSize = feed.rows.size
        requestedPage = feed.page
        onIntent(SeerrIntent.LoadMore)
    }
    val columns = rememberStableGridColumnCount(scroll)
    val nav =
        rememberTvGridFocusNav(
            scroll,
            feed.rows.size,
            columns,
            hasMore = feed.hasMore && feed.error == null && !noGrowth && requestedSize == null,
            revealComposedTargets = true,
            onLoadMore = { loadMore() },
        )
    val retryRequester = scope.rememberChildRequester("feed-retry")
    val searchRequester = scope.rememberChildRequester("search")
    val aboveGrid = if (state.section == SeerrSection.Search) searchRequester else selectedTab
    var searchEditing by remember { mutableStateOf(false) }
    val workScope = rememberCoroutineScope()
    val rail = LocalTvHostedRailController.current
    var footerJob by remember { mutableStateOf<Job?>(null) }
    var footerGeneration by remember { mutableStateOf(-1L) }
    var focusedIndex by remember { mutableStateOf(0) }
    var gridHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(heroVisible) {
        if (gridHasFocus && feed.rows.isNotEmpty()) nav.requestFocus(focusedIndex)
    }
    var footerFocused by remember { mutableStateOf(false) }
    var footerLoadGeneration by remember { mutableStateOf<Long?>(null) }
    var footerReturnIndex by remember { mutableStateOf(0) }
    val recoveryVisible = feed.error != null || (feed.hasMore && (noGrowth || feed.rows.isEmpty()))
    val footerVisible = recoveryVisible || footerLoadGeneration != null
    LaunchedEffect(feed.loading, feed.page, feed.error, recoveryVisible, requestedSize, entryBlocked, rail.inputGeneration) {
        val generation = footerLoadGeneration ?: return@LaunchedEffect
        if (entryBlocked || generation != rail.inputGeneration) {
            footerLoadGeneration = null
            return@LaunchedEffect
        }
        if (feed.loading || requestedSize != null) return@LaunchedEffect
        footerLoadGeneration = null
        if (!footerFocused || recoveryVisible || feed.error != null) return@LaunchedEffect
        if (feed.rows.isNotEmpty()) {
            nav.requestFocus(footerReturnIndex.coerceIn(0, feed.rows.lastIndex))
        } else {
            refreshRequester.requestFocusSafely()
        }
    }

    fun focusFooter(): Boolean {
        nav.cancelNavigation()
        footerJob?.cancel()
        footerGeneration = rail.inputGeneration
        footerJob =
            workScope.launch {
                scroll.scrollToItem(feed.rows.size)
                requestTvFocusWithRetry { retryRequester.requestFocusSafely() }
            }
        return true
    }
    LaunchedEffect(rail.inputGeneration, rail.railHasFocus) {
        if (footerGeneration != rail.inputGeneration ||
            rail.railHasFocus
        ) {
            footerJob?.cancel()
        }
    }
    DisposableEffect(nav) { onDispose { footerJob?.cancel() } }
    LoadMoreOnApproachEnd(
        itemCount = feed.rows.size,
        hasMore = feed.hasMore,
        isLoading = feed.loading && feed.rows.isEmpty(),
        isLoadingMore = feed.loading,
        automaticPagingAllowed = feed.error == null && !noGrowth && requestedSize == null,
        lastVisibleIndex = { scroll.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0 },
        onLoadMore = { loadMore() },
    )
    Column(modifier) {
        if (state.section == SeerrSection.Search) {
            Column(
                Modifier.padding(
                    start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    top = TvDimens.heroCompactGap,
                    bottom = TvDimens.heroCompactGap,
                ),
                verticalArrangement = Arrangement.spacedBy(TvDimens.heroCompactGap),
            ) {
                TvFindHeader(
                    queryText = state.query,
                    onQueryTextChanged = { onIntent(SeerrIntent.QueryChanged(it)) },
                    onClearQuery = {
                        searchRequester.requestFocusSafely()
                        onIntent(SeerrIntent.ClearSearch)
                    },
                    searchFieldRequester = searchRequester,
                    onSearchFieldEditingChange = {
                        searchEditing = it
                        onEditing(it)
                    },
                    onImeSearch = { onIntent(SeerrIntent.SubmitSearch) },
                    onEditingCommitted = {},
                    showTitle = false,
                    showSubmitButton = true,
                    clearEnabled = state.query.isNotEmpty() || state.submittedQuery.isNotEmpty(),
                    exitEditingRequests = exitEditing,
                    searchFieldModifier =
                        Modifier.onKeyEvent { event ->
                            !searchEditing && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && onLeft()
                        },
                    modifier =
                        Modifier
                            .onFocusChanged {
                                if (it.hasFocus) {
                                    onLowerContentFocused(false)
                                    scope.onChildFocused("search", TvFocusTargetKind.Action, 0)
                                    onIntent(SeerrIntent.Focused("search"))
                                }
                            }.focusGroup()
                            .onPreviewKeyEvent { event ->
                                if (searchEditing || event.type != KeyEventType.KeyDown) {
                                    false
                                } else {
                                    when (event.key) {
                                        Key.DirectionUp -> selectedTab.requestFocusSafely()
                                        Key.DirectionDown -> {
                                            if (feed.rows.isNotEmpty()) {
                                                nav.requestFocus(focusedIndex)
                                                true
                                            } else {
                                                scope.requestEntry(listOf("feed-retry", "feed-loading", "feed-empty"))
                                            }
                                        }
                                        else -> false
                                    }
                                }
                            },
                )
                DetailText(
                    stringResource(
                        if (state.submittedQuery.isEmpty()) Res.string.seerr_search_prompt else Res.string.seerr_search_results,
                        state.submittedQuery,
                    ),
                    style = TvHeroOverviewStyle.copy(lineHeight = TvDimens.heroCompactMetadataLineHeight),
                    maxLines = 1,
                )
            }
        }
        if (heroVisible) {
            TvLibraryGridHero(
                session,
                hero.item,
                contentStartPadding = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                showBackdrop = false,
                reserveGridViewport = true,
                gridTopReserve = TvDimens.gridContentTopPadding,
                gridBottomReserve = TvDimens.overscanVertical,
                catalogMetadata = true,
            )
        }
        // The indexed grid owns every vertical reveal, including already-composed
        // rows outside the smaller viewport beneath the search controls.
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberNoAutoScrollSpec()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TvDimens.posterWidth.tileScaled()),
                state = scroll,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onFocusChanged {
                            gridHasFocus = it.hasFocus
                            if (!it.hasFocus) {
                                nav.cancelNavigation()
                                footerJob?.cancel()
                            }
                        }.onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                footerFocused && event.key == Key.DirectionUp -> {
                                    if (feed.rows.isEmpty()) {
                                        aboveGrid.requestFocusSafely()
                                    } else {
                                        nav.requestFocus(focusedIndex)
                                        true
                                    }
                                }
                                footerVisible &&
                                    !footerFocused &&
                                    event.key == Key.DirectionDown &&
                                    focusedIndex / columns == feed.rows.lastIndex / columns -> focusFooter()
                                else -> nav.onPreviewKeyEvent(event)
                            }
                        },
                contentPadding =
                    PaddingValues(
                        start =
                            padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) +
                                TvDimens.gridContentHorizontalPadding,
                        end = TvDimens.overscanHorizontal + TvDimens.gridContentHorizontalPadding,
                        top = if (heroVisible) TvDimens.gridContentTopPadding else TvDimens.libraryGridContentTopReserve,
                        bottom = TvDimens.overscanVertical,
                    ),
                verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
            ) {
                itemsIndexed(feed.rows, key = { _, row -> row.id }, contentType = { _, _ -> "poster" }) { index, row ->
                    val requester = nav.rememberItemFocusRequester(index)
                    DisposableEffect(scope, row.id, requester) {
                        scope.register(row.id, requester)
                        onDispose { scope.unregister(row.id, requester) }
                    }
                    TvMediaCard(
                        session = session,
                        item =
                            row.card.copy(
                                title = row.media.title ?: stringResource(Res.string.seerr_untitled),
                                subtitle =
                                    row.media.year
                                        ?.toString()
                                        .orEmpty(),
                            ),
                        wide = false,
                        focusRequester = requester,
                        modifier =
                            Modifier.onPreviewKeyEvent { event ->
                                when {
                                    event.type != KeyEventType.KeyDown -> false
                                    event.key == Key.DirectionUp && index < columns -> aboveGrid.requestFocusSafely()
                                    event.key == Key.DirectionLeft && index % columns == 0 -> onLeft()
                                    event.key == Key.DirectionRight && (index == feed.rows.lastIndex || (index + 1) % columns == 0) ->
                                        if (footerVisible && index == feed.rows.lastIndex) focusFooter() else true
                                    else -> false
                                }
                            },
                        onFocused = {
                            footerFocused = false
                            footerLoadGeneration = null
                            focusedIndex = index
                            onMediaFocused(row.card)
                            nav.onItemFocused(index)
                            scope.onChildFocused(row.id, TvFocusTargetKind.Item, index)
                            onLowerContentFocused(index >= columns)
                            onIntent(SeerrIntent.Focused(row.id))
                        },
                        onClick = {
                            onIntent(
                                SeerrIntent.RememberScroll(
                                    false,
                                    SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                                ),
                            )
                            onIntent(SeerrIntent.OpenDetail(row))
                        },
                        artwork = { SeerrPoster(row.media, Modifier.fillMaxSize()) },
                    )
                }
                if (feed.rows.isEmpty() && feed.error == null) {
                    item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                            if (feed.loading) {
                                FocusableBox(
                                    null,
                                    seerrFocus(scope, "feed-loading", 0, onIntent, onLeft).focusProperties { up = aboveGrid },
                                ) {
                                    DetailText(stringResource(Res.string.seerr_loading))
                                }
                            } else if (feed.error == null) {
                                FocusableBox(
                                    null,
                                    seerrFocus(scope, "feed-empty", 0, onIntent, onLeft).focusProperties { up = aboveGrid },
                                ) {
                                    DetailText(
                                        stringResource(
                                            when {
                                                state.section == SeerrSection.Requests -> Res.string.seerr_requests_empty
                                                state.hideAvailable && state.section == SeerrSection.Discover ->
                                                    Res.string.seerr_filtered_empty
                                                else -> Res.string.seerr_empty
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                if (footerVisible) {
                    item(key = "recovery", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                            feed.error?.let { DetailText(seerrErrorText(it), color = LocalJellyfinPalette.current.error) }
                            TvButton(
                                stringResource(
                                    if (feed.loading) {
                                        Res.string.seerr_loading
                                    } else if (feed.error !=
                                        null
                                    ) {
                                        Res.string.seerr_retry
                                    } else {
                                        Res.string.seerr_load_more
                                    },
                                ),
                                {
                                    if (!feed.loading) {
                                        footerLoadGeneration = rail.inputGeneration
                                        footerReturnIndex = focusedIndex
                                        if (feed.error != null && feed.rows.isEmpty() && feed.page == 0) {
                                            onIntent(SeerrIntent.Refresh)
                                        } else {
                                            loadMore(deliberate = true)
                                        }
                                    }
                                },
                                Modifier
                                    .focusRequester(retryRequester)
                                    .focusProperties { if (feed.rows.isEmpty()) up = aboveGrid }
                                    .onKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && onLeft() }
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            footerFocused = true
                                            onLowerContentFocused(false)
                                        }
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvSeerrDetail(
    state: SeerrDetailUi,
    session: Session,
    onIntent: (SeerrIntent) -> Unit,
    scope: TvFocusScopeNode,
    scroll: LazyListState,
    seasonScroll: LazyListState,
    castScroll: LazyListState,
    relatedScroll: LazyListState,
    padding: PaddingValues,
    modifier: Modifier,
    onLeft: () -> Boolean,
    reveal: (String) -> Boolean,
) {
    val detail = state.detail
    val focusedByGroup = remember { mutableMapOf<String, String>() }

    fun trackedIntent(intent: SeerrIntent) {
        if (intent is SeerrIntent.Focused) {
            val group = if (':' in intent.id) intent.id.substringBefore(':') else "actions"
            focusedByGroup[group] = intent.id
        }
        onIntent(intent)
    }

    fun groupEntry(keys: List<String>): String {
        val first = keys.first()
        val group = if (':' in first) first.substringBefore(':') else "actions"
        return focusedByGroup[group]?.takeIf { it in keys }
            ?: state.selectedSeasons
                .firstOrNull()
                ?.let { "season:$it" }
                ?.takeIf { it in keys }
            ?: first
    }
    val groups =
        buildList {
            val actions =
                buildList {
                    if (state.loading) add("loading")
                    if (state.reviewEnabled) add("request")
                    if (state.libraryAvailable && state.selectionEnabled && !state.resolvingLibrary) add("library")
                    if (state.selectionEnabled && state.requestQualities.size > 1) {
                        add("quality")
                    }
                    if ((state.error != null || !state.admissionFresh) &&
                        !state.loading &&
                        !state.submitting &&
                        !state.reconciling
                    ) {
                        add("recovery")
                    }
                }
            if (actions.isNotEmpty()) add(actions)
            if (state.seasons.isNotEmpty()) add(state.seasons.map { "season:${it.season.number}" })
            if (state.uncertain && !state.loading && !state.reconciling && !state.submitting) add(listOf("reconcile"))
            if (detail?.cast?.isNotEmpty() == true) add(detail.cast.mapIndexed { index, member -> "cast:$index:${member.id}" })
            if (state.relatedEnabled) {
                val similar =
                    buildList {
                        if (state.related.phase == SeerrRelatedPhase.Error ||
                            state.related.phase == SeerrRelatedPhase.Interrupted
                        ) {
                            add("related-retry")
                        }
                        addAll(state.related.rows.map { "similar:${it.id}" })
                    }
                if (similar.isNotEmpty()) add(similar)
            }
        }

    fun boundary(id: String): Modifier =
        Modifier.onPreviewKeyEvent { event ->
            val group = groups.indexOfFirst { id in it }
            if (event.type != KeyEventType.KeyDown || group < 0) {
                false
            } else {
                when (event.key) {
                    Key.DirectionLeft -> id == state.seasons.firstOrNull()?.let { "season:${it.season.number}" }
                    Key.DirectionUp -> groups.getOrNull(group - 1)?.let { reveal(groupEntry(it)) } ?: true
                    Key.DirectionDown -> groups.getOrNull(group + 1)?.let { reveal(groupEntry(it)) } ?: true
                    else -> false
                }
            }
        }
    LaunchedEffect(seasonScroll) {
        snapshotFlow { SeerrScrollPosition(seasonScroll.firstVisibleItemIndex, seasonScroll.firstVisibleItemScrollOffset) }
            .distinctUntilChanged()
            .collect { onIntent(SeerrIntent.RememberSeasonScroll(it)) }
    }
    LaunchedEffect(castScroll) {
        snapshotFlow { SeerrScrollPosition(castScroll.firstVisibleItemIndex, castScroll.firstVisibleItemScrollOffset) }
            .distinctUntilChanged()
            .collect { onIntent(SeerrIntent.RememberCastScroll(it)) }
    }
    LaunchedEffect(relatedScroll) {
        snapshotFlow { SeerrScrollPosition(relatedScroll.firstVisibleItemIndex, relatedScroll.firstVisibleItemScrollOffset) }
            .distinctUntilChanged()
            .collect { onIntent(SeerrIntent.RememberRelatedScroll(it)) }
    }
    SeerrDetailSurface(session, state, modifier, isTv = true) {
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(TvDimens.detailSectionGap),
        ) {
            item(key = "metadata") {
                Column(verticalArrangement = Arrangement.spacedBy(TvDimens.detailMetaGap)) {
                    if (detail != null) {
                        SeerrDetailHero(
                            detail,
                            {},
                            showBack = false,
                            isTv = true,
                            passiveQuality = state.quality.takeIf { state.requestQualities.size <= 1 },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                                TvButton(
                                    stringResource(Res.string.seerr_request),
                                    { onIntent(SeerrIntent.ConfirmRequest) },
                                    seerrFocus(scope, "request", 0, ::trackedIntent, onLeft).then(boundary("request")),
                                    enabled = state.reviewEnabled,
                                )
                                if (state.libraryAvailable) {
                                    TvButton(
                                        stringResource(Res.string.seerr_open_library),
                                        {
                                            onIntent(
                                                SeerrIntent.RememberScroll(
                                                    true,
                                                    SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                                                ),
                                            )
                                            onIntent(SeerrIntent.OpenLibrary)
                                        },
                                        seerrFocus(scope, "library", 0, ::trackedIntent, onLeft).then(boundary("library")),
                                        enabled =
                                            state.admissionFresh &&
                                                !state.loading &&
                                                !state.submitting &&
                                                !state.resolvingLibrary &&
                                                !state.reconciling,
                                    )
                                }
                                if (state.requestQualities.size > 1) {
                                    TvButton(
                                        stringResource(Res.string.seerr_quality_choice, seerrQualityText(state.quality)),
                                        { onIntent(SeerrIntent.ChooseQuality) },
                                        seerrFocus(scope, "quality", 0, ::trackedIntent, onLeft).then(boundary("quality")),
                                        enabled = state.selectionEnabled,
                                    )
                                }
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                            verticalArrangement = Arrangement.spacedBy(TvDimens.detailMetaGap),
                        ) {
                            SeerrAvailabilityFact(SeerrQuality.Standard, detail.media.standardAvailability)
                            SeerrAvailabilityFact(SeerrQuality.FourK, detail.media.fourKAvailability)
                            DetailText(seerrQuotaText(detail.quota))
                            detail.requests.forEach { request ->
                                DetailText(
                                    listOfNotNull(
                                        seerrRequestLine(request),
                                        request.seasons.takeIf { it.isNotEmpty() }?.let {
                                            stringResource(Res.string.seerr_season_selection, seerrSeasonNumbersText(it))
                                        },
                                    ).joinToString(" · "),
                                )
                            }
                        }
                        state.option?.blockedBy?.let { DetailText(seerrErrorText(it)) }
                        if (!state.canSubmit &&
                            state.selectedSeasons.isNotEmpty() &&
                            detail.quota.kind == SeerrQuotaKind.Limited &&
                            !detail.quota.allows(state.selectedSeasons.size)
                        ) {
                            DetailText(stringResource(Res.string.seerr_quota_exceeded))
                        }
                    }
                    if ((state.error != null || !state.admissionFresh) && !state.loading && !state.submitting && !state.reconciling) {
                        TvButton(
                            stringResource(Res.string.seerr_retry),
                            { onIntent(SeerrIntent.Refresh) },
                            seerrFocus(scope, "recovery", 0, ::trackedIntent, onLeft).then(boundary("recovery")),
                        )
                    }
                    if (state.loading) {
                        FocusableBox(
                            null,
                            seerrFocus(scope, "loading", 0, ::trackedIntent, onLeft),
                        ) { DetailText(stringResource(Res.string.seerr_loading)) }
                    }
                    state.error?.let { DetailText(seerrErrorText(it), color = LocalJellyfinPalette.current.error) }
                    state.notice?.let {
                        DetailText(
                            stringResource(
                                if (it ==
                                    SeerrNotice.Created
                                ) {
                                    Res.string.seerr_request_created
                                } else {
                                    Res.string.seerr_reconciled
                                },
                            ),
                        )
                    }
                }
            }
            if (detail != null && state.seasons.isNotEmpty()) {
                item(key = "seasons", contentType = "ribbon") {
                    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.detailShelfTitleGap)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                            DetailText(stringResource(Res.string.seerr_seasons), style = DetailTitleStyle)
                            DetailText(stringResource(Res.string.seerr_choose_season))
                        }
                        LazyRow(
                            state = seasonScroll,
                            contentPadding = PaddingValues(TvDimens.itemGap),
                            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                        ) {
                            itemsIndexed(state.seasons, key = { _, row -> "season:${row.season.number}" }) { index, row ->
                                val id = "season:${row.season.number}"
                                val label = seerrSeasonText(row.season)
                                val reason = row.blockedBy?.let { seerrErrorText(it) }
                                Column(
                                    Modifier.width(TvDimens.seerrSeasonWidth.tileScaled()),
                                    verticalArrangement = Arrangement.spacedBy(TvDimens.detailMetaGap),
                                ) {
                                    FocusableBox(
                                        onClick = { onIntent(SeerrIntent.ToggleSeason(row.season.number)) },
                                        modifier =
                                            seerrFocus(
                                                scope,
                                                id,
                                                index,
                                                ::trackedIntent,
                                                if (index ==
                                                    0
                                                ) {
                                                    onLeft
                                                } else {
                                                    ({ false })
                                                },
                                            ).then(boundary(id)).fillMaxWidth().aspectRatio(Dimensions.seerrSeasonArtworkAspectRatio),
                                        enabled = state.reviewEnabled,
                                        focusableWhenDisabled = true,
                                        contentDescription =
                                            listOfNotNull(
                                                label,
                                                seerrAvailabilityLine(state.quality, row.availability),
                                                reason,
                                            ).joinToString(". "),
                                    ) {
                                        SeerrSeasonArtwork(row.artworkUrl, row.selected, Modifier.fillMaxSize())
                                    }
                                    DetailText(label)
                                    SeerrAvailabilityFact(SeerrQuality.Standard, row.season.standardAvailability)
                                    SeerrAvailabilityFact(SeerrQuality.FourK, row.season.fourKAvailability)
                                }
                            }
                        }
                    }
                }
            }
            if (detail != null && state.uncertain) {
                item(key = "reconcile") {
                    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                        DetailText(stringResource(Res.string.seerr_uncertain))
                        TvButton(
                            stringResource(Res.string.seerr_reconcile),
                            { onIntent(SeerrIntent.Reconcile) },
                            seerrFocus(scope, "reconcile", 0, ::trackedIntent, onLeft).then(boundary("reconcile")),
                            enabled = !state.loading && !state.reconciling && !state.submitting,
                        )
                    }
                }
            }
            if (detail?.cast?.isNotEmpty() == true) {
                item(key = "cast", contentType = "ribbon") {
                    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.detailShelfTitleGap)) {
                        DetailText(stringResource(Res.string.seerr_cast), style = DetailTitleStyle)
                        LazyRow(
                            state = castScroll,
                            contentPadding = PaddingValues(TvDimens.itemGap),
                            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                        ) {
                            itemsIndexed(detail.cast, key = { index, member -> "cast:$index:${member.id}" }) { index, member ->
                                val id = "cast:$index:${member.id}"
                                PersonCard(
                                    member.name,
                                    member.character,
                                    onClick = {
                                        if (state.relatedEnabled) {
                                            onIntent(
                                                SeerrIntent.RememberScroll(
                                                    true,
                                                    SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                                                ),
                                            )
                                            onIntent(
                                                SeerrIntent.RememberCastScroll(
                                                    SeerrScrollPosition(
                                                        castScroll.firstVisibleItemIndex,
                                                        castScroll.firstVisibleItemScrollOffset,
                                                    ),
                                                ),
                                            )
                                            trackedIntent(SeerrIntent.Focused(id))
                                            onIntent(SeerrIntent.OpenPerson(member.id))
                                        }
                                    },
                                    focusModifier =
                                        seerrFocus(
                                            scope,
                                            id,
                                            index,
                                            ::trackedIntent,
                                            if (index ==
                                                0
                                            ) {
                                                onLeft
                                            } else {
                                                ({ false })
                                            },
                                            TvFocusTargetKind.Item,
                                        ).then(boundary(id)),
                                    artwork =
                                        seerrArtworkUrl(member.profileUrl)?.let { profile ->
                                            {
                                                MeasuredAsyncImage(
                                                    model = profile,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop,
                                                )
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
            if (detail != null && (state.related.phase != SeerrRelatedPhase.Ready || state.related.rows.isNotEmpty())) {
                item(key = "similar", contentType = "ribbon") {
                    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.detailShelfTitleGap)) {
                        DetailText(stringResource(Res.string.seerr_similar), style = DetailTitleStyle)
                        if (state.related.phase == SeerrRelatedPhase.Error || state.related.phase == SeerrRelatedPhase.Interrupted) {
                            DetailText(
                                stringResource(
                                    if (state.related.phase ==
                                        SeerrRelatedPhase.Interrupted
                                    ) {
                                        Res.string.seerr_related_interrupted
                                    } else {
                                        Res.string.seerr_related_error
                                    },
                                ),
                            )
                            TvButton(
                                stringResource(Res.string.seerr_retry),
                                { onIntent(SeerrIntent.RetrySimilar) },
                                seerrFocus(scope, "related-retry", 0, ::trackedIntent, onLeft).then(boundary("related-retry")),
                                enabled = state.relatedEnabled,
                            )
                        } else if (state.related.phase == SeerrRelatedPhase.Loading ||
                            state.related.phase == SeerrRelatedPhase.NotAttempted
                        ) {
                            DetailText(stringResource(Res.string.seerr_loading))
                        }
                        LazyRow(
                            state = relatedScroll,
                            contentPadding = PaddingValues(TvDimens.itemGap),
                            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                        ) {
                            itemsIndexed(state.related.rows, key = { _, row -> row.id }) { index, row ->
                                val id = "similar:${row.id}"
                                TvMediaCard(
                                    session,
                                    row.card.copy(
                                        title = row.media.title ?: stringResource(Res.string.seerr_untitled),
                                        subtitle =
                                            row.media.year
                                                ?.toString()
                                                .orEmpty(),
                                    ),
                                    wide = false,
                                    onFocused = {
                                        scope.onChildFocused(id, TvFocusTargetKind.Item, index)
                                        trackedIntent(SeerrIntent.Focused(id))
                                    },
                                    onClick = { if (state.relatedEnabled) onIntent(SeerrIntent.OpenSimilar(row.id)) },
                                    modifier = Modifier.alpha(if (state.relatedEnabled) 1f else 0.5f).then(boundary(id)),
                                    focusRequester = scope.rememberChildRequester(id),
                                    focusChildModifier =
                                        Modifier
                                            .focusProperties { canFocus = state.relatedEnabled }
                                            .onKeyEvent {
                                                it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && index == 0 && onLeft()
                                            },
                                    artwork = { SeerrPoster(row.media, Modifier.fillMaxSize()) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSeerrConnection(
    state: SeerrConnectionUi,
    identifier: String,
    onIdentifierChanged: (String) -> Unit,
    password: String,
    onPasswordChanged: (String) -> Unit,
    onIntent: (SeerrIntent) -> Unit,
    scope: TvFocusScopeNode,
    scroll: LazyListState,
    padding: PaddingValues,
    modifier: Modifier,
    exitEditing: Int,
    onEditing: (Boolean) -> Unit,
    onLeft: () -> Boolean,
) {
    DisposableEffect(Unit) { onDispose { onEditing(false) } }

    fun submit() {
        if (state.busy || identifier.isBlank() || password.isEmpty()) return
        val submitted = password
        onPasswordChanged("")
        onIntent(SeerrIntent.Login(identifier, submitted))
    }
    val service = state.connection.service
    LazyColumn(state = scroll, modifier = modifier, contentPadding = padding) {
        item {
            Column(
                Modifier.widthIn(max = TvDimens.findKeyboardWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
            ) {
                DetailText(stringResource(Res.string.seerr_setup))
                state.error?.let { DetailText(seerrErrorText(it), color = LocalJellyfinPalette.current.error) }
                if (state.connection.phase == SeerrConnectionPhase.Connected ||
                    state.connection.phase == SeerrConnectionPhase.VerificationRequired
                ) {
                    state.connection.user?.let { DetailText(stringResource(Res.string.seerr_connected, it.displayName)) }
                    state.connection.endpoint?.let { DetailText(stringResource(Res.string.seerr_endpoint, it)) }
                    if (service?.usesPlainHttp == true) DetailText(stringResource(Res.string.seerr_http))
                    TvButton(stringResource(Res.string.seerr_verify_connection), {
                        onIntent(SeerrIntent.RestoreConnection)
                    }, seerrFocus(scope, "verify", 8, onIntent, onLeft), enabled = !state.busy)
                    TvButton(stringResource(Res.string.seerr_disconnect), {
                        onPasswordChanged("")
                        onIntent(SeerrIntent.Disconnect)
                    }, seerrFocus(scope, "disconnect", 9, onIntent, onLeft), enabled = !state.busy)
                } else {
                    TvInputField(
                        state.url,
                        {
                            onIntent(SeerrIntent.UrlChanged(it))
                        },
                        stringResource(Res.string.seerr_url),
                        seerrFocus(scope, "url", 0, onIntent, onLeft).fillMaxWidth(),
                        enabled = !state.busy,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        onImeAction = {
                            onIntent(SeerrIntent.ValidateService)
                        },
                        onEditingChange = onEditing,
                        exitEditingRequests = exitEditing,
                    )
                    TvButton(
                        stringResource(Res.string.seerr_validate),
                        { onIntent(SeerrIntent.ValidateService) },
                        seerrFocus(scope, "validate", 1, onIntent, onLeft),
                        enabled =
                            !state.busy && state.url.isNotBlank(),
                    )
                    if (service != null) {
                        DetailText(stringResource(Res.string.seerr_validated, service.version))
                        if (service.usesPlainHttp) DetailText(stringResource(Res.string.seerr_http))
                        if (service.jellyfinLoginEnabled) {
                            TvButton(
                                stringResource(Res.string.seerr_jellyfin_login),
                                {
                                    onPasswordChanged("")
                                    onIntent(SeerrIntent.SelectLogin(SeerrLoginMethod.Jellyfin))
                                },
                                seerrFocus(scope, "jellyfin-login", 2, onIntent, onLeft),
                                enabled = !state.busy,
                                selected =
                                    state.method == SeerrLoginMethod.Jellyfin,
                            )
                        }
                        if (service.localLoginEnabled) {
                            TvButton(
                                stringResource(Res.string.seerr_local_login),
                                {
                                    onPasswordChanged("")
                                    onIntent(SeerrIntent.SelectLogin(SeerrLoginMethod.Local))
                                },
                                seerrFocus(scope, "local-login", 3, onIntent, onLeft),
                                enabled = !state.busy,
                                selected =
                                    state.method == SeerrLoginMethod.Local,
                            )
                        }
                        if (service.jellyfinLoginEnabled || service.localLoginEnabled) {
                            if (state.method == SeerrLoginMethod.Local) DetailText(stringResource(Res.string.seerr_local_explanation))
                            TvInputField(
                                identifier,
                                onIdentifierChanged,
                                stringResource(
                                    if (state.method ==
                                        SeerrLoginMethod.Local
                                    ) {
                                        Res.string.seerr_email
                                    } else {
                                        Res.string.seerr_username
                                    },
                                ),
                                seerrFocus(scope, "identifier", 4, onIntent, onLeft).fillMaxWidth(),
                                enabled = !state.busy,
                                keyboardType =
                                    if (state.method ==
                                        SeerrLoginMethod.Local
                                    ) {
                                        KeyboardType.Email
                                    } else {
                                        KeyboardType.Text
                                    },
                                onEditingChange = onEditing,
                                exitEditingRequests = exitEditing,
                            )
                            TvInputField(
                                password,
                                {
                                    onPasswordChanged(it)
                                },
                                stringResource(Res.string.seerr_password),
                                seerrFocus(scope, "password", 5, onIntent, onLeft).fillMaxWidth(),
                                enabled = !state.busy,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Go,
                                onImeAction = ::submit,
                                onEditingChange = onEditing,
                                exitEditingRequests = exitEditing,
                            )
                            TvButton(
                                stringResource(Res.string.seerr_sign_in),
                                ::submit,
                                seerrFocus(scope, "login", 6, onIntent, onLeft),
                                enabled =
                                    !state.busy && identifier.isNotBlank() && password.isNotEmpty(),
                            )
                        }
                        if (service.quickConnectSupported) {
                            TvButton(stringResource(Res.string.seerr_quick_connect), {
                                onPasswordChanged("")
                                onIntent(SeerrIntent.BeginQuickConnect)
                            }, seerrFocus(scope, "quick-connect", 7, onIntent, onLeft), enabled = !state.busy)
                        }
                    }
                }
                state.quickConnect?.let {
                    DetailText(stringResource(Res.string.seerr_quick_connect_code, it.code), style = TvScreenHeaderStyle)
                    DetailText(stringResource(Res.string.seerr_quick_connect_wait))
                }
                if (state.busy) DetailText(stringResource(Res.string.seerr_working))
                TvButton(stringResource(Res.string.seerr_cancel), {
                    onPasswordChanged("")
                    onIdentifierChanged("")
                    onIntent(SeerrIntent.CancelAuthentication)
                }, seerrFocus(scope, "cancel-auth", 10, onIntent, onLeft))
            }
        }
    }
}

@Composable
private fun TvSeerrConfirmation(
    state: SeerrDetailUi,
    onIntent: (SeerrIntent) -> Unit,
) {
    if (!state.reviewVisible) return
    TvFocusTrapEffect()
    val cancel = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestTvFocusWithRetry { cancel.requestFocusSafely() } }
    val summaryRequester = remember { FocusRequester() }
    val summaryScroll = rememberScrollState()
    val summaryScope = rememberCoroutineScope()
    val hasAction = state.reviewCanSubmit || state.chooseSeasons || state.reviewNeedsRefresh
    TvSettingsDialogFrame(
        title = stringResource(Res.string.seerr_request),
        onDismiss = { if (!state.submitting) onIntent(SeerrIntent.Back) },
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(summaryScroll)
                .focusRequester(summaryRequester)
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = cancel
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                }.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionUp, Key.DirectionDown -> {
                                val down = event.key == Key.DirectionDown
                                if (down && summaryScroll.value >= summaryScroll.maxValue) {
                                    cancel.requestFocusSafely()
                                } else {
                                    val step = (summaryScroll.viewportSize * 0.75f).toInt().coerceAtLeast(1)
                                    val target = (summaryScroll.value + if (down) step else -step).coerceIn(0, summaryScroll.maxValue)
                                    summaryScope.launch { summaryScroll.animateScrollTo(target) }
                                    true
                                }
                            }
                            else -> false
                        }
                    }
                }.focusable(),
            verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
        ) {
            seerrReviewLines(state).forEach { DetailText(it) }
        }
        TvSettingsDialogAction(
            stringResource(Res.string.seerr_close),
            cancel,
            { onIntent(SeerrIntent.Back) },
            Modifier.focusProperties {
                up = summaryRequester
                down = if (hasAction) confirm else FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
            enabled = !state.submitting,
        )
        if (hasAction) {
            TvSettingsDialogAction(
                stringResource(
                    when {
                        state.reviewCanSubmit -> Res.string.seerr_confirm
                        state.chooseSeasons -> Res.string.seerr_choose_seasons_action
                        else -> Res.string.seerr_refresh_status
                    },
                ),
                confirm,
                {
                    onIntent(
                        when {
                            state.reviewCanSubmit -> SeerrIntent.SubmitRequest
                            state.chooseSeasons -> SeerrIntent.ChooseSeasons
                            else -> SeerrIntent.Refresh
                        },
                    )
                },
                Modifier.focusProperties {
                    up = cancel
                    down = FocusRequester.Cancel
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                },
                enabled = !state.submitting && !state.loading,
            )
        }
    }
}

@Composable
private fun TvSeerrQualityPicker(
    state: SeerrDetailUi,
    onIntent: (SeerrIntent) -> Unit,
) {
    TvFocusTrapEffect()
    val standard = remember { FocusRequester() }
    val fourK = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestTvFocusWithRetry { standard.requestFocusSafely() } }
    TvSettingsDialogFrame(
        stringResource(
            if (state.libraryPickerVisible) Res.string.seerr_library_quality else Res.string.seerr_quality_title,
        ),
        {
            onIntent(SeerrIntent.Back)
        },
    ) {
        TvSettingsDialogAction(
            seerrQualityText(SeerrQuality.Standard),
            standard,
            {
                onIntent(
                    if (state.libraryPickerVisible) {
                        SeerrIntent.OpenLibraryQuality(
                            SeerrQuality.Standard,
                        )
                    } else {
                        SeerrIntent.SelectQuality(SeerrQuality.Standard)
                    },
                )
            },
            Modifier.focusProperties {
                up = FocusRequester.Cancel
                down = fourK
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
            enabled = SeerrQuality.Standard in (if (state.libraryPickerVisible) state.libraryQualities else state.requestQualities),
        )
        TvSettingsDialogAction(
            seerrQualityText(SeerrQuality.FourK),
            fourK,
            {
                onIntent(
                    if (state.libraryPickerVisible) {
                        SeerrIntent.OpenLibraryQuality(
                            SeerrQuality.FourK,
                        )
                    } else {
                        SeerrIntent.SelectQuality(SeerrQuality.FourK)
                    },
                )
            },
            Modifier.focusProperties {
                up = standard
                down = close
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
            enabled = SeerrQuality.FourK in (if (state.libraryPickerVisible) state.libraryQualities else state.requestQualities),
        )
        TvSettingsDialogAction(
            stringResource(Res.string.seerr_close),
            close,
            { onIntent(SeerrIntent.Back) },
            Modifier.focusProperties {
                up = fourK
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
        )
    }
}

@Composable
private fun TvSeerrPerson(
    state: SeerrPersonUi,
    session: Session,
    onIntent: (SeerrIntent) -> Unit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState(state.scroll.offset)
    val movies = rememberLazyListState(state.movieScroll.index, state.movieScroll.offset)
    val series = rememberLazyListState(state.seriesScroll.index, state.seriesScroll.offset)
    val restoreId = remember(state.entryId) { state.focusedId }
    val credits = state.credits
    BackHandler { onIntent(SeerrIntent.Back) }
    LaunchedEffect(scroll, movies, series) {
        snapshotFlow {
            SeerrIntent.RememberPerson(
                SeerrScrollPosition(offset = scroll.value),
                SeerrScrollPosition(movies.firstVisibleItemIndex, movies.firstVisibleItemScrollOffset),
                SeerrScrollPosition(series.firstVisibleItemIndex, series.firstVisibleItemScrollOffset),
            )
        }.distinctUntilChanged().collect { onIntent(it) }
    }
    TvPersonContent(
        session = session,
        state = state.presentation,
        onBack = { onIntent(SeerrIntent.Back) },
        onHomeSelected = {},
        onFindSelected = {},
        onFavoritesSelected = {},
        onSettingsSelected = {},
        onItemSelected = {
            onIntent(
                SeerrIntent.RememberPerson(
                    SeerrScrollPosition(offset = scroll.value),
                    SeerrScrollPosition(movies.firstVisibleItemIndex, movies.firstVisibleItemScrollOffset),
                    SeerrScrollPosition(series.firstVisibleItemIndex, series.firstVisibleItemScrollOffset),
                ),
            )
            onIntent(SeerrIntent.PersonFocused(it.id))
            onIntent(SeerrIntent.OpenCredit(it.id))
        },
        onItemPlayDirect = {},
        onRetry = { onIntent(SeerrIntent.RetryPerson) },
        onLoadMoreAutomatically = {},
        onRetryPage = {},
        modifier = modifier,
        headerArtwork = { url, bounds -> SeerrPersonArtwork(url, bounds) },
        cardArtwork = { item, bounds -> credits[item.id]?.let { SeerrPoster(it, bounds) } },
        scrollState = scroll,
        moviesState = movies,
        seriesState = series,
        initialFocusId = restoreId,
        onFocused = { onIntent(SeerrIntent.PersonFocused(it)) },
        entryKey = "seerr/person/${state.entryId}",
    )
}
