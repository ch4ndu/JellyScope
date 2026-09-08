// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.CardImageAspect
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.LoadMoreOnApproachEnd
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.RetryableError
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.directPlayAction
import com.jellyscope.ui.component.rememberCardImageDecode
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.library_error
import com.jellyscope.ui.generated.resources.library_item_cd
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as lazyItems

@Composable
internal fun LibraryGrid(
    session: Session,
    state: LibraryBrowseUiState,
    gridState: LazyGridState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    contentPadding: PaddingValues,
) {
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
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
    ) {
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
        if (state.error) {
            item(
                key = "library:control:page-retry",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                LibraryPageError(onRetry = onRetry)
            }
        }
    }
}

@Composable
internal fun LibraryList(
    session: Session,
    state: LibraryBrowseUiState,
    listState: LazyListState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    contentPadding: PaddingValues,
) {
    LoadMoreOnApproachEnd(
        itemCount = state.items.size,
        hasMore = state.hasMore,
        isLoading = state.isLoading,
        isLoadingMore = state.isLoadingMore,
        automaticPagingAllowed = !state.error,
        lastVisibleIndex = {
            listState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        },
        onLoadMore = onLoadMore,
    )

    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .desktopScrollInput(listState, DesktopScrollOrientation.Vertical),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        lazyItems(
            items = state.items,
            key = { item -> item.id },
        ) { item ->
            LibraryListItem(
                session = session,
                item = item,
                onClick = { onItemSelected(item) },
                onLongClick = item.directPlayAction(onPlayItem),
            )
        }
        if (state.error) {
            item(key = "library:control:page-retry") {
                LibraryPageError(onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun LibraryPageError(onRetry: () -> Unit) {
    RetryableError(
        message = stringResource(Res.string.library_error),
        retryable = true,
        onRetry = onRetry,
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun LibraryListItem(
    session: Session,
    item: MediaCardUi,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(Res.string.library_item_cd, item.title)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = Dimensions.minTouchTarget)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.let { rowModifier ->
                    if (onLongClick == null) {
                        rowModifier.combinedClickable(onClick = onClick)
                    } else {
                        rowModifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                    }
                },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
    ) {
        Row(
            modifier = Modifier.padding(Dimensions.contentSpacing),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier =
                    Modifier
                        .width(Dimensions.listThumbnailWidth.tileScaled())
                        .aspectRatio(Dimensions.listThumbnailAspectRatio),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                val imageUrl = item.backdropUrl ?: item.imageUrl
                if (imageUrl != null) {
                    AsyncImage(
                        model =
                            authenticatedImageRequest(
                                imageUrl,
                                session,
                                rememberCardImageDecode(Dimensions.listThumbnailWidth.tileScaled(), CardImageAspect.Wide),
                            ),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.metadataLine?.let { metadataLine ->
                    Text(
                        text = metadataLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.genresLine?.let { genres ->
                    Text(
                        text = genres,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
