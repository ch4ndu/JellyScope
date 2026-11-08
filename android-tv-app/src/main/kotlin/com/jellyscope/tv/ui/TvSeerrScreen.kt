// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrConnectionPhase
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.SeerrLoginMethod
import com.jellyscope.core.domain.model.SeerrMediaType
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
import com.jellyscope.ui.component.FocusableBox
import com.jellyscope.ui.focus.FocusRestoreRequest
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.nav_requests
import com.jellyscope.ui.generated.resources.seerr_approval_required
import com.jellyscope.ui.generated.resources.seerr_auto_approval
import com.jellyscope.ui.generated.resources.seerr_back
import com.jellyscope.ui.generated.resources.seerr_cancel
import com.jellyscope.ui.generated.resources.seerr_cast
import com.jellyscope.ui.generated.resources.seerr_choose_season
import com.jellyscope.ui.generated.resources.seerr_clear
import com.jellyscope.ui.generated.resources.seerr_confirm
import com.jellyscope.ui.generated.resources.seerr_confirmation_summary
import com.jellyscope.ui.generated.resources.seerr_confirmation_title
import com.jellyscope.ui.generated.resources.seerr_connected
import com.jellyscope.ui.generated.resources.seerr_disconnect
import com.jellyscope.ui.generated.resources.seerr_email
import com.jellyscope.ui.generated.resources.seerr_empty
import com.jellyscope.ui.generated.resources.seerr_endpoint
import com.jellyscope.ui.generated.resources.seerr_episode_count
import com.jellyscope.ui.generated.resources.seerr_filtered_empty
import com.jellyscope.ui.generated.resources.seerr_hide_available
import com.jellyscope.ui.generated.resources.seerr_hide_available_hint
import com.jellyscope.ui.generated.resources.seerr_http
import com.jellyscope.ui.generated.resources.seerr_jellyfin_login
import com.jellyscope.ui.generated.resources.seerr_load_more
import com.jellyscope.ui.generated.resources.seerr_loading
import com.jellyscope.ui.generated.resources.seerr_local_explanation
import com.jellyscope.ui.generated.resources.seerr_local_login
import com.jellyscope.ui.generated.resources.seerr_manage
import com.jellyscope.ui.generated.resources.seerr_no_requests
import com.jellyscope.ui.generated.resources.seerr_open_library
import com.jellyscope.ui.generated.resources.seerr_overview_missing
import com.jellyscope.ui.generated.resources.seerr_owner_changed
import com.jellyscope.ui.generated.resources.seerr_page
import com.jellyscope.ui.generated.resources.seerr_page_hint
import com.jellyscope.ui.generated.resources.seerr_password
import com.jellyscope.ui.generated.resources.seerr_quick_connect
import com.jellyscope.ui.generated.resources.seerr_quick_connect_code
import com.jellyscope.ui.generated.resources.seerr_quick_connect_wait
import com.jellyscope.ui.generated.resources.seerr_quota_exceeded
import com.jellyscope.ui.generated.resources.seerr_reconcile
import com.jellyscope.ui.generated.resources.seerr_reconciled
import com.jellyscope.ui.generated.resources.seerr_refresh
import com.jellyscope.ui.generated.resources.seerr_related_error
import com.jellyscope.ui.generated.resources.seerr_related_interrupted
import com.jellyscope.ui.generated.resources.seerr_request_created
import com.jellyscope.ui.generated.resources.seerr_requests_empty
import com.jellyscope.ui.generated.resources.seerr_restricted
import com.jellyscope.ui.generated.resources.seerr_retry
import com.jellyscope.ui.generated.resources.seerr_search
import com.jellyscope.ui.generated.resources.seerr_search_results
import com.jellyscope.ui.generated.resources.seerr_search_submit
import com.jellyscope.ui.generated.resources.seerr_season_selection
import com.jellyscope.ui.generated.resources.seerr_seasons
import com.jellyscope.ui.generated.resources.seerr_selected
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
import com.jellyscope.ui.screen.seerr.SeerrCastPortrait
import com.jellyscope.ui.screen.seerr.SeerrConnectionUi
import com.jellyscope.ui.screen.seerr.SeerrDetailHero
import com.jellyscope.ui.screen.seerr.SeerrDetailUi
import com.jellyscope.ui.screen.seerr.SeerrIntent
import com.jellyscope.ui.screen.seerr.SeerrNotice
import com.jellyscope.ui.screen.seerr.SeerrPoster
import com.jellyscope.ui.screen.seerr.SeerrRelatedPhase
import com.jellyscope.ui.screen.seerr.SeerrScreenEffects
import com.jellyscope.ui.screen.seerr.SeerrScrollPosition
import com.jellyscope.ui.screen.seerr.SeerrSection
import com.jellyscope.ui.screen.seerr.SeerrUiState
import com.jellyscope.ui.screen.seerr.SeerrViewModel
import com.jellyscope.ui.screen.seerr.relatedEnabled
import com.jellyscope.ui.screen.seerr.seerrAvailabilityLine
import com.jellyscope.ui.screen.seerr.seerrCatalogText
import com.jellyscope.ui.screen.seerr.seerrErrorText
import com.jellyscope.ui.screen.seerr.seerrMediaTypeText
import com.jellyscope.ui.screen.seerr.seerrQualityText
import com.jellyscope.ui.screen.seerr.seerrQuotaText
import com.jellyscope.ui.screen.seerr.seerrRequestActionText
import com.jellyscope.ui.screen.seerr.seerrRequestLine
import com.jellyscope.ui.screen.seerr.seerrSeasonNumbersText
import com.jellyscope.ui.screen.seerr.seerrSeasonText
import com.jellyscope.ui.screen.seerr.seerrSectionText
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
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
private fun TvSeerrContent(
    state: SeerrUiState,
    session: Session,
    onIntent: (SeerrIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val detail = state.detail
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
            else -> "discover"
        }
    val body = rememberTvFocusScopeNode(listOf("requests", surface))
    val currentState by rememberUpdatedState(state)
    val currentBody by rememberUpdatedState(body)
    val registrationKey = remember { rail.contentRegistrationKey }
    var editing by remember { mutableStateOf(false) }
    var exitEditing by remember { mutableStateOf(0) }
    val startPadding = if (rail.visible) TvDimens.drawerContentStartPadding else TvDimens.overscanHorizontal
    val enterContent =
        remember(selectedTab) {
            {
                val current = currentState
                currentBody.requestEntry(listOfNotNull(current.lastFocusedId, current.feed.selectedId, "refresh", "url", "manage")) ||
                    selectedTab.requestFocusSafely()
            }
        }
    var confirmationWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.detail?.confirmation != null) {
        if (state.detail?.confirmation != null) {
            confirmationWasOpen = true
        } else if (confirmationWasOpen) {
            confirmationWasOpen = false
            requestTvFocusWithRetry { body.requestEntry(listOf("request", "library", "refresh")) }
        }
    }
    DisposableEffect(rail, registrationKey, selectedTab, enterContent, detail != null) {
        rail.setContentRightFocusRequester(registrationKey, if (detail != null) body.entryRequester else selectedTab)
        rail.setContentRightFocusAction(registrationKey, enterContent)
        onDispose {
            rail.setContentRightFocusRequester(registrationKey, null)
            rail.setContentRightFocusAction(registrationKey, null)
        }
    }
    BackHandler(enabled = !rail.railHasFocus || state.detail != null || state.connection.authenticating) {
        when {
            editing -> exitEditing++
            state.detail != null -> onIntent(SeerrIntent.Back)
            state.connection.authenticating -> onIntent(SeerrIntent.CancelAuthentication)
            !rail.requestRailFocus() -> onBack()
        }
    }
    Column(modifier.fillMaxSize().focusGroup(), verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap)) {
        if (detail == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = startPadding, top = TvDimens.overscanVertical, end = TvDimens.overscanHorizontal)
                    .tvFocusScope(header) {
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                    DetailText(stringResource(Res.string.nav_requests), modifier = Modifier.weight(1f), style = TvScreenHeaderStyle)
                    if (state.section != SeerrSection.Connection) {
                        TvButton(
                            text = stringResource(Res.string.seerr_manage),
                            onClick = { onIntent(SeerrIntent.SelectSection(SeerrSection.Connection)) },
                            modifier =
                                Modifier
                                    .focusRequester(header.rememberChildRequester(SeerrSection.Connection.name))
                                    .focusProperties { down = body.entryRequester }
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            header.onChildFocused(
                                                SeerrSection.Connection.name,
                                                TvFocusTargetKind.Action,
                                                2,
                                            )
                                        }
                                    },
                            enabled = state.ownerValid,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                    val sections =
                        if (state.section ==
                            SeerrSection.Connection
                        ) {
                            listOf(SeerrSection.Discover)
                        } else {
                            listOf(SeerrSection.Discover, SeerrSection.Requests)
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
                            enabled = state.ownerValid,
                            onClick = { onIntent(SeerrIntent.SelectSection(section)) },
                            modifier =
                                Modifier
                                    .focusRequester(requester)
                                    .focusProperties { down = body.entryRequester }
                                    .onFocusChanged { if (it.isFocused) header.onChildFocused(section.name, TvFocusTargetKind.Tab, index) }
                                    .onKeyEvent { event ->
                                        event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionLeft &&
                                            index == 0 &&
                                            rail.requestRailFocus()
                                    },
                        )
                    }
                }
            }
        }
        key(surface, state.detail?.entryId, state.catalog, state.submittedQuery, state.feed.resetRevision) {
            val position =
                state.detail?.scroll ?: if (state.section == SeerrSection.Connection) SeerrScrollPosition() else state.feed.scroll
            val scroll = rememberLazyListState(position.index, position.offset)
            val gridScroll = rememberLazyGridState(position.index, position.offset)
            val isFeed = state.detail == null && state.section != SeerrSection.Connection
            val coordinator = LocalTvFocusCoordinator.current
            val restore = coordinator?.readyRestore?.takeIf { it.path.scopes == body.scopes }
            val savedFocus = remember { state.detail?.focusedId ?: state.lastFocusedId ?: state.feed.selectedId }
            val slots = tvSeerrFocusSlots(state)
            val latestSlots by rememberUpdatedState(slots)
            var entered by remember { mutableStateOf(false) }
            var restoredTarget by remember { mutableStateOf<String?>(null) }
            val ready =
                state.detail?.let { !it.loading || it.detail != null }
                    ?: (!state.feed.loading || state.feed.rows.isNotEmpty() || state.section != SeerrSection.Requests)
            LaunchedEffect(body, restore?.token, ready, state.detail?.loading, state.connected, state.restricted, slots) {
                if (!ready || rail.contentAutofocusSuppressed || state.detail?.confirmation != null) return@LaunchedEffect
                if (state.detail?.loading == true && savedFocus?.startsWith("similar:") == true) return@LaunchedEffect
                val preferred = savedFocus?.let { FocusRestoreRequest(it, 0) } ?: body.restoreRequest()
                val request =
                    if (state.detail != null && (preferred == null || preferred.key !in latestSlots)) {
                        latestSlots.keys
                            .firstOrNull { it == "refresh" || it == "loading" }
                            ?.let { FocusRestoreRequest(it, 0) }
                    } else {
                        preferred
                    }
                val preferredReady = savedFocus != null && savedFocus in latestSlots && restoredTarget != savedFocus
                if (request != null && (!entered || restore != null || preferredReady)) {
                    var restored =
                        body.restoreFocus(
                            request,
                            latestSlots.keys.toList(),
                            { latestSlots.values.elementAt(it) },
                            { if (isFeed) gridScroll.scrollToItem(it) else scroll.scrollToItem(it) },
                        )
                    var target = request.key
                    if (!restored && state.detail != null && request.key != "refresh" && "refresh" in latestSlots) {
                        restored =
                            body.restoreFocus(
                                FocusRestoreRequest("refresh", 0),
                                latestSlots.keys.toList(),
                                { latestSlots.values.elementAt(it) },
                                { scroll.scrollToItem(it) },
                            )
                        target = "refresh"
                    }
                    if (restored) {
                        entered = true
                        restoredTarget = target
                    }
                } else if (!entered && restore == null) {
                    entered = requestTvFocusWithRetry { body.requestEntry(latestSlots.keys.toList()) || selectedTab.requestFocusSafely() }
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
                    }.filterNotNull().distinctUntilChanged().collect { onIntent(SeerrIntent.RememberScroll(!isFeed, it)) }
                }
            }
            val padding =
                PaddingValues(
                    start = startPadding,
                    end = TvDimens.overscanHorizontal,
                    top = TvDimens.gridContentTopPadding,
                    bottom = TvDimens.overscanVertical,
                )
            val bodyModifier = Modifier.weight(1f).fillMaxWidth().tvFocusScope(body) { slots.keys.toList() }
            when {
                !state.ownerValid || (state.section != SeerrSection.Connection && (state.restricted || !state.connected)) -> {
                    LazyColumn(
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
                                stringResource(Res.string.seerr_manage),
                                { onIntent(SeerrIntent.SelectSection(SeerrSection.Connection)) },
                                modifier = seerrFocus(body, "manage", 0, onIntent, rail::requestRailFocus),
                                enabled = state.ownerValid,
                            )
                        }
                    }
                }
                state.section == SeerrSection.Connection ->
                    TvSeerrConnection(state.connection, onIntent, body, scroll, padding, bodyModifier, exitEditing, {
                        editing =
                            it
                    }, rail::requestRailFocus)
                (detail?.loading == true && detail.detail == null) ||
                    (detail == null && state.section == SeerrSection.Requests && state.feed.loading && state.feed.rows.isEmpty()) -> {
                    val loading = body.rememberChildRequester("loading")
                    TvLoadingFocusPark(
                        focusRequester = loading,
                        onRequestRailFocus = rail::requestRailFocus,
                        requestFocusOnAttach = !rail.contentAutofocusSuppressed && !rail.railHasFocus,
                        modifier =
                            bodyModifier.onFocusChanged {
                                if (it.hasFocus) body.onChildFocused("loading", TvFocusTargetKind.Loading, 0)
                            },
                    )
                }
                detail != null -> TvSeerrDetail(detail, session, onIntent, body, scroll, padding, bodyModifier, rail::requestRailFocus)
                else ->
                    TvSeerrFeed(
                        state,
                        session,
                        onIntent,
                        body,
                        gridScroll,
                        padding,
                        bodyModifier,
                        exitEditing,
                        { editing = it },
                        rail::requestRailFocus,
                    )
            }
        }
    }
    state.detail?.takeIf { it.confirmation != null }?.let { TvSeerrConfirmation(it, onIntent) }
}

/** Lazy slots are layout positions, separate from the stable focus keys. */
private fun tvSeerrFocusSlots(state: SeerrUiState): Map<String, Int> =
    buildMap {
        val detail = state.detail
        if (state.section == SeerrSection.Connection) {
            listOf(
                "url",
                "validate",
                "jellyfin-login",
                "local-login",
                "identifier",
                "password",
                "login",
                "quick-connect",
                "verify",
                "disconnect",
                "cancel-auth",
            ).forEach {
                put(it, 0)
            }
        } else if (state.restricted || !state.connected || !state.ownerValid) {
            put("manage", 1)
        } else if ((detail?.loading == true && detail.detail == null) ||
            (detail == null && state.section == SeerrSection.Requests && state.feed.loading && state.feed.rows.isEmpty())
        ) {
            put("loading", 0)
        } else if (detail != null) {
            if (!detail.loading && !detail.submitting && !detail.reconciling) put("refresh", 0)
            if (detail.loading) put("loading", 0)
            val value = detail.detail
            if (value != null) {
                if (detail.selectionEnabled) {
                    put("standard", 0)
                    if (detail.showFourK) put("four-k", 0)
                    detail.seasons.forEach { row -> if (row.enabled) put("season:${row.season.number}", 1) }
                }
                val footer = 2
                if (detail.canSubmit) put("request", 0)
                if (detail.uncertain && !detail.loading && !detail.reconciling && !detail.submitting) put("reconcile", footer)
                if (!detail.loading && !detail.submitting && !detail.resolvingLibrary && !detail.reconciling) put("library", 0)
                value.cast.forEachIndexed { index, member -> put("cast:$index:${member.id}", 3) }
                val relatedSlot = footer + 1 + if (value.cast.isNotEmpty()) 1 else 0
                if (detail.relatedEnabled) {
                    if (detail.related.phase == SeerrRelatedPhase.Error || detail.related.phase == SeerrRelatedPhase.Interrupted) {
                        put("related-retry", relatedSlot)
                    }
                    detail.related.rows.forEach { put("similar:${it.id}", relatedSlot) }
                }
            }
        } else {
            put("refresh", 0)
            if (state.section ==
                SeerrSection.Discover
            ) {
                listOf("search", "submit-search", "clear-search", "trending", "movies", "tv", "hide-available").forEach { put(it, 0) }
            }
            state.feed.rows.forEachIndexed { index, row -> put(row.id, index + 1) }
            if (state.feed.hasMore) put("more", state.feed.rows.size + 1)
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
) {
    val feed = state.feed
    val rail = LocalTvHostedRailController.current
    var appendStart by remember { mutableStateOf<Int?>(null) }
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(feed.loading, feed.page, feed.error, state.lastFocusedId, rail.railHasFocus, rail.contentAutofocusSuppressed) {
        val start = appendStart ?: return@LaunchedEffect
        if (feed.loading) return@LaunchedEffect
        appendStart = null
        if (feed.error != null ||
            latestState.lastFocusedId != "more" ||
            rail.railHasFocus ||
            rail.contentAutofocusSuppressed
        ) {
            return@LaunchedEffect
        }
        // Keep focus at the appended boundary, including when the final page removes the footer.
        val targetId =
            feed.rows.getOrNull(start)?.id ?: if (!feed.hasMore) feed.rows.lastOrNull()?.id ?: "refresh" else return@LaunchedEffect
        val slots = tvSeerrFocusSlots(latestState)
        val keys = slots.keys.toList()
        scope.restoreFocus(
            FocusRestoreRequest(targetId, keys.indexOf(targetId).coerceAtLeast(0)),
            keys,
            { slots.values.elementAt(it) },
            { scroll.scrollToItem(it) },
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(TvDimens.posterWidth.tileScaled()),
        state = scroll,
        modifier = modifier,
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
    ) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
            Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                if (state.section == SeerrSection.Discover) {
                    TvInputField(
                        state.query,
                        { onIntent(SeerrIntent.QueryChanged(it)) },
                        stringResource(Res.string.seerr_search),
                        modifier =
                            seerrFocus(
                                scope,
                                "search",
                                1,
                                onIntent,
                                onLeft,
                            ).widthIn(max = TvDimens.findKeyboardWidth).fillMaxWidth(),
                        imeAction = ImeAction.Search,
                        onImeAction = {
                            onIntent(SeerrIntent.SubmitSearch)
                        },
                        onEditingChange = onEditing,
                        exitEditingRequests = exitEditing,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                        TvButton(stringResource(Res.string.seerr_search_submit), {
                            onIntent(SeerrIntent.SubmitSearch)
                        }, seerrFocus(scope, "submit-search", 2, onIntent, onLeft), enabled = state.query.isNotBlank())
                        TvButton(stringResource(Res.string.seerr_clear), {
                            onIntent(SeerrIntent.ClearSearch)
                        }, seerrFocus(scope, "clear-search", 3, onIntent, { false }))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                        listOf(
                            SeerrCatalog.Trending to "trending",
                            SeerrCatalog.Movies to "movies",
                            SeerrCatalog.Tv to "tv",
                        ).forEachIndexed {
                            index,
                            (catalog, id),
                            ->
                            TvButton(
                                seerrCatalogText(catalog),
                                { onIntent(SeerrIntent.SelectCatalog(catalog)) },
                                seerrFocus(
                                    scope,
                                    id,
                                    index + 4,
                                    onIntent,
                                    if (index == 0) {
                                        onLeft
                                    } else {
                                        { false }
                                    },
                                ),
                                selected = state.catalog == catalog,
                            )
                        }
                    }
                    if (state.catalog != SeerrCatalog.Search) {
                        TvButton(
                            stringResource(Res.string.seerr_hide_available),
                            { onIntent(SeerrIntent.ToggleHideAvailable) },
                            seerrFocus(scope, "hide-available", 7, onIntent, onLeft),
                            selected = state.hideAvailable,
                        )
                        DetailText(stringResource(Res.string.seerr_hide_available_hint))
                    }
                    if (state.catalog ==
                        SeerrCatalog.Search
                    ) {
                        DetailText(stringResource(Res.string.seerr_search_results, state.submittedQuery))
                    }
                }
                TvButton(stringResource(Res.string.seerr_refresh), {
                    onIntent(SeerrIntent.Refresh)
                }, seerrFocus(scope, "refresh", 0, onIntent, onLeft), enabled = !feed.loading)
                if (feed.loading) DetailText(stringResource(Res.string.seerr_loading))
                feed.error?.let { DetailText(seerrErrorText(it), color = LocalJellyfinPalette.current.error) }
                if (feed.loaded &&
                    !feed.loading &&
                    feed.error == null &&
                    feed.rows.isEmpty()
                ) {
                    DetailText(
                        stringResource(
                            if (state.section ==
                                SeerrSection.Requests
                            ) {
                                Res.string.seerr_requests_empty
                            } else {
                                if (state.hideAvailable &&
                                    state.catalog != SeerrCatalog.Search
                                ) {
                                    Res.string.seerr_filtered_empty
                                } else {
                                    Res.string.seerr_empty
                                }
                            },
                        ),
                    )
                }
                if (feed.page > 0) DetailText(stringResource(Res.string.seerr_page, feed.page, feed.totalPages.coerceAtLeast(feed.page)))
            }
        }
        itemsIndexed(feed.rows, key = { _, row -> row.id }, contentType = { _, _ -> "poster" }) { index, row ->
            Column {
                TvMediaCard(
                    session = session,
                    item = row.card.copy(title = row.media.title ?: stringResource(Res.string.seerr_untitled)),
                    wide = false,
                    onFocused = { onIntent(SeerrIntent.Focused(row.id)) },
                    onClick = {
                        onIntent(
                            SeerrIntent.RememberScroll(
                                false,
                                SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                            ),
                        )
                        onIntent(SeerrIntent.OpenDetail(row))
                    },
                    focusRequester = scope.rememberChildRequester(row.id),
                    focusChildModifier =
                        Modifier.onFocusChanged {
                            if (it.isFocused) {
                                scope.onChildFocused(
                                    row.id,
                                    TvFocusTargetKind.Item,
                                    index,
                                )
                            }
                        },
                    artwork = { SeerrPoster(row.media, Modifier.fillMaxSize()) },
                )
                DetailText(seerrAvailabilityLine(SeerrQuality.Standard, row.media.standardAvailability))
                row.request?.let { DetailText(seerrRequestLine(it)) }
            }
        }
        if (feed.hasMore) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }, contentType = "more") {
                Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                    val label = stringResource(if (feed.loading) Res.string.seerr_loading else Res.string.seerr_load_more)
                    FocusableBox(
                        onClick = {
                            appendStart = feed.rows.size
                            onIntent(SeerrIntent.LoadMore)
                        },
                        modifier = seerrFocus(scope, "more", feed.rows.size + 7, onIntent, onLeft),
                        enabled = !feed.loading,
                        focusableWhenDisabled = true,
                        contentDescription = label,
                        contentPadding = PaddingValues(TvDimens.buttonHorizontalPadding, TvDimens.buttonVerticalPadding),
                    ) { _ -> DetailText(label) }
                    DetailText(stringResource(Res.string.seerr_page_hint))
                }
            }
        }
    }
}

@Composable
private fun TvSeerrDetail(
    state: SeerrDetailUi,
    session: Session,
    onIntent: (SeerrIntent) -> Unit,
    scope: TvFocusScopeNode,
    scroll: LazyListState,
    padding: PaddingValues,
    modifier: Modifier,
    onLeft: () -> Boolean,
) {
    val detail = state.detail
    LazyColumn(
        state = scroll,
        modifier = modifier,
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
    ) {
        item(key = "metadata") {
            Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                TvButton(
                    stringResource(Res.string.seerr_refresh),
                    { onIntent(SeerrIntent.Refresh) },
                    seerrFocus(scope, "refresh", 0, onIntent, onLeft),
                    enabled =
                        !state.loading && !state.submitting && !state.reconciling,
                )
                if (state.loading) {
                    FocusableBox(
                        onClick = {},
                        enabled = false,
                        focusableWhenDisabled = true,
                        modifier = seerrFocus(scope, "loading", 0, onIntent, onLeft),
                        contentDescription = stringResource(Res.string.seerr_loading),
                    ) {
                        DetailText(stringResource(Res.string.seerr_loading))
                    }
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
                if (detail != null) {
                    SeerrDetailHero(detail, {}, showBack = false)
                    TvButton(
                        seerrRequestActionText(state),
                        { onIntent(SeerrIntent.ConfirmRequest) },
                        seerrFocus(
                            scope,
                            "request",
                            state.seasons.size + 4,
                            onIntent,
                            onLeft,
                        ),
                        enabled = state.canSubmit,
                    )
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
                        seerrFocus(scope, "library", state.seasons.size + 5, onIntent, onLeft),
                        enabled =
                            !state.loading && !state.submitting && !state.resolvingLibrary && !state.reconciling,
                    )
                    DetailText(seerrAvailabilityLine(SeerrQuality.Standard, detail.media.standardAvailability))
                    DetailText(seerrAvailabilityLine(SeerrQuality.FourK, detail.media.fourKAvailability))
                    DetailText(seerrQuotaText(detail.quota))
                    DetailText(detail.overview ?: stringResource(Res.string.seerr_overview_missing))
                    if (detail.requests.isEmpty()) DetailText(stringResource(Res.string.seerr_no_requests))
                    detail.requests.forEach {
                        DetailText(seerrRequestLine(it))
                        if (it.seasons.isNotEmpty()) {
                            DetailText(
                                stringResource(Res.string.seerr_season_selection, seerrSeasonNumbersText(it.seasons)),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                        TvButton(
                            seerrQualityText(SeerrQuality.Standard),
                            {
                                onIntent(SeerrIntent.SelectQuality(SeerrQuality.Standard))
                            },
                            seerrFocus(scope, "standard", 1, onIntent, onLeft),
                            enabled = state.selectionEnabled,
                            selected =
                                state.quality == SeerrQuality.Standard,
                        )
                        if (state.showFourK) {
                            TvButton(
                                seerrQualityText(SeerrQuality.FourK),
                                {
                                    onIntent(SeerrIntent.SelectQuality(SeerrQuality.FourK))
                                },
                                seerrFocus(scope, "four-k", 2, onIntent, { false }),
                                enabled = state.selectionEnabled,
                                selected =
                                    state.quality == SeerrQuality.FourK,
                            )
                        }
                    }
                    state.option?.blockedBy?.let { DetailText(seerrErrorText(it)) }
                    if (state.key.type == SeerrMediaType.Tv) DetailText(stringResource(Res.string.seerr_seasons))
                }
            }
        }
        item(key = "seasons", contentType = "ribbon") {
            val seasonScroll = rememberLazyListState(state.seasonScroll.index, state.seasonScroll.offset)
            LaunchedEffect(seasonScroll) {
                snapshotFlow { SeerrScrollPosition(seasonScroll.firstVisibleItemIndex, seasonScroll.firstVisibleItemScrollOffset) }
                    .distinctUntilChanged()
                    .collect { onIntent(SeerrIntent.RememberSeasonScroll(it)) }
            }
            LazyRow(
                state = seasonScroll,
                contentPadding = PaddingValues(TvDimens.itemGap),
                horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
            ) {
                itemsIndexed(state.seasons, key = {
                    _,
                    row,
                    ->
                    "season:${row.season.number}"
                }, contentType = { _, _ -> "season" }) { index, row ->
                    Column(
                        Modifier.width(Dimensions.seerrSeasonWidth),
                        verticalArrangement = Arrangement.spacedBy(TvDimens.detailMetaGap),
                    ) {
                        val label = seerrSeasonText(row.season)
                        val reason = row.blockedBy?.let { seerrErrorText(it) }
                        FocusableBox(
                            onClick = { onIntent(SeerrIntent.ToggleSeason(row.season.number)) },
                            modifier =
                                seerrFocus(
                                    scope,
                                    "season:${row.season.number}",
                                    index + 3,
                                    onIntent,
                                    if (index ==
                                        0
                                    ) {
                                        onLeft
                                    } else {
                                        ({ false })
                                    },
                                ),
                            enabled = row.enabled,
                            focusableWhenDisabled = true,
                            contentDescription =
                                listOfNotNull(
                                    label,
                                    seerrAvailabilityLine(state.quality, row.availability),
                                    reason,
                                ).joinToString(". "),
                            contentPadding = PaddingValues(TvDimens.buttonHorizontalPadding, TvDimens.buttonVerticalPadding),
                        ) {
                            DetailText(if (row.selected) stringResource(Res.string.seerr_selected, label) else label)
                        }
                        DetailText(stringResource(Res.string.seerr_episode_count, row.season.episodeCount))
                        DetailText(seerrAvailabilityLine(state.quality, row.availability))
                        reason?.let { DetailText(it) }
                    }
                }
            }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                if (detail != null) {
                    if (state.key.type == SeerrMediaType.Tv &&
                        state.selectedSeasons.isEmpty()
                    ) {
                        DetailText(stringResource(Res.string.seerr_choose_season))
                    }
                    if (state.uncertain) {
                        DetailText(stringResource(Res.string.seerr_uncertain))
                        TvButton(
                            stringResource(Res.string.seerr_reconcile),
                            { onIntent(SeerrIntent.Reconcile) },
                            seerrFocus(
                                scope,
                                "reconcile",
                                state.seasons.size + 3,
                                onIntent,
                                onLeft,
                            ),
                            enabled = !state.loading && !state.reconciling && !state.submitting,
                        )
                    }
                    if (!state.canSubmit &&
                        state.selectedSeasons.isNotEmpty() &&
                        detail.quota.kind == SeerrQuotaKind.Limited &&
                        !detail.quota.allows(state.selectedSeasons.size)
                    ) {
                        DetailText(stringResource(Res.string.seerr_quota_exceeded))
                    }
                }
            }
        }
        if (detail?.cast?.isNotEmpty() == true) {
            item(key = "cast", contentType = "ribbon") {
                Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                    DetailText(stringResource(Res.string.seerr_cast), style = TvScreenHeaderStyle)
                    val castScroll = rememberLazyListState(state.castScroll.index, state.castScroll.offset)
                    LaunchedEffect(castScroll) {
                        snapshotFlow { SeerrScrollPosition(castScroll.firstVisibleItemIndex, castScroll.firstVisibleItemScrollOffset) }
                            .distinctUntilChanged()
                            .collect { onIntent(SeerrIntent.RememberCastScroll(it)) }
                    }
                    LazyRow(
                        state = castScroll,
                        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                        contentPadding = PaddingValues(TvDimens.itemGap),
                    ) {
                        itemsIndexed(detail.cast, key = { index, member -> "cast:$index:${member.id}" }) { index, member ->
                            val id = "cast:$index:${member.id}"
                            var focused by remember { mutableStateOf(false) }
                            val description = listOfNotNull(member.name, member.character).joinToString(". ")
                            Column(
                                Modifier
                                    .width(TvDimens.posterWidth.tileScaled())
                                    .border(if (focused) TvDimens.focusBorder else Dimensions.zero, LocalJellyfinPalette.current.cyan)
                                    .then(
                                        seerrFocus(
                                            scope,
                                            id,
                                            index,
                                            onIntent,
                                            if (index ==
                                                0
                                            ) {
                                                onLeft
                                            } else {
                                                ({ false })
                                            },
                                            TvFocusTargetKind.Item,
                                        ),
                                    ).onFocusChanged { focused = it.isFocused }
                                    .semantics(mergeDescendants = true) { contentDescription = description }
                                    .focusable(),
                            ) {
                                SeerrCastPortrait(
                                    member.profileUrl,
                                    Modifier.fillMaxWidth().height(TvDimens.detailPersonImageHeight.tileScaled()),
                                )
                                DetailText(member.name)
                                member.character?.let { DetailText(it) }
                            }
                        }
                    }
                }
            }
        }
        if (detail != null &&
            (state.related.phase != SeerrRelatedPhase.Ready || state.related.rows.isNotEmpty())
        ) {
            item(key = "similar", contentType = "ribbon") {
                Column(verticalArrangement = Arrangement.spacedBy(TvDimens.itemGap)) {
                    DetailText(stringResource(Res.string.seerr_similar), style = TvScreenHeaderStyle)
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
                            seerrFocus(scope, "related-retry", state.seasons.size + 6, onIntent, onLeft),
                            enabled = state.relatedEnabled,
                        )
                    } else if (state.related.phase == SeerrRelatedPhase.Loading || state.related.phase == SeerrRelatedPhase.NotAttempted) {
                        DetailText(stringResource(Res.string.seerr_loading))
                    }
                    val relatedScroll = rememberLazyListState(state.related.scroll.index, state.related.scroll.offset)
                    LaunchedEffect(relatedScroll) {
                        snapshotFlow {
                            SeerrScrollPosition(
                                relatedScroll.firstVisibleItemIndex,
                                relatedScroll.firstVisibleItemScrollOffset,
                            )
                        }.distinctUntilChanged()
                            .collect { onIntent(SeerrIntent.RememberRelatedScroll(it)) }
                    }
                    LazyRow(
                        state = relatedScroll,
                        contentPadding = PaddingValues(TvDimens.itemGap),
                        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                    ) {
                        items(state.related.rows, key = { it.id }, contentType = { "poster" }) { row ->
                            TvMediaCard(
                                session = session,
                                item =
                                    row.card.copy(
                                        title =
                                            row.media.title ?: stringResource(Res.string.seerr_untitled),
                                    ),
                                wide = false,
                                onFocused = { onIntent(SeerrIntent.Focused("similar:${row.id}")) },
                                onClick = { if (state.relatedEnabled) onIntent(SeerrIntent.OpenSimilar(row.id)) },
                                modifier = Modifier.alpha(if (state.relatedEnabled) 1f else 0.5f),
                                focusRequester = scope.rememberChildRequester("similar:${row.id}"),
                                focusChildModifier =
                                    Modifier.focusProperties { canFocus = state.relatedEnabled }.onFocusChanged {
                                        if (it.isFocused) {
                                            scope.onChildFocused(
                                                "similar:${row.id}",
                                                TvFocusTargetKind.Item,
                                                state.seasons.size + 6,
                                            )
                                        }
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

@Composable
private fun TvSeerrConnection(
    state: SeerrConnectionUi,
    onIntent: (SeerrIntent) -> Unit,
    scope: TvFocusScopeNode,
    scroll: LazyListState,
    padding: PaddingValues,
    modifier: Modifier,
    exitEditing: Int,
    onEditing: (Boolean) -> Unit,
    onLeft: () -> Boolean,
) {
    var identifier by remember(state.credentialRevision) { mutableStateOf("") }
    var password by remember(state.credentialRevision) { mutableStateOf("") }
    DisposableEffect(state.credentialRevision) {
        onDispose {
            identifier = ""
            password = ""
        }
    }
    DisposableEffect(Unit) { onDispose { onEditing(false) } }

    fun submit() {
        if (state.busy || identifier.isBlank() || password.isEmpty()) return
        val submitted = password
        password = ""
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
                        password = ""
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
                                    password = ""
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
                                    password = ""
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
                                { identifier = it },
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
                                    password = it
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
                                password = ""
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
                    password = ""
                    identifier = ""
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
    val intent = state.confirmation ?: return
    TvFocusTrapEffect()
    val cancel = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestTvFocusWithRetry { cancel.requestFocusSafely() } }
    Dialog(onDismissRequest = { if (!state.submitting) onIntent(SeerrIntent.Back) }) {
        Box(Modifier.background(LocalJellyfinPalette.current.surfaceNavy)) {
            Column(
                Modifier
                    .widthIn(max = TvDimens.settingsDialogWidth)
                    .heightIn(max = TvDimens.settingsDialogMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(TvDimens.settingsDialogContentPadding)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(TvDimens.formGap),
            ) {
                DetailText(
                    stringResource(
                        Res.string.seerr_confirmation_title,
                        state.detail?.media?.title ?: stringResource(Res.string.seerr_untitled),
                    ),
                    style = TvScreenHeaderStyle,
                )
                DetailText(
                    stringResource(
                        Res.string.seerr_confirmation_summary,
                        seerrMediaTypeText(intent.key.type),
                        seerrQualityText(intent.quality),
                    ),
                )
                if (intent.key.type ==
                    SeerrMediaType.Tv
                ) {
                    DetailText(stringResource(Res.string.seerr_season_selection, seerrSeasonNumbersText(intent.seasons)))
                }
                DetailText(
                    stringResource(
                        if (state.option?.requiresApproval !=
                            false
                        ) {
                            Res.string.seerr_approval_required
                        } else {
                            Res.string.seerr_auto_approval
                        },
                    ),
                )
                if (state.submitting) DetailText(stringResource(Res.string.seerr_working))
                TvButton(
                    stringResource(Res.string.seerr_cancel),
                    { onIntent(SeerrIntent.Back) },
                    Modifier.focusRequester(cancel).focusProperties {
                        up =
                            FocusRequester.Cancel
                        ; down = confirm
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    },
                    enabled = !state.submitting,
                )
                TvButton(
                    stringResource(Res.string.seerr_confirm),
                    { onIntent(SeerrIntent.SubmitRequest) },
                    Modifier.focusRequester(confirm).focusProperties {
                        up =
                            cancel
                        ; down = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    },
                    enabled =
                        !state.submitting && state.canSubmit,
                )
            }
        }
    }
}
