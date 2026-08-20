// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.jellyscope.core.domain.model.Session
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.rememberChildRequester
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.focus.rememberRestoreAwareBringIntoViewSpec
import com.jellyscope.ui.screen.find.FindResultTab
import com.jellyscope.ui.screen.find.FindResultsUi
import com.jellyscope.ui.screen.find.FindUiState
import com.jellyscope.ui.screen.find.PersonUi
import com.jellyscope.ui.screen.find.findResultTabsInOrder
import com.jellyscope.ui.screen.find.hasActiveFindUi
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.AdaptiveSpinner as TvSpinner
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.DetailTitleStyle as TvTitleStyle

@Composable
internal fun TvFindResultsPane(
    session: Session,
    state: FindUiState,
    resultsFocusRequester: FocusRequester,
    contentStartPadding: Dp,
    onRecentSelected: (String) -> Unit,
    onClearRecentSearches: () -> Unit,
    onPersonSelected: (PersonUi) -> Unit,
    onResultTabSelected: (FindResultTab) -> Unit,
    onRetry: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
) {
    if (state.queryText.isBlank() && state.recentSearches.isNotEmpty()) {
        TvFindChipSection(
            title = stringResource(R.string.tv_find_recent_searches),
            contentStartPadding = contentStartPadding,
        ) {
            items(
                items = state.recentSearches,
                key = { search -> search },
            ) { search ->
                TvFindChip(
                    label = search,
                    selected = false,
                    onClick = { onRecentSelected(search) },
                )
            }
            item(key = "clear-recent-searches") {
                TvFindChip(
                    label = stringResource(R.string.tv_find_clear_recent_searches),
                    selected = false,
                    onClick = onClearRecentSearches,
                )
            }
        }
    }
    if (state.personSuggestions.isNotEmpty()) {
        TvFindPersonSection(
            people = state.personSuggestions,
            contentStartPadding = contentStartPadding,
            onPersonSelected = onPersonSelected,
        )
    }
    if (state.isSearching) {
        Row(
            modifier =
                Modifier.padding(
                    start = contentStartPadding,
                    end = TvDimens.overscanHorizontal,
                ),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvSpinner(size = TvDimens.playerIconSize)
            TvText(
                text = stringResource(R.string.tv_find_loading),
                color = LocalJellyfinPalette.current.textSecondary,
            )
        }
    }
    if (state.error) {
        Row(
            modifier =
                Modifier.padding(
                    start = contentStartPadding,
                    end = TvDimens.overscanHorizontal,
                ),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvText(
                text = stringResource(R.string.tv_find_error),
                color = LocalJellyfinPalette.current.error,
            )
            TvButton(
                text = stringResource(R.string.tv_retry),
                onClick = onRetry,
            )
        }
    }
    if (!state.groupedResults.isEmpty) {
        TvFindTabs(
            selected = state.selectedResultTab,
            contentStartPadding = contentStartPadding,
            onResultTabSelected = onResultTabSelected,
        )
        TvFindResultSections(
            session = session,
            selectedTab = state.selectedResultTab,
            results = state.groupedResults,
            resultsFocusRequester = resultsFocusRequester,
            contentStartPadding = contentStartPadding,
            onItemSelected = onItemSelected,
            onItemPlayDirect = onItemPlayDirect,
        )
    } else if (!state.isSearching && !state.error && state.hasActiveFindUi()) {
        TvText(
            text = stringResource(R.string.tv_find_empty),
            color = LocalJellyfinPalette.current.textSecondary,
            modifier =
                Modifier.padding(
                    start = contentStartPadding,
                    end = TvDimens.overscanHorizontal,
                ),
        )
    } else if (state.queryText.isBlank() && state.recentSearches.isEmpty()) {
        TvText(
            text = stringResource(R.string.tv_find_prompt),
            color = LocalJellyfinPalette.current.textSecondary,
            modifier =
                Modifier.padding(
                    start = contentStartPadding,
                    end = TvDimens.overscanHorizontal,
                ),
        )
    }
}

@Composable
private fun TvFindChipSection(
    title: String,
    contentStartPadding: Dp,
    content: LazyListScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.findResultsHeaderGap)) {
        TvText(
            text = title,
            style = TvTitleStyle,
            maxLines = 1,
            modifier = Modifier.padding(start = contentStartPadding),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(TvDimens.browseControlsGap),
            contentPadding =
                PaddingValues(
                    start = contentStartPadding + TvDimens.focusBorder,
                    top = TvDimens.focusBorder,
                    end = TvDimens.overscanHorizontal + TvDimens.rowGap,
                    bottom = TvDimens.focusBorder,
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun TvFindPersonSection(
    people: List<PersonUi>,
    contentStartPadding: Dp,
    onPersonSelected: (PersonUi) -> Unit,
) {
    TvFindChipSection(
        title = stringResource(R.string.tv_find_people),
        contentStartPadding = contentStartPadding,
    ) {
        items(
            items = people,
            key = { person -> person.id },
        ) { person ->
            TvFindChip(
                label = person.name,
                selected = false,
                onClick = { onPersonSelected(person) },
                modifier = Modifier.width(TvDimens.findPersonChipWidth),
            )
        }
    }
}

@Composable
private fun TvFindTabs(
    selected: FindResultTab,
    contentStartPadding: Dp,
    onResultTabSelected: (FindResultTab) -> Unit,
) {
    TvFindChipSection(
        title = stringResource(R.string.tv_find_results),
        contentStartPadding = contentStartPadding,
    ) {
        items(
            items = findResultTabsInOrder,
            key = { tab -> tab.name },
        ) { tab ->
            TvFindChip(
                label = findTabLabel(tab),
                selected = selected == tab,
                onClick = { onResultTabSelected(tab) },
            )
        }
    }
}

@Composable
private fun TvFindChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(percent = 50)
    val foreground =
        when {
            focused -> LocalJellyfinPalette.current.onFocusedLight
            selected -> LocalJellyfinPalette.current.cyan
            else -> LocalJellyfinPalette.current.textPrimary
        }

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(if (focused) Color.White else LocalJellyfinPalette.current.surfaceRaised)
                .onFocusChanged { state -> focused = state.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ).focusable()
                .semantics {
                    contentDescription = label
                    role = Role.Button
                }.padding(
                    horizontal = TvDimens.buttonHorizontalPadding,
                    vertical = TvDimens.buttonVerticalPadding,
                ),
        contentAlignment = Alignment.Center,
    ) {
        TvText(
            text = label,
            color = foreground,
            style = TvBodyStyle.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

@Composable
private fun TvFindResultSections(
    session: Session,
    selectedTab: FindResultTab,
    results: FindResultsUi,
    resultsFocusRequester: FocusRequester,
    contentStartPadding: Dp,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
) {
    var nextResultFocusRequester: FocusRequester? = resultsFocusRequester

    fun focusRequesterFor(items: List<MediaCardUi>): FocusRequester? {
        val requester = if (items.isNotEmpty()) nextResultFocusRequester else null
        if (requester != null) {
            nextResultFocusRequester = null
        }
        return requester
    }

    if (selectedTab == FindResultTab.All || selectedTab == FindResultTab.Movies) {
        TvFindResultRow(
            rowKey = "movies",
            title = stringResource(R.string.tv_find_tab_movies),
            session = session,
            items = results.movies,
            focusRequester = focusRequesterFor(results.movies),
            contentStartPadding = contentStartPadding,
            onItemSelected = onItemSelected,
            onItemPlayDirect = onItemPlayDirect,
        )
    }
    if (selectedTab == FindResultTab.All || selectedTab == FindResultTab.Shows) {
        TvFindResultRow(
            rowKey = "shows",
            title = stringResource(R.string.tv_find_tab_shows),
            session = session,
            items = results.shows,
            focusRequester = focusRequesterFor(results.shows),
            contentStartPadding = contentStartPadding,
            onItemSelected = onItemSelected,
            onItemPlayDirect = onItemPlayDirect,
        )
    }
    if (selectedTab == FindResultTab.All || selectedTab == FindResultTab.Episodes) {
        TvFindResultRow(
            rowKey = "episodes",
            title = stringResource(R.string.tv_find_tab_episodes),
            session = session,
            items = results.episodes,
            focusRequester = focusRequesterFor(results.episodes),
            contentStartPadding = contentStartPadding,
            onItemSelected = onItemSelected,
            onItemPlayDirect = onItemPlayDirect,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvFindResultRow(
    rowKey: String,
    title: String,
    session: Session,
    items: List<MediaCardUi>,
    focusRequester: FocusRequester?,
    contentStartPadding: Dp,
    onItemSelected: (MediaCardUi) -> Unit,
    onItemPlayDirect: (MediaCardUi) -> Unit,
) {
    if (items.isEmpty()) {
        return
    }
    val firstItemId = items.first().id
    val rowScope = rememberTvFocusScopeNode(listOf("find", "row:$rowKey"))
    val rowState = rememberLazyListState()
    val restoreRequest = rowScope.restoreRequest()
    val restoreAwareSpec =
        rememberRestoreAwareBringIntoViewSpec(LocalBringIntoViewSpec.current, rowScope.restoreHandoffActive)
    LaunchedEffect(restoreRequest, items, rowState) {
        val request = restoreRequest ?: return@LaunchedEffect
        rowScope.restoreFocus(
            request = request,
            semanticKeys = items.map { item -> "item:${item.id}" },
            lazySlotIndex = { it },
            revealCentered = { index ->
                rowState.scrollToItem(index)
                val info = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                if (info != null) {
                    val viewportCenter =
                        (rowState.layoutInfo.viewportStartOffset + rowState.layoutInfo.viewportEndOffset) / 2
                    rowState.scrollToItem(index, -(viewportCenter - info.size / 2))
                }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(TvDimens.detailShelfTitleGap)) {
        TvText(
            text = title,
            style = TvTitleStyle,
            maxLines = 1,
            modifier = Modifier.padding(start = contentStartPadding),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides restoreAwareSpec) {
            LazyRow(
                state = rowState,
                modifier =
                    Modifier.tvFocusScope(rowScope) {
                        items.map { item -> "item:${item.id}" }
                    },
                horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
                contentPadding =
                    PaddingValues(
                        start = contentStartPadding + TvDimens.focusBorder,
                        top = TvDimens.focusBorder,
                        end = TvDimens.overscanHorizontal + TvDimens.rowGap,
                        bottom = TvDimens.detailShelfBottomPadding,
                    ),
            ) {
                items(
                    items = items,
                    key = { item -> item.id },
                    contentType = { "find-result" },
                ) { item ->
                    val itemKey = "item:${item.id}"
                    val itemRequester =
                        if (item.id == firstItemId && focusRequester != null) {
                            rowScope.rememberChildRequester(itemKey, focusRequester)
                        } else {
                            rowScope.rememberChildRequester(itemKey)
                        }
                    val onItemClick = remember(item.id, onItemSelected) { { onItemSelected(item) } }
                    val onItemPlay = remember(item.id, onItemPlayDirect) { { onItemPlayDirect(item) } }
                    TvMediaCard(
                        session = session,
                        item = item,
                        wide = false,
                        modifier = Modifier,
                        focusRequester = itemRequester,
                        onFocused = {
                            rowScope.onChildFocused(itemKey, TvFocusTargetKind.Item, items.indexOf(item))
                        },
                        onClick = onItemClick,
                        onPlayDirect = onItemPlay,
                    )
                }
            }
        }
    }
}

@Composable
private fun findTabLabel(tab: FindResultTab): String =
    when (tab) {
        FindResultTab.All -> stringResource(R.string.tv_find_tab_all)
        FindResultTab.Movies -> stringResource(R.string.tv_find_tab_movies)
        FindResultTab.Shows -> stringResource(R.string.tv_find_tab_shows)
        FindResultTab.Episodes -> stringResource(R.string.tv_find_tab_episodes)
    }
