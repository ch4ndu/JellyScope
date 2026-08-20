// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.activeCount
import com.jellyscope.tv.R
import com.jellyscope.tv.ui.focus.LocalTvFocusCoordinator
import com.jellyscope.tv.ui.focus.TvFocusTargetKind
import com.jellyscope.tv.ui.focus.rememberTvFocusScopeNode
import com.jellyscope.tv.ui.focus.tvFocusScope
import com.jellyscope.ui.screen.library.LibraryBrowseUiState
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailSecondaryStyle as TvSecondaryStyle
import com.jellyscope.ui.component.DetailText as TvText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvLibraryHeader(
    title: String?,
    state: LibraryBrowseUiState,
    sortButtonRequester: FocusRequester,
    filterButtonRequester: FocusRequester,
    shuffleButtonRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    onOpenSortPicker: () -> Unit,
    onOpenFilterPicker: () -> Unit,
    onShuffleAll: () -> Unit,
    onChromeFocused: () -> Unit = {},
    chromeVisible: Boolean? = null,
    innerTabs: @Composable () -> Unit,
    reserveLeadingSpaceForTabs: Boolean = false,
    tabsUpRequester: FocusRequester? = null,
    tabsStartRequester: FocusRequester? = null,
    onRequestGridFocus: (() -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val coordinator = requireNotNull(LocalTvFocusCoordinator.current)
    val headerScope = rememberTvFocusScopeNode(listOf("library", "header"))
    val actionRequesters =
        listOf(
            "action:shuffle" to shuffleButtonRequester,
            "action:sort" to sortButtonRequester,
            "action:filter" to filterButtonRequester,
        )
    actionRequesters.forEach { (key, requester) ->
        DisposableEffect(headerScope, key, requester) {
            headerScope.register(key, requester)
            onDispose { headerScope.unregister(key, requester) }
        }
    }
    LaunchedEffect(coordinator.readyRestore, state.collectionType) {
        val restore = coordinator.readyRestore ?: return@LaunchedEffect
        if (restore.path.scopes == headerScope.scopes) {
            headerScope.requestEntry(listOf(restore.path.targetKey))
        }
    }

    fun Modifier.trackHeaderFocus(key: String): Modifier =
        onFocusChanged { focusState ->
            if (focusState.isFocused) {
                onChromeFocused()
                val path = headerScope.path(key, TvFocusTargetKind.Action, 0)
                coordinator.recordFocused(path)
                coordinator.readyRestore
                    ?.takeIf { restore -> restore.path == path }
                    ?.let { restore -> coordinator.resolveRestore(restore.token, path) }
            }
        }
    // Only steer DOWN into the grid when it actually has items; otherwise leave
    // default focus search so DOWN can reach the Retry button in the error state
    // (a fixed down-target to an unattached grid would trap focus).
    val contentDownTarget = gridFocusRequester.takeIf { state.items.isNotEmpty() }
    val leadingActionRequester =
        if (state.collectionType == LibraryCollectionType.Movies) {
            shuffleButtonRequester
        } else {
            sortButtonRequester
        }
    val requestGridOnDown =
        Modifier.onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown &&
                event.key == Key.DirectionDown &&
                onRequestGridFocus?.invoke() == true
        }

    Row(
        modifier =
            modifier
                .tvLibraryChromeVisibility(chromeVisible)
                .fillMaxWidth()
                .tvFocusScope(headerScope) {
                    buildList {
                        if (state.collectionType == LibraryCollectionType.Movies) add("action:shuffle")
                        add("action:sort")
                        add("action:filter")
                    }
                },
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        title?.let { visibleTitle ->
            TvText(
                text = visibleTitle,
                style = TvScreenHeaderStyle,
                maxLines = 1,
            )
        }
        innerTabs()
        if (reserveLeadingSpaceForTabs) {
            Spacer(modifier = Modifier.weight(1f))
        }
        TvText(
            text =
                if (state.hasMore) {
                    stringResource(R.string.tv_grid_item_count_more, state.totalCount)
                } else {
                    stringResource(R.string.tv_grid_item_count, state.totalCount)
                },
            style = TvSecondaryStyle,
            color = LocalJellyfinPalette.current.textSecondary,
            maxLines = 1,
        )
        if (!reserveLeadingSpaceForTabs) {
            Spacer(modifier = Modifier.weight(1f))
        }
        if (state.collectionType == LibraryCollectionType.Movies) {
            TvButton(
                text =
                    stringResource(
                        if (state.isPreparingShuffle) {
                            R.string.tv_library_shuffle_preparing
                        } else {
                            R.string.tv_library_shuffle_all
                        },
                    ),
                onClick = onShuffleAll,
                modifier =
                    Modifier
                        .focusRequester(shuffleButtonRequester)
                        .trackHeaderFocus("action:shuffle")
                        .then(requestGridOnDown)
                        .then(
                            tabsUpRequester?.let { target -> Modifier.focusProperties { up = target } }
                                ?: Modifier,
                        ).then(
                            tabsStartRequester?.let { target -> Modifier.focusProperties { left = target } }
                                ?: Modifier,
                        ).then(
                            contentDownTarget?.let { target -> Modifier.focusProperties { down = target } }
                                ?: Modifier,
                        ),
            )
        }
        // IMPORTANT: keep these buttons ALWAYS focusable — do NOT gate `enabled` on
        // state.isLoading. Selecting a sort/filter triggers a reload (isLoading=true);
        // a disabled TvButton is non-focusable (FocusableBox uses focusable(enabled)),
        // so the focused button would drop out of the focus tree mid-reload and
        // Compose would evict focus to the navigation drawer (the "sort jump"). Staying
        // focusable keeps focus anchored here through the reload. Re-selecting during
        // a load is harmless — the paginator cancels and re-issues.
        TvButton(
            text = stringResource(R.string.tv_sort_button, librarySortLabel(state.sortBy)),
            onClick = onOpenSortPicker,
            modifier =
                Modifier
                    .focusRequester(sortButtonRequester)
                    .trackHeaderFocus("action:sort")
                    .then(requestGridOnDown)
                    .then(
                        tabsUpRequester?.let { target -> Modifier.focusProperties { up = target } }
                            ?: Modifier,
                    ).then(
                        if (state.collectionType != LibraryCollectionType.Movies) {
                            tabsStartRequester?.let { target -> Modifier.focusProperties { left = target } }
                                ?: Modifier
                        } else {
                            Modifier
                        },
                    ).then(
                        contentDownTarget?.let { target ->
                            Modifier.focusProperties { down = target }
                        } ?: Modifier,
                    ),
        )
        TvButton(
            text = stringResource(R.string.tv_library_filter_button, state.filters.activeCount),
            onClick = onOpenFilterPicker,
            modifier =
                Modifier
                    .focusRequester(filterButtonRequester)
                    .trackHeaderFocus("action:filter")
                    .then(requestGridOnDown)
                    .then(
                        tabsUpRequester?.let { target -> Modifier.focusProperties { up = target } }
                            ?: Modifier,
                    ).then(
                        contentDownTarget?.let { target ->
                            Modifier.focusProperties { down = target }
                        } ?: Modifier,
                    ),
        )
    }
}
