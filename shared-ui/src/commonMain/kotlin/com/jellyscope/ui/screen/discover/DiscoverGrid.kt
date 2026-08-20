// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.adaptive.tileScaled
import com.jellyscope.ui.component.CardImageAspect
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.LoadingIndicator
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.RetryableError
import com.jellyscope.ui.component.authenticatedImageRequest
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.rememberCardImageDecode
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.discover_because_you_watched
import com.jellyscope.ui.generated.resources.discover_collections
import com.jellyscope.ui.generated.resources.discover_empty
import com.jellyscope.ui.generated.resources.discover_error
import com.jellyscope.ui.generated.resources.discover_facet_cd
import com.jellyscope.ui.generated.resources.discover_genres
import com.jellyscope.ui.generated.resources.discover_studios
import com.jellyscope.ui.generated.resources.discover_suggestions
import com.jellyscope.ui.generated.resources.discover_upcoming
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
internal fun DiscoverSectionBody(
    session: Session,
    state: DiscoverUiState,
    selectedSection: DiscoverSection,
    gridState: LazyGridState,
    onRetryGenres: () -> Unit,
    onRetryStudios: () -> Unit,
    onRetryCollections: () -> Unit,
    onRetrySuggestions: () -> Unit,
    onRetryUpcoming: () -> Unit,
    onGenreSelected: (DiscoverFacetUi) -> Unit,
    onStudioSelected: (DiscoverFacetUi) -> Unit,
    onCollectionSelected: (MediaCardUi) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    when (selectedSection) {
        DiscoverSection.Genres ->
            DiscoverFacetGrid(
                title = stringResource(Res.string.discover_genres),
                state = state.genres,
                session = session,
                gridState = gridState,
                onRetry = onRetryGenres,
                onFacetSelected = onGenreSelected,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
        DiscoverSection.Studios ->
            DiscoverFacetGrid(
                title = stringResource(Res.string.discover_studios),
                state = state.studios,
                session = session,
                gridState = gridState,
                onRetry = onRetryStudios,
                onFacetSelected = onStudioSelected,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
        DiscoverSection.Collections ->
            DiscoverMediaGrid(
                title = stringResource(Res.string.discover_collections),
                state = state.collections,
                session = session,
                gridState = gridState,
                onRetry = onRetryCollections,
                onItemSelected = onCollectionSelected,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
        DiscoverSection.Suggestions ->
            DiscoverSuggestionsGrid(
                title = stringResource(Res.string.discover_suggestions),
                state = state.suggestions,
                session = session,
                gridState = gridState,
                onRetry = onRetrySuggestions,
                onItemSelected = onItemSelected,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
        DiscoverSection.Upcoming ->
            DiscoverMediaGrid(
                title = stringResource(Res.string.discover_upcoming),
                state = state.upcoming,
                session = session,
                gridState = gridState,
                onRetry = onRetryUpcoming,
                onItemSelected = onItemSelected,
                topContentPadding = topContentPadding,
                bottomContentPadding = bottomContentPadding,
                modifier = modifier,
            )
    }
}

@Composable
private fun DiscoverFacetGrid(
    title: String,
    state: DiscoverListState<DiscoverFacetUi>,
    session: Session,
    gridState: LazyGridState,
    onRetry: () -> Unit,
    onFacetSelected: (DiscoverFacetUi) -> Unit,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val messageModifier = modifier.padding(top = topContentPadding, bottom = bottomContentPadding)
    when (state) {
        DiscoverListState.Loading -> DiscoverCenteredMessage(messageModifier) { DiscoverLoading() }
        is DiscoverListState.Error ->
            DiscoverCenteredMessage(messageModifier) {
                DiscoverError(title = title, retryable = state.retryable, onRetry = onRetry)
            }
        DiscoverListState.Empty -> DiscoverCenteredMessage(messageModifier) { DiscoverEmpty(title) }
        is DiscoverListState.Content ->
            if (state.items.isEmpty()) {
                DiscoverCenteredMessage(messageModifier) { DiscoverEmpty(title) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(Dimensions.libraryCardWidth.tileScaled()),
                    modifier =
                        modifier
                            .fillMaxSize()
                            .desktopScrollInput(gridState, DesktopScrollOrientation.Vertical),
                    state = gridState,
                    contentPadding = discoverGridPadding(topContentPadding, bottomContentPadding),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
                ) {
                    gridItems(
                        items = state.items,
                        key = { facet -> "${facet.id.orEmpty()}-${facet.name}" },
                    ) { facet ->
                        FacetCard(
                            session = session,
                            facet = facet,
                            onClick = { onFacetSelected(facet) },
                        )
                    }
                }
            }
    }
}

@Composable
private fun DiscoverMediaGrid(
    title: String,
    state: DiscoverListState<MediaCardUi>,
    session: Session,
    gridState: LazyGridState,
    onRetry: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val messageModifier = modifier.padding(top = topContentPadding, bottom = bottomContentPadding)
    when (state) {
        DiscoverListState.Loading -> DiscoverCenteredMessage(messageModifier) { DiscoverLoading() }
        is DiscoverListState.Error ->
            DiscoverCenteredMessage(messageModifier) {
                DiscoverError(title = title, retryable = state.retryable, onRetry = onRetry)
            }
        DiscoverListState.Empty -> DiscoverCenteredMessage(messageModifier) { DiscoverEmpty(title) }
        is DiscoverListState.Content ->
            if (state.items.isEmpty()) {
                DiscoverCenteredMessage(messageModifier) { DiscoverEmpty(title) }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(Dimensions.gridMinCellWidth.tileScaled()),
                    modifier =
                        modifier
                            .fillMaxSize()
                            .desktopScrollInput(gridState, DesktopScrollOrientation.Vertical),
                    state = gridState,
                    contentPadding = discoverGridPadding(topContentPadding, bottomContentPadding),
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
                        )
                    }
                }
            }
    }
}

@Composable
private fun DiscoverSuggestionsGrid(
    title: String,
    state: DiscoverSuggestionsState,
    session: Session,
    gridState: LazyGridState,
    onRetry: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val messageModifier = modifier.padding(top = topContentPadding, bottom = bottomContentPadding)
    when (state) {
        DiscoverSuggestionsState.Loading -> DiscoverCenteredMessage(messageModifier) { DiscoverLoading() }
        is DiscoverSuggestionsState.Error ->
            DiscoverCenteredMessage(messageModifier) {
                DiscoverError(title = title, retryable = state.retryable, onRetry = onRetry)
            }
        DiscoverSuggestionsState.Empty -> DiscoverCenteredMessage(messageModifier) { DiscoverEmpty(title) }
        is DiscoverSuggestionsState.Content ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(Dimensions.gridMinCellWidth.tileScaled()),
                modifier =
                    modifier
                        .fillMaxSize()
                        .desktopScrollInput(gridState, DesktopScrollOrientation.Vertical),
                state = gridState,
                contentPadding = discoverGridPadding(topContentPadding, bottomContentPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
                verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
            ) {
                state.seedItem?.let { seed ->
                    item(
                        key = "suggestions-seed",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        Text(
                            text = stringResource(Res.string.discover_because_you_watched, seed.title),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (state.items.isEmpty()) {
                    item(
                        key = "suggestions-empty",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        DiscoverEmpty(title)
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
                    )
                }
            }
    }
}

@Composable
private fun DiscoverCenteredMessage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun discoverGridPadding(
    top: Dp,
    bottom: Dp,
): PaddingValues {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    return horizontalContentPadding.asPaddingValues(
        top = top,
        bottom = Dimensions.screenPadding + bottom,
    )
}

@Composable
private fun FacetCard(
    session: Session,
    facet: DiscoverFacetUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(Res.string.discover_facet_cd, facet.name)
    Column(
        modifier =
            modifier
                .width(Dimensions.libraryCardWidth.tileScaled())
                .heightIn(min = Dimensions.minTouchTarget)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(Dimensions.libraryCardAspectRatio),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val imageUrl = facet.imageUrl
                if (imageUrl != null) {
                    AsyncImage(
                        model =
                            authenticatedImageRequest(
                                imageUrl,
                                session,
                                rememberCardImageDecode(Dimensions.libraryCardWidth.tileScaled(), CardImageAspect.Wide),
                            ),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = facet.name.take(FACET_PLACEHOLDER_LENGTH),
                        modifier = Modifier.padding(Dimensions.contentSpacing),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Text(
            text = facet.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DiscoverLoading() {
    LoadingIndicator()
}

@Composable
private fun DiscoverEmpty(title: String) {
    Text(
        text = stringResource(Res.string.discover_empty, title),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DiscoverError(
    title: String,
    retryable: Boolean,
    onRetry: () -> Unit,
) {
    RetryableError(
        message = stringResource(Res.string.discover_error, title),
        retryable = retryable,
        onRetry = onRetry,
        horizontalAlignment = Alignment.Start,
    )
}

private const val FACET_PLACEHOLDER_LENGTH = 1
