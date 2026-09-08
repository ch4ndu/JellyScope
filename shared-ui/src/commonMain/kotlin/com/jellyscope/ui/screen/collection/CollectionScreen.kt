// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.AppTopBar
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.LoadMoreOnApproachEnd
import com.jellyscope.ui.component.LoadingIndicator
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.RetryableError
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.appTopBarContentPadding
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.directPlayAction
import com.jellyscope.ui.component.rememberAutoHidingTopBarVisible
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.collection_empty
import com.jellyscope.ui.generated.resources.collection_error
import com.jellyscope.ui.generated.resources.collection_loading
import com.jellyscope.ui.generated.resources.collection_title
import com.jellyscope.ui.generated.resources.collection_total_count
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
fun CollectionScreen(
    session: Session,
    collectionId: String,
    title: String?,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionViewModel =
        koinViewModel(
            parameters = { parametersOf(session, collectionId) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CollectionContent(
        session = session,
        title = title ?: stringResource(Res.string.collection_title),
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onItemSelected = onItemSelected,
        onPlayItem = onPlayItem,
        onSettingsClick = onSettingsClick,
        modifier = modifier,
    )
}

@Composable
internal fun CollectionContent(
    session: Session,
    title: String,
    state: CollectionUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val topBarVisible = rememberAutoHidingTopBarVisible(gridState)
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val topContentPadding = appTopBarContentPadding()
    val bottomContentPadding = appNavigationBarContentPadding()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading && state.items.isEmpty() ->
                LoadingMessage(
                    Modifier
                        .align(Alignment.Center)
                        .padding(
                            start = horizontalContentPadding.start,
                            top = topContentPadding,
                            end = horizontalContentPadding.end,
                            bottom = bottomContentPadding,
                        ),
                )
            state.error && state.items.isEmpty() ->
                ErrorMessage(
                    onRetry = onRetry,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(
                                start = horizontalContentPadding.start,
                                top = topContentPadding,
                                end = horizontalContentPadding.end,
                                bottom = bottomContentPadding,
                            ),
                )
            state.items.isEmpty() ->
                Text(
                    text = stringResource(Res.string.collection_empty),
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(
                                start = horizontalContentPadding.start,
                                top = topContentPadding,
                                end = horizontalContentPadding.end,
                                bottom = bottomContentPadding,
                            ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            else -> {
                LoadMoreOnApproachEnd(
                    itemCount = state.items.size,
                    hasMore = state.hasMore,
                    isLoading = state.isLoading,
                    isLoadingMore = state.isLoadingMore,
                    automaticPagingAllowed = !state.error,
                    lastVisibleIndex = {
                        gridState.layoutInfo.visibleItemsInfo
                            .lastOrNull()
                            ?.index ?: 0
                    },
                    onLoadMore = onLoadMore,
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(Dimensions.gridMinCellWidth.tileScaled()),
                    state = gridState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .desktopScrollInput(gridState, DesktopScrollOrientation.Vertical),
                    contentPadding =
                        horizontalContentPadding.asPaddingValues(
                            top = topContentPadding,
                            bottom = bottomContentPadding,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
                ) {
                    if (state.totalCount > 0) {
                        item(
                            key = "total-count",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            CollectionTotalCount(totalCount = state.totalCount)
                        }
                    }
                    gridItems(
                        items = state.items,
                        key = { item -> item.id },
                    ) { item ->
                        MediaCard(
                            item = item,
                            session = session,
                            onClick = { onItemSelected(item) },
                            onLongClick = item.directPlayAction(onPlayItem),
                        )
                    }
                    if (state.isLoadingMore) {
                        item(
                            key = "loading-more",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            LoadingMessage()
                        }
                    }
                    if (state.error && state.items.isNotEmpty()) {
                        item(
                            key = "collection:control:page-retry",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            ErrorMessage(onRetry = onRetry)
                        }
                    }
                }
            }
        }
        AppTopBar(
            title = title,
            visible = topBarVisible,
            onBack = onBack,
            onSettingsClick = onSettingsClick,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun CollectionTotalCount(totalCount: Int) {
    if (totalCount <= 0) {
        return
    }
    Text(
        text = stringResource(Res.string.collection_total_count, totalCount),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LoadingMessage(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator()
        Text(
            text = stringResource(Res.string.collection_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ErrorMessage(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RetryableError(
        message = stringResource(Res.string.collection_error),
        retryable = true,
        onRetry = onRetry,
        modifier = modifier,
    )
}
