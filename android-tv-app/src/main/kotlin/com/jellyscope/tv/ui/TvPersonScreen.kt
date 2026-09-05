// SPDX-License-Identifier: MPL-2.0

@file:OptIn(ExperimentalFoundationApi::class)

package com.jellyscope.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.LoadMoreOnApproachEnd
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.focus.FocusRestoreRequest
import com.jellyscope.ui.focus.FocusRestoreTarget
import com.jellyscope.ui.focus.rememberNoAutoScrollSpec
import com.jellyscope.ui.focus.rememberRestoreAwareBringIntoViewSpec
import com.jellyscope.ui.focus.resolveFocusRestoreTarget
import com.jellyscope.ui.screen.detail.DetailFocusContainer
import com.jellyscope.ui.screen.detail.focusChild
import com.jellyscope.ui.screen.detail.focusContainer
import com.jellyscope.ui.screen.detail.rememberRouteDetailFocusContainer
import com.jellyscope.ui.screen.detail.requestFocusSafely
import com.jellyscope.ui.screen.person.PersonHeaderUi
import com.jellyscope.ui.screen.person.PersonUiState
import com.jellyscope.ui.screen.person.PersonViewModel
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import com.jellyscope.ui.component.AdaptiveCenteredSpinner as TvCenteredSpinner
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailHeadlineStyle as TvHeadlineStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.DetailTitleStyle as TvTitleStyle

@Composable
fun TvPersonScreen(
    session: Session,
    personId: String,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit,
    onFindSelected: () -> Unit,
    onFavoritesSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonViewModel =
        koinViewModel(
            key = "person-$personId",
            parameters = { parametersOf(session, personId) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    TvPersonContent(
        session = session,
        state = state,
        onBack = onBack,
        onHomeSelected = onHomeSelected,
        onFindSelected = onFindSelected,
        onFavoritesSelected = onFavoritesSelected,
        onSettingsSelected = onSettingsSelected,
        onItemSelected = onItemSelected,
        onItemPlayDirect = onItemPlayDirect,
        onRetry = viewModel::retry,
        onLoadMoreAutomatically = viewModel::loadMoreAutomatically,
        onRetryPage = viewModel::loadMore,
        modifier = modifier,
    )
}

@Composable
private fun TvPersonContent(
    session: Session,
    state: PersonUiState,
    onBack: () -> Unit,
    onHomeSelected: () -> Unit,
    onFindSelected: () -> Unit,
    onFavoritesSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onRetry: () -> Unit,
    onLoadMoreAutomatically: () -> Unit,
    onRetryPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val moviesFocus = rememberRouteDetailFocusContainer("person/movies")
    val seriesFocus = rememberRouteDetailFocusContainer("person/series")
    val scrollState = rememberScrollState()
    // Preserve default horizontal ribbon scrolling.
    val ribbonBringIntoViewSpec = LocalBringIntoViewSpec.current
    val noVerticalAutoScrollSpec = rememberNoAutoScrollSpec()
    // Keep per-frame placement geometry out of snapshot state.
    val geometry = remember { PersonScrollGeometry() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        val content = state as? PersonUiState.Content ?: return@LaunchedEffect
        if (!initialFocusRequested && (content.movies.isNotEmpty() || content.series.isNotEmpty())) {
            if (moviesFocus.restorePending() || seriesFocus.restorePending()) {
                initialFocusRequested = true
                return@LaunchedEffect
            }
            withFrameNanos { }
            val entry = if (content.movies.isNotEmpty()) moviesFocus else seriesFocus
            entry.entryRequester.requestFocusSafely()
            initialFocusRequested = true
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalAppBackgroundBrush.current)
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.Back &&
                        run {
                            onBack()
                            true
                        }
                },
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides noVerticalAutoScrollSpec) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .fillMaxHeight()
                        .focusGroup()
                        .onPlaced { coordinates ->
                            geometry.viewportTopInRoot = coordinates.positionInRoot().y
                            geometry.viewportHeight = coordinates.size.height
                        }.verticalScroll(scrollState)
                        .padding(
                            top = TvDimens.overscanVertical,
                            bottom = TvDimens.overscanVertical,
                        ),
                verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
            ) {
                when (state) {
                    PersonUiState.Loading ->
                        TvCenteredSpinner(drawBackground = false)
                    is PersonUiState.Error ->
                        TvPersonError(
                            retryable = state.retryable,
                            onRetry = onRetry,
                        )
                    is PersonUiState.Content ->
                        TvPersonLoadedContent(
                            session = session,
                            state = state,
                            moviesFocus = moviesFocus,
                            seriesFocus = seriesFocus,
                            scrollState = scrollState,
                            geometry = geometry,
                            ribbonBringIntoViewSpec = ribbonBringIntoViewSpec,
                            onItemSelected = onItemSelected,
                            onItemPlayDirect = onItemPlayDirect,
                            onLoadMoreAutomatically = onLoadMoreAutomatically,
                            onRetryPage = onRetryPage,
                        )
                }
            }
        }
    }
}

@Composable
private fun TvPersonLoadedContent(
    session: Session,
    state: PersonUiState.Content,
    moviesFocus: DetailFocusContainer,
    seriesFocus: DetailFocusContainer,
    scrollState: ScrollState,
    geometry: PersonScrollGeometry,
    ribbonBringIntoViewSpec: BringIntoViewSpec,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onLoadMoreAutomatically: () -> Unit,
    onRetryPage: () -> Unit,
) {
    val moviesRestoreRequest = moviesFocus.restoreRequest()
    val seriesRestoreRequest = seriesFocus.restoreRequest()
    val pageRetryRequester = remember { FocusRequester() }
    val pageRetryFocusScope = rememberCoroutineScope()
    var retryReturnFocus by remember { mutableStateOf<DetailFocusContainer?>(null) }
    val requestPageRetryFocus: (DetailFocusContainer) -> Boolean = { returnFocus ->
        retryReturnFocus = returnFocus
        pageRetryFocusScope.launch {
            scrollState.animateScrollTo(scrollState.maxValue)
            requestTvFocusWithRetry { pageRetryRequester.requestFocusSafely() }
        }
        true
    }
    LaunchedEffect(state.error, state.movies.isEmpty(), state.series.isEmpty()) {
        if (state.error && state.movies.isEmpty() && state.series.isEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
            requestTvFocusWithRetry { pageRetryRequester.requestFocusSafely() }
        }
    }
    LaunchedEffect(
        moviesRestoreRequest,
        seriesRestoreRequest,
        state.movies,
        state.series,
        state.hasMore,
        state.isLoadingMore,
        state.error,
    ) {
        if (!state.hasMore || state.isLoadingMore || state.error) return@LaunchedEffect
        val targetsEmptyRow =
            (moviesRestoreRequest != null && state.movies.isEmpty()) ||
                (seriesRestoreRequest != null && state.series.isEmpty())
        if (targetsEmptyRow) onLoadMoreAutomatically()
    }

    // Section-zero focus reveals the full portrait.
    val sectionZeroScrollModifier =
        personSectionFocusScroll(scrollState) { 0 }
    val sectionOneScrollModifier =
        personSectionFocusScroll(scrollState) {
            personSectionScrollTarget(
                sectionTopInViewport = geometry.sectionOneTopInViewport(),
                sectionHeight = geometry.sectionOneHeight,
                viewportHeight = geometry.viewportHeight,
                scrollValue = scrollState.value,
                maxValue = scrollState.maxValue,
            )
        }

    Column(
        modifier = sectionZeroScrollModifier,
        verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
    ) {
        TvPersonHeader(
            session = session,
            header = state.header,
            modifier = Modifier.padding(horizontal = TvDimens.overscanHorizontal),
        )
        if (state.movies.isNotEmpty()) {
            TvPersonMediaRow(
                session = session,
                title = stringResource(R.string.tv_person_movies),
                items = state.movies,
                hasMore = state.hasMore,
                isLoadingMore = state.isLoadingMore,
                pageError = state.error,
                rowFocus = moviesFocus,
                ribbonBringIntoViewSpec = ribbonBringIntoViewSpec,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                onLoadMoreAutomatically = onLoadMoreAutomatically,
                onRequestPageRetryFocus = requestPageRetryFocus,
            )
        } else if (state.series.isNotEmpty()) {
            TvPersonMediaRow(
                session = session,
                title = stringResource(R.string.tv_person_series),
                items = state.series,
                hasMore = state.hasMore,
                isLoadingMore = state.isLoadingMore,
                pageError = state.error,
                rowFocus = seriesFocus,
                ribbonBringIntoViewSpec = ribbonBringIntoViewSpec,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                onLoadMoreAutomatically = onLoadMoreAutomatically,
                onRequestPageRetryFocus = requestPageRetryFocus,
            )
        } else {
            TvText(
                text = stringResource(R.string.tv_empty_row),
                modifier = Modifier.padding(horizontal = TvDimens.overscanHorizontal),
                color = LocalJellyfinPalette.current.textSecondary,
            )
        }
    }

    if (state.movies.isNotEmpty() && state.series.isNotEmpty()) {
        Column(
            modifier =
                sectionOneScrollModifier.onPlaced { coordinates ->
                    geometry.sectionOneTopInRoot = coordinates.positionInRoot().y
                    geometry.sectionOneHeight = coordinates.size.height
                },
            verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
        ) {
            TvPersonMediaRow(
                session = session,
                title = stringResource(R.string.tv_person_series),
                items = state.series,
                hasMore = state.hasMore,
                isLoadingMore = state.isLoadingMore,
                pageError = state.error,
                rowFocus = seriesFocus,
                ribbonBringIntoViewSpec = ribbonBringIntoViewSpec,
                onItemSelected = onItemSelected,
                onItemPlayDirect = onItemPlayDirect,
                onLoadMoreAutomatically = onLoadMoreAutomatically,
                onRequestPageRetryFocus = requestPageRetryFocus,
            )
        }
    }

    if (state.isLoadingMore) {
        Row(
            modifier = Modifier.padding(horizontal = TvDimens.overscanHorizontal),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvSpinner(size = TvDimens.playerIconSize)
            TvText(
                text = stringResource(R.string.tv_loading_more),
                color = LocalJellyfinPalette.current.textSecondary,
            )
        }
    }
    if (state.error) {
        key(PERSON_PAGE_RETRY_KEY) {
            Row(
                modifier = Modifier.padding(horizontal = TvDimens.overscanHorizontal),
                horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvText(
                    text = stringResource(R.string.tv_row_error),
                    color = LocalJellyfinPalette.current.error,
                )
                TvButton(
                    text = stringResource(R.string.tv_retry),
                    onClick = {
                        retryReturnFocus?.requestEntry()
                        onRetryPage()
                    },
                    modifier =
                        Modifier
                            .width(TvDimens.retryButtonWidth)
                            .focusRequester(pageRetryRequester)
                            .onPreviewKeyEvent { event ->
                                event.type == KeyEventType.KeyDown &&
                                    event.key == Key.DirectionUp &&
                                    retryReturnFocus?.requestEntry() == true
                            },
                )
            }
        }
    }
}

@Composable
private fun TvPersonHeader(
    session: Session,
    header: PersonHeaderUi?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .width(TvDimens.personHeaderPosterWidth.tileScaled())
                    .height(TvDimens.personHeaderPosterHeight.tileScaled())
                    .clip(RoundedCornerShape(TvDimens.cardRadius))
                    .background(LocalJellyfinPalette.current.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            header?.imageUrl?.let { imageUrl ->
                AsyncImage(
                    model = authenticatedImageRequest(imageUrl, session),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } ?: TvText(
                text = header?.name?.take(1).orEmpty(),
                style = TvHeadlineStyle,
                color = LocalJellyfinPalette.current.textSecondary,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(TvDimens.detailHeroTextWidthFraction),
            verticalArrangement = Arrangement.spacedBy(TvDimens.detailHeroTextGap),
        ) {
            TvText(
                text = header?.name ?: stringResource(R.string.tv_person_title),
                style = TvHeadlineStyle.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
            )
            TvText(
                text = header?.overview ?: stringResource(R.string.tv_overview_fallback),
                style = TvBodyStyle,
                color = LocalJellyfinPalette.current.textSecondary,
                maxLines = 5,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvPersonMediaRow(
    session: Session,
    title: String,
    items: List<MediaCardUi>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    pageError: Boolean,
    rowFocus: DetailFocusContainer,
    ribbonBringIntoViewSpec: BringIntoViewSpec,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onLoadMoreAutomatically: () -> Unit,
    onRequestPageRetryFocus: (DetailFocusContainer) -> Boolean,
) {
    val rowState = rememberLazyListState()
    val restoreRequest = rowFocus.restoreRequest()
    val restoreAwareSpec =
        rememberRestoreAwareBringIntoViewSpec(
            ribbonBringIntoViewSpec,
            rowFocus.restoreHandoffActive,
        )
    LaunchedEffect(restoreRequest, items, hasMore, pageError, rowState) {
        val request = restoreRequest ?: return@LaunchedEffect
        if (personRowRestoreAction(request, items.map { it.id }, hasMore) == FocusRestoreTarget.Deferred) {
            // Page until the saved focus position exists.
            if (!pageError) onLoadMoreAutomatically()
            return@LaunchedEffect
        }
        rowFocus.restoreFocus(
            request = request,
            semanticKeys = items.map { it.id },
            lazySlotIndex = { it },
            contentReady = !hasMore || request.fallbackSemanticIndex < items.size,
            revealCentered = { index ->
                rowState.scrollToItem(index)
                val itemInfo = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (itemInfo != null) {
                    val viewportCenter =
                        (rowState.layoutInfo.viewportStartOffset + rowState.layoutInfo.viewportEndOffset) / 2
                    rowState.scrollToItem(index, -(viewportCenter - itemInfo.size / 2))
                }
            },
        )
    }
    // Debounce pagination from the visible-index signal.
    LoadMoreOnApproachEnd(
        itemCount = items.size,
        hasMore = hasMore,
        isLoading = false,
        isLoadingMore = isLoadingMore,
        automaticPagingAllowed = !pageError,
        lastVisibleIndex = {
            rowState.layoutInfo.visibleItemsInfo.maxOfOrNull { item -> item.index } ?: 0
        },
        threshold = PERSON_LOAD_MORE_THRESHOLD,
        onLoadMore = onLoadMoreAutomatically,
    )
    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.detailShelfTitleGap)) {
        TvText(
            text = title,
            modifier = Modifier.padding(horizontal = TvDimens.overscanHorizontal),
            style = TvTitleStyle,
            maxLines = 1,
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides restoreAwareSpec) {
            LazyRow(
                state = rowState,
                modifier = Modifier.fillMaxWidth().focusContainer(rowFocus) { listOfNotNull(items.firstOrNull()?.id) },
                horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                contentPadding =
                    PaddingValues(
                        start = TvDimens.overscanHorizontal + TvDimens.focusBorder,
                        top = TvDimens.focusBorder,
                        end = TvDimens.overscanHorizontal + TvDimens.rowGap,
                        bottom = TvDimens.detailShelfBottomPadding,
                    ),
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.id },
                ) { index, item ->
                    val onItemClick = remember(item, onItemSelected) { { onItemSelected(item) } }
                    val onItemPlay = remember(item, onItemPlayDirect) { { onItemPlayDirect(item) } }
                    TvMediaCard(
                        session = session,
                        item = item,
                        wide = false,
                        focusChildModifier =
                            Modifier
                                .onPreviewKeyEvent { event ->
                                    pageError &&
                                        index == items.lastIndex &&
                                        event.type == KeyEventType.KeyDown &&
                                        (event.key == Key.DirectionDown || event.key == Key.DirectionRight) &&
                                        onRequestPageRetryFocus(rowFocus)
                                }.focusChild(rowFocus, item.id, index),
                        onFocused = {},
                        onClick = onItemClick,
                        onPlayDirect = onItemPlay,
                    )
                }
            }
        }
    }
}

internal fun personRowRestoreAction(
    request: FocusRestoreRequest,
    itemIds: List<String>,
    hasMore: Boolean,
): FocusRestoreTarget {
    val contentReady = !hasMore || request.fallbackSemanticIndex < itemIds.size
    return resolveFocusRestoreTarget(request, itemIds, contentReady)
}

@Composable
private fun TvPersonError(
    retryable: Boolean,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.formGap)) {
        TvText(
            text = stringResource(R.string.tv_person_error),
            color = LocalJellyfinPalette.current.error,
        )
        if (retryable) {
            TvButton(
                text = stringResource(R.string.tv_retry),
                onClick = onRetry,
                modifier = Modifier.width(TvDimens.retryButtonWidth),
            )
        }
    }
}

private const val PERSON_LOAD_MORE_THRESHOLD = 5
private const val PERSON_PAGE_RETRY_KEY = "person:control:page-retry"

/** Plain placement geometry read only during focus changes. */
private class PersonScrollGeometry {
    var viewportTopInRoot: Float = 0f
    var viewportHeight: Int = 0
    var sectionOneTopInRoot: Float = 0f
    var sectionOneHeight: Int = 0

    fun sectionOneTopInViewport(): Int = (sectionOneTopInRoot - viewportTopInRoot).roundToInt()
}

/** Applies the explicit vertical target when a section gains focus. */
@Composable
private fun personSectionFocusScroll(
    scrollState: ScrollState,
    target: () -> Int,
): Modifier {
    val scope = rememberCoroutineScope()
    var hadFocus by remember { mutableStateOf(false) }

    return Modifier
        .focusGroup()
        .onFocusChanged { state ->
            if (state.hasFocus && !hadFocus) {
                scope.launch { scrollState.animateScrollTo(target()) }
            }
            hadFocus = state.hasFocus
        }
}

/** Returns the minimum scroll needed to reveal the section. */
internal fun personSectionScrollTarget(
    sectionTopInViewport: Int,
    sectionHeight: Int,
    viewportHeight: Int,
    scrollValue: Int,
    maxValue: Int,
): Int {
    if (viewportHeight <= 0 || maxValue <= 0) return scrollValue

    val sectionBottomInViewport = sectionTopInViewport + sectionHeight
    val delta =
        when {
            sectionBottomInViewport > viewportHeight -> sectionBottomInViewport - viewportHeight
            sectionTopInViewport < 0 -> sectionTopInViewport
            else -> 0
        }
    return (scrollValue + delta).coerceIn(0, maxValue)
}
