// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.LibraryCollectionType
import com.jellyscope.core.domain.model.LibraryFilterSelection
import com.jellyscope.core.domain.model.LibrarySortBy
import com.jellyscope.core.domain.model.LibrarySortOrder
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.OnResumeEffect
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.library_shuffle_empty
import com.jellyscope.ui.generated.resources.library_shuffle_unavailable
import com.jellyscope.ui.generated.resources.library_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LibraryScreen(
    session: Session,
    parentId: String,
    title: String?,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onShuffleQueue: (List<String>) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialFilters: LibraryFilterSelection? = null,
    initialFilterLabel: String? = null,
    collectionType: LibraryCollectionType = LibraryCollectionType.Other,
    viewModel: LibraryBrowseViewModel =
        koinViewModel(
            parameters = { parametersOf(session, parentId.takeIf { id -> id.isNotBlank() }, collectionType) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val emptyMessage = stringResource(Res.string.library_shuffle_empty)
    val unavailableMessage = stringResource(Res.string.library_shuffle_unavailable)
    val currentOnShuffleQueue by rememberUpdatedState(onShuffleQueue)

    OnResumeEffect(viewModel::refreshSilently)

    LaunchedEffect(viewModel, emptyMessage, unavailableMessage) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryBrowseEvent.LaunchShuffle -> currentOnShuffleQueue(event.itemIds)
                LibraryBrowseEvent.ShuffleEmpty -> snackbarHostState.showSnackbar(emptyMessage)
                LibraryBrowseEvent.ShuffleUnavailable -> snackbarHostState.showSnackbar(unavailableMessage)
            }
        }
    }

    LaunchedEffect(viewModel, initialFilters) {
        initialFilters?.let { filters -> viewModel.setFilters(filters) }
    }

    LibraryContent(
        session = session,
        title = title ?: initialFilterLabel ?: stringResource(Res.string.library_title),
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onSetSort = viewModel::setSort,
        onSetFilters = viewModel::setFilters,
        onShuffleAll = viewModel::shuffleAll,
        onItemSelected = onItemSelected,
        onPlayItem = onPlayItem,
        onSettingsClick = onSettingsClick,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
private fun LibraryContent(
    session: Session,
    title: String,
    state: LibraryBrowseUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSetSort: (LibrarySortBy, LibrarySortOrder) -> Unit,
    onSetFilters: (LibraryFilterSelection) -> Unit,
    onShuffleAll: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState,
) {
    var listMode by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val topBarContent: @Composable (@Composable RowScope.() -> Unit) -> Unit = { actions ->
        AppTopBar(
            title = title,
            visible = true,
            onBack = onBack,
            onSettingsClick = onSettingsClick,
            actions = actions,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (listMode) {
            LibraryListModeContent(
                session = session,
                state = state,
                listState = listState,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
                onSetSort = onSetSort,
                onSetFilters = onSetFilters,
                onShuffleAll = onShuffleAll,
                onToggleMode = { listMode = false },
                onItemSelected = onItemSelected,
                onPlayItem = onPlayItem,
                topBarContent = topBarContent,
                bottomContentPadding = appNavigationBarContentPadding(),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LibraryBrowseGridContent(
                session = session,
                state = state,
                gridState = gridState,
                onRetry = onRetry,
                onLoadMore = onLoadMore,
                onSetSort = onSetSort,
                onSetFilters = onSetFilters,
                onShuffleAll = onShuffleAll,
                onItemSelected = onItemSelected,
                onPlayItem = onPlayItem,
                trailingControls = {
                    LibraryViewModeButton(
                        listMode = false,
                        onToggleMode = { listMode = true },
                    )
                },
                topBarContent = topBarContent,
                bottomContentPadding = appNavigationBarContentPadding(),
                modifier = Modifier.fillMaxSize(),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
    }
}
