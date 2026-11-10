// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.seerr

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jellyscope.core.domain.model.SeerrCatalog
import com.jellyscope.core.domain.model.SeerrLibraryItem
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.LoadMoreOnApproachEnd
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.seerr_clear
import com.jellyscope.ui.generated.resources.seerr_discover
import com.jellyscope.ui.generated.resources.seerr_empty
import com.jellyscope.ui.generated.resources.seerr_filtered_empty
import com.jellyscope.ui.generated.resources.seerr_load_more
import com.jellyscope.ui.generated.resources.seerr_loading
import com.jellyscope.ui.generated.resources.seerr_manage
import com.jellyscope.ui.generated.resources.seerr_movies
import com.jellyscope.ui.generated.resources.seerr_my_requests
import com.jellyscope.ui.generated.resources.seerr_owner_changed
import com.jellyscope.ui.generated.resources.seerr_requests_empty
import com.jellyscope.ui.generated.resources.seerr_restricted
import com.jellyscope.ui.generated.resources.seerr_retry
import com.jellyscope.ui.generated.resources.seerr_search
import com.jellyscope.ui.generated.resources.seerr_search_prompt
import com.jellyscope.ui.generated.resources.seerr_search_results
import com.jellyscope.ui.generated.resources.seerr_search_submit
import com.jellyscope.ui.generated.resources.seerr_setup
import com.jellyscope.ui.generated.resources.seerr_trending
import com.jellyscope.ui.generated.resources.seerr_tv
import com.jellyscope.ui.generated.resources.seerr_untitled
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import org.jetbrains.compose.resources.stringResource

@Composable
fun SeerrScreen(
    viewModel: SeerrViewModel,
    session: Session,
    onSettingsClick: () -> Unit,
    bottomContentPadding: Dp = Dimensions.zero,
    onOpenLibrary: (SeerrLibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SeerrScreenEffects(viewModel, onOpenLibrary)
    SeerrContent(state, session, onSettingsClick, bottomContentPadding, viewModel::accept, modifier)
}

/** Shared lifecycle bridge only; TV supplies its own focusable rendering. */
@Composable
fun SeerrScreenEffects(
    viewModel: SeerrViewModel,
    onOpenLibrary: (SeerrLibraryItem) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val openLibrary by rememberUpdatedState(onOpenLibrary)
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.accept(SeerrIntent.Resumed)
            viewModel.libraryEffects.collect { viewModel.consumeLibraryEffect(it, openLibrary) }
        }
    }
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) viewModel.accept(SeerrIntent.Disposed)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.accept(SeerrIntent.Disposed)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeerrContent(
    state: SeerrUiState,
    session: Session,
    onSettingsClick: () -> Unit,
    bottomContentPadding: Dp,
    onIntent: (SeerrIntent) -> Unit,
    modifier: Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(
        modifier
            .fillMaxSize()
            .background(LocalAppBackgroundBrush.current)
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    keyboard?.hide()
                    focus.clearFocus()
                })
            }.onPreviewKeyEvent {
                if (it.key == Key.Escape && it.type == KeyEventType.KeyDown && state.detail != null) {
                    onIntent(SeerrIntent.Back)
                    true
                } else {
                    false
                }
            },
    ) {
        if (state.detail == null) {
            SeerrBrowseControls(
                state.section,
                state.catalog,
                state.hideAvailable,
                browseEligible = state.ownerValid && !state.restricted && state.connected && !state.connection.busy,
                onIntent = onIntent,
                refreshEnabled = !state.feed.loading,
            )
        }
        when {
            !state.ownerValid -> Text(stringResource(Res.string.seerr_owner_changed), Modifier.padding(Dimensions.screenPadding))
            state.section == SeerrSection.Connection -> SeerrConnectionContent(state.connection, onIntent, Modifier.weight(1f))
            state.restricted -> Text(stringResource(Res.string.seerr_restricted), Modifier.padding(Dimensions.screenPadding))
            !state.connected ->
                Column(Modifier.padding(Dimensions.screenPadding), verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing)) {
                    Text(stringResource(Res.string.seerr_setup))
                    state.connection.error?.let { Text(seerrErrorText(it), color = MaterialTheme.colorScheme.error) }
                    if (state.connection.busy) Text(stringResource(Res.string.seerr_loading))
                    Button(
                        onClick = { onIntent(SeerrIntent.SelectSection(SeerrSection.Connection)) },
                    ) { Text(stringResource(Res.string.seerr_manage)) }
                }
            state.person != null ->
                key(state.person.entryId) {
                    SeerrPersonContent(state.person, session, onIntent, Modifier.weight(1f))
                }
            state.detail != null ->
                key(
                    state.detail.entryId,
                ) {
                    SeerrDetailSurface(session, state.detail, Modifier.weight(1f)) {
                        SeerrDetailContent(state.detail, onIntent, session = session)
                    }
                }
            else ->
                key(state.section, state.catalog, state.submittedQuery, state.feed.resetRevision) {
                    SeerrFeedContent(state, session, bottomContentPadding, onIntent, Modifier.weight(1f))
                }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeerrFeedContent(
    state: SeerrUiState,
    session: Session,
    bottomContentPadding: Dp,
    onIntent: (SeerrIntent) -> Unit,
    modifier: Modifier,
) {
    val horizontal = adaptiveHorizontalContentPadding()
    val feed = state.feed
    val scroll = rememberLazyGridState(feed.scroll.index, feed.scroll.offset)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
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
    val gridOffset =
        (if (state.section == SeerrSection.Search) 1 else 0) +
            (if (feed.loading || feed.error != null || feed.rows.isEmpty()) 1 else 0)
    LoadMoreOnApproachEnd(
        itemCount = feed.rows.size,
        hasMore = feed.hasMore,
        isLoading = feed.loading && feed.rows.isEmpty(),
        isLoadingMore = feed.loading,
        automaticPagingAllowed = feed.error == null && !noGrowth && requestedSize == null,
        lastVisibleIndex = { (scroll.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0) - gridOffset },
        onLoadMore = { loadMore() },
    )
    LaunchedEffect(scroll) {
        snapshotFlow {
            if (scroll.isScrollInProgress) null else SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { onIntent(SeerrIntent.RememberScroll(false, it, state.section)) }
    }

    fun submit() {
        keyboard?.hide()
        focus.clearFocus()
        onIntent(SeerrIntent.SubmitSearch)
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Dimensions.gridMinCellWidth.tileScaled()),
        state = scroll,
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = horizontal.start,
                end = horizontal.end,
                top = Dimensions.libraryInnerViewContentSpacing,
                bottom =
                    bottomContentPadding + Dimensions.screenPadding,
            ),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
    ) {
        if (state.section == SeerrSection.Search) {
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { onIntent(SeerrIntent.QueryChanged(it)) },
                        label = { Text(stringResource(Res.string.seerr_search)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.widthIn(max = Dimensions.formControlMaxWidth).fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing)) {
                        Button(
                            onClick = ::submit,
                            enabled = state.query.isNotBlank(),
                        ) { Text(stringResource(Res.string.seerr_search_submit)) }
                        TextButton(onClick = {
                            keyboard?.hide()
                            focus.clearFocus()
                            onIntent(SeerrIntent.ClearSearch)
                        }) { Text(stringResource(Res.string.seerr_clear)) }
                    }
                    if (state.submittedQuery.isNotEmpty()) Text(stringResource(Res.string.seerr_search_results, state.submittedQuery))
                }
            }
        }
        if (feed.loading || feed.error != null || feed.rows.isEmpty()) {
            item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
                    if (state.section == SeerrSection.Search &&
                        state.submittedQuery.isEmpty()
                    ) {
                        Text(stringResource(Res.string.seerr_search_prompt))
                    }
                    if (feed.loading) Text(stringResource(Res.string.seerr_loading))
                    feed.error?.let {
                        Text(seerrErrorText(it), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = {
                            if (feed.page == 0) onIntent(SeerrIntent.Refresh) else loadMore(deliberate = true)
                        }, enabled = !feed.loading) { Text(stringResource(Res.string.seerr_retry)) }
                    }
                    if (feed.loaded &&
                        feed.rows.isEmpty() &&
                        !feed.loading &&
                        feed.error == null
                    ) {
                        Text(
                            stringResource(
                                if (state.section ==
                                    SeerrSection.Requests
                                ) {
                                    Res.string.seerr_requests_empty
                                } else {
                                    if (state.hideAvailable &&
                                        state.section == SeerrSection.Discover
                                    ) {
                                        Res.string.seerr_filtered_empty
                                    } else {
                                        Res.string.seerr_empty
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }
        items(feed.rows, key = { it.id }, contentType = { "title" }) { row ->
            val title = row.media.title ?: stringResource(Res.string.seerr_untitled)
            val requester = remember(row.id) { FocusRequester() }
            val restoreId = remember { feed.selectedId }
            LaunchedEffect(restoreId) { if (restoreId == row.id) requester.requestFocusSafely() }
            Column {
                MediaCard(
                    item = row.card.copy(title = title),
                    session = session,
                    fillWidth = true,
                    modifier =
                        Modifier
                            .focusRequester(
                                requester,
                            ).onFocusChanged { if (it.isFocused) onIntent(SeerrIntent.Focused(row.id)) },
                    onClick = {
                        keyboard?.hide()
                        focus.clearFocus()
                        onIntent(
                            SeerrIntent.RememberScroll(
                                false,
                                SeerrScrollPosition(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset),
                            ),
                        )
                        onIntent(SeerrIntent.OpenDetail(row))
                    },
                    artwork = { SeerrPoster(row.media, Modifier.fillMaxWidth()) },
                )
            }
        }
        if (feed.hasMore && feed.error == null && (feed.rows.isEmpty() || noGrowth)) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        enabled = !feed.loading,
                        onClick = { loadMore(deliberate = true) },
                    ) { Text(stringResource(Res.string.seerr_load_more)) }
                }
            }
        }
    }
}

@Composable
fun seerrSectionText(section: SeerrSection): String =
    stringResource(
        when (section) {
            SeerrSection.Discover -> Res.string.seerr_discover
            SeerrSection.Requests -> Res.string.seerr_my_requests
            SeerrSection.Search -> Res.string.seerr_search_submit
            SeerrSection.Connection -> Res.string.seerr_manage
        },
    )

@Composable
fun seerrCatalogText(catalog: SeerrCatalog): String =
    stringResource(
        when (catalog) {
            SeerrCatalog.Trending -> Res.string.seerr_trending
            SeerrCatalog.Movies -> Res.string.seerr_movies
            SeerrCatalog.Tv -> Res.string.seerr_tv
            SeerrCatalog.Search -> Res.string.seerr_search
        },
    )
