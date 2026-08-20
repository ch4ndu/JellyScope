// SPDX-License-Identifier: MPL-2.0
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jellyscope.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibraryRecommendationReason
import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.rememberChildRequester
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.component.AmbientLayer
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.focus.rememberRestoreAwareBringIntoViewSpec
import com.jellyscope.ui.screen.library.LibraryRecommendationRowState
import com.jellyscope.ui.screen.library.LibraryRecommendationRowUi
import com.jellyscope.ui.theme.LocalJellyfinPalette
import kotlinx.coroutines.delay
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@Composable
internal fun TvLibraryInnerTabs(
    views: List<LibraryInnerView>,
    selectedView: LibraryInnerView,
    focusRequesterFor: (LibraryInnerView) -> FocusRequester,
    onRequestContentFocus: (() -> Boolean)?,
    onRequestTrailingFocus: (() -> Boolean)? = null,
    onFocusRegionChanged: (TvLibraryFocusRegion) -> Unit = {},
    chromeVisible: Boolean? = null,
    onSelected: (LibraryInnerView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.tvLibraryChromeVisibility(chromeVisible).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
    ) {
        views.forEach { view ->
            TvFocusableBox(
                onClick = { onSelected(view) },
                modifier =
                    Modifier
                        .focusRequester(focusRequesterFor(view))
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                onFocusRegionChanged(TvLibraryFocusRegion.Chrome)
                            }
                        }.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when {
                                    event.key == Key.DirectionDown ->
                                        onRequestContentFocus?.invoke() == true
                                    event.key == Key.DirectionRight && view == views.lastOrNull() ->
                                        onRequestTrailingFocus?.invoke() == true
                                    else -> false
                                }
                            }
                        },
                contentDescription = tvLibraryInnerViewLabel(view),
                contentPadding =
                    PaddingValues(
                        horizontal = TvDimens.buttonHorizontalPadding,
                        vertical = TvDimens.buttonVerticalPadding,
                    ),
                backgroundColor =
                    if (view == selectedView) {
                        LocalJellyfinPalette.current.cyan.copy(alpha = 0.18f)
                    } else {
                        LocalJellyfinPalette.current.surfaceRaised
                    },
                focusedBackgroundColor = LocalJellyfinPalette.current.cyan,
            ) { focused ->
                TvText(
                    text = tvLibraryInnerViewLabel(view),
                    style = TvBodyStyle,
                    color =
                        if (focused) {
                            LocalJellyfinPalette.current.onFocusedLight
                        } else {
                            LocalJellyfinPalette.current.textPrimary
                        },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun TvLibraryRecommendedContent(
    session: Session,
    states: Map<LibraryRecommendationSection, LibraryRecommendationRowState>,
    contentRequester: FocusRequester,
    tabsRequester: FocusRequester,
    onFocusRegionChanged: (TvLibraryFocusRegion) -> Unit,
    onRetry: (LibraryRecommendationSection) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
    onAmbientPresentationChanged: (TvHeroAmbientPresentation) -> Unit = {},
    contentStartPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    var focusedItem by remember { mutableStateOf<MediaCardUi?>(null) }
    var heroItem by remember { mutableStateOf<MediaCardUi?>(null) }
    var focusedRibbonIdentity by remember { mutableStateOf<String?>(null) }
    var recommendedHasFocus by remember { mutableStateOf(false) }
    val rows =
        states.entries.flatMap { (section, state) ->
            when (state) {
                is LibraryRecommendationRowState.Content -> state.rows
                else -> emptyList()
            }.ifEmpty {
                if (state is LibraryRecommendationRowState.Error) {
                    listOf(
                        LibraryRecommendationRowUi(
                            key = "error-${section.name}",
                            section = section,
                            reason = null,
                            baselineItemName = null,
                            items = emptyList(),
                        ),
                    )
                } else {
                    emptyList()
                }
            }
        }

    if (rows.isEmpty()) {
        SideEffect {
            onFocusRegionChanged(
                if (recommendedHasFocus) {
                    TvLibraryFocusRegion.TopContent
                } else {
                    TvLibraryFocusRegion.Chrome
                },
            )
        }
        val loading = states.values.any { state -> state is LibraryRecommendationRowState.Loading }
        TvLibraryStatusFocusPark(
            text = stringResource(if (loading) R.string.tv_loading else R.string.tv_library_empty),
            focusRequester = contentRequester,
            tabsRequester = tabsRequester,
            onFocusRegionChanged = onFocusRegionChanged,
            onContentFocusChanged = { hasFocus -> recommendedHasFocus = hasFocus },
            modifier = modifier,
        )
        return
    }

    LaunchedEffect(rows, focusedItem) {
        val target = focusedItem ?: rows.firstNotNullOfOrNull { row -> row.items.firstOrNull() } ?: return@LaunchedEffect
        if (heroItem != null && heroItem?.id != target.id) delay(HERO_SETTLE_MS)
        heroItem = target
    }
    val ambientState =
        rememberTvHeroAmbientState(
            item = heroItem,
            surface = TvHeroAmbientSurface.Recommended,
            accountKey = "${session.serverId}|${session.userId}",
        )
    val listState = rememberLazyListState()
    val noVerticalAutoScrollSpec = rememberNoAutoScrollSpec()
    val horizontalBringIntoViewSpec = rememberMarioBringIntoViewSpec()
    val focusedRegion =
        if (recommendedHasFocus) {
            recommendedRibbonFocusRegion(rows, focusedRibbonIdentity)
        } else {
            null
        }
    SideEffect {
        focusedRegion?.let(onFocusRegionChanged)
    }
    SideEffect {
        onAmbientPresentationChanged(ambientState.presentation)
    }
    DisposableEffect(Unit) {
        onDispose { onAmbientPresentationChanged(TvHeroAmbientPresentation.Empty) }
    }

    Box(modifier = modifier.fillMaxSize().tvHomeAmbientBackground(ambientState.presentation)) {
        AmbientLayer(
            ambientColor = ambientState.presentation.color,
            animationOwnerKey = ambientState.presentation.animationOwnerKey,
            clearWhenColorMissing = ambientState.presentation.clearWhenColorMissing,
        )
        TvHeroBackdrop(
            session = session,
            item = heroItem,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            TvLibraryGridHero(
                session = session,
                item = heroItem,
                contentStartPadding = contentStartPadding,
                contentTopPadding = TvDimens.libraryHeroChromeContentInset,
                contentEndPadding = TvDimens.overscanHorizontal,
                height = TvDimens.libraryHeroHeight,
                showBackdrop = false,
            )
            CompositionLocalProvider(LocalBringIntoViewSpec provides noVerticalAutoScrollSpec) {
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(contentRequester)
                            .onFocusChanged { focusState ->
                                recommendedHasFocus = focusState.hasFocus
                            }.focusGroup(),
                    contentPadding = PaddingValues(bottom = TvDimens.overscanVertical),
                    verticalArrangement = Arrangement.spacedBy(TvDimens.rowGap),
                ) {
                    itemsIndexed(
                        items = rows,
                        key = { _, row -> recommendationRibbonIdentity(row.section, row.key) },
                    ) { rowIndex, row ->
                        val ribbonIdentity = recommendationRibbonIdentity(row.section, row.key)
                        Column(
                            modifier =
                                rememberScrollIntoViewOnFocusModifier(
                                    listState = listState,
                                    index = rowIndex,
                                ),
                            verticalArrangement = Arrangement.spacedBy(TvDimens.gridContentTopPadding),
                        ) {
                            if (row.items.isEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = contentStartPadding),
                                    horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                                ) {
                                    TvText(
                                        text = stringResource(R.string.tv_library_error),
                                        color = LocalJellyfinPalette.current.error,
                                    )
                                    val retryModifier =
                                        if (row == rows.first()) {
                                            Modifier.focusProperties { up = tabsRequester }
                                        } else {
                                            Modifier
                                        }
                                    TvButton(
                                        text = stringResource(R.string.tv_retry),
                                        onClick = { onRetry(row.section) },
                                        modifier =
                                            retryModifier.onFocusChanged { focusState ->
                                                if (focusState.isFocused) {
                                                    focusedRibbonIdentity = ribbonIdentity
                                                    onFocusRegionChanged(
                                                        recommendedRibbonFocusRegion(rows, ribbonIdentity),
                                                    )
                                                }
                                            },
                                    )
                                }
                            } else {
                                val rowScope =
                                    rememberTvFocusScopeNode(
                                        listOf("library", "recommended", "row:$ribbonIdentity"),
                                    )
                                val rowState = rememberLazyListState()
                                val restoreRequest = rowScope.restoreRequest()
                                val restoreAwareSpec =
                                    rememberRestoreAwareBringIntoViewSpec(
                                        horizontalBringIntoViewSpec,
                                        rowScope.restoreHandoffActive,
                                    )
                                LaunchedEffect(restoreRequest, row.items, rowState) {
                                    val request = restoreRequest ?: return@LaunchedEffect
                                    rowScope.restoreFocus(
                                        request = request,
                                        semanticKeys = row.items.map { item -> "item:${item.id}" },
                                        lazySlotIndex = { it },
                                        revealCentered = { index ->
                                            rowState.scrollToItem(index)
                                            val info = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                            if (info != null) {
                                                val viewportCenter =
                                                    (
                                                        rowState.layoutInfo.viewportStartOffset +
                                                            rowState.layoutInfo.viewportEndOffset
                                                    ) / 2
                                                rowState.scrollToItem(index, -(viewportCenter - info.size / 2))
                                            }
                                        },
                                    )
                                }
                                TvText(
                                    text = tvRecommendationRowLabel(row),
                                    modifier =
                                        Modifier.padding(
                                            start = contentStartPadding,
                                            end = TvDimens.overscanHorizontal,
                                        ),
                                    style = TvBodyStyle,
                                )
                                CompositionLocalProvider(LocalBringIntoViewSpec provides restoreAwareSpec) {
                                    LazyRow(
                                        state = rowState,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .tvFocusScope(rowScope) {
                                                    row.items.map { item -> "item:${item.id}" }
                                                },
                                        contentPadding =
                                            PaddingValues(
                                                start = contentStartPadding + TvDimens.gridContentHorizontalPadding,
                                                top = TvDimens.gridContentTopPadding,
                                                end = TvDimens.overscanHorizontal + TvDimens.gridContentHorizontalPadding,
                                                bottom = TvDimens.gridContentTopPadding,
                                            ),
                                        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                                    ) {
                                        // itemsIndexed, so the semantic index comes
                                        // from the item lambda rather than an O(n)
                                        // `row.items.indexOf(item)` per focus event.
                                        itemsIndexed(row.items, key = { _, item -> item.id }) { index, item ->
                                            val firstRow = row == rows.firstOrNull()
                                            val itemKey = "item:${item.id}"
                                            val itemRequester = rowScope.rememberChildRequester(itemKey)
                                            TvMediaCard(
                                                session = session,
                                                item = item,
                                                wide = false,
                                                focusRequester = itemRequester,
                                                modifier =
                                                    if (firstRow) {
                                                        Modifier.focusProperties { up = tabsRequester }
                                                    } else {
                                                        Modifier
                                                    },
                                                onFocused = {
                                                    focusedRibbonIdentity = ribbonIdentity
                                                    onFocusRegionChanged(
                                                        recommendedRibbonFocusRegion(rows, ribbonIdentity),
                                                    )
                                                    rowScope.onChildFocused(
                                                        itemKey,
                                                        TvFocusTargetKind.Item,
                                                        index,
                                                    )
                                                    focusedItem = item
                                                },
                                                onPosterLoaded = { bitmap -> ambientState.onPosterLoaded(item.id, bitmap) },
                                                onClick = { onItemSelected(item) },
                                                onPlayDirect = { onItemPlayDirect(item) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun recommendationRibbonIdentity(
    section: LibraryRecommendationSection,
    rowKey: String,
): String = "${section.name}:$rowKey"

private fun recommendedRibbonFocusRegion(
    rows: List<LibraryRecommendationRowUi>,
    focusedRibbonIdentity: String?,
): TvLibraryFocusRegion {
    val rowIndex =
        focusedRibbonIdentity?.let { identity ->
            rows.indexOfFirst { row ->
                recommendationRibbonIdentity(row.section, row.key) == identity
            }
        } ?: -1
    return when {
        rowIndex == 0 -> TvLibraryFocusRegion.TopContent
        rowIndex > 0 -> TvLibraryFocusRegion.LowerContent
        else -> TvLibraryFocusRegion.Chrome
    }
}

@Composable
private fun TvLibraryStatusFocusPark(
    text: String,
    focusRequester: FocusRequester,
    tabsRequester: FocusRequester,
    onFocusRegionChanged: (TvLibraryFocusRegion) -> Unit,
    onContentFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusProperties { up = tabsRequester }
                .onFocusChanged { focusState ->
                    onContentFocusChanged(focusState.hasFocus)
                    if (focusState.isFocused) {
                        onFocusRegionChanged(TvLibraryFocusRegion.TopContent)
                    }
                }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        TvText(text = text, color = LocalJellyfinPalette.current.textSecondary)
    }
}

@Composable
private fun tvLibraryInnerViewLabel(view: LibraryInnerView): String =
    when (view) {
        LibraryInnerView.Recommended -> stringResource(R.string.tv_library_recommended)
        LibraryInnerView.Library -> stringResource(R.string.tv_library_library)
        // Retained enum entries that are no longer offered as tabs.
        LibraryInnerView.Genres,
        LibraryInnerView.Collections,
        -> ""
    }

@Composable
private fun tvRecommendationRowLabel(row: LibraryRecommendationRowUi): String {
    val baseline = row.baselineItemName.orEmpty()
    return when (row.section) {
        LibraryRecommendationSection.ContinueWatching -> stringResource(R.string.tv_continue_watching)
        LibraryRecommendationSection.RecentlyAdded -> stringResource(R.string.tv_recently_added)
        LibraryRecommendationSection.NextUp -> stringResource(R.string.tv_next_up)
        LibraryRecommendationSection.MovieRecommendations ->
            when (row.reason) {
                LibraryRecommendationReason.SimilarToRecentlyPlayed -> stringResource(R.string.tv_library_because_watched, baseline)
                LibraryRecommendationReason.SimilarToLikedItem -> stringResource(R.string.tv_library_because_liked, baseline)
                LibraryRecommendationReason.HasDirector -> stringResource(R.string.tv_library_directed_by, baseline)
                LibraryRecommendationReason.HasActor -> stringResource(R.string.tv_library_starring, baseline)
                else -> stringResource(R.string.tv_library_recommended)
            }
    }
}
