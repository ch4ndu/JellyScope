// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.person

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.HeroDetailBackButton
import com.jellyscope.ui.component.LoadingIndicator
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.RetryableError
import com.jellyscope.ui.component.appNavigationBarContentPadding
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.directPlayAction
import com.jellyscope.ui.component.heroDetailMessageTopPadding
import com.jellyscope.ui.component.heroDetailTopPadding
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.person_back_cd
import com.jellyscope.ui.generated.resources.person_empty
import com.jellyscope.ui.generated.resources.person_error
import com.jellyscope.ui.generated.resources.person_load_more
import com.jellyscope.ui.generated.resources.person_loading
import com.jellyscope.ui.generated.resources.person_loading_more
import com.jellyscope.ui.generated.resources.person_movies
import com.jellyscope.ui.generated.resources.person_series
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PersonScreen(
    session: Session,
    personId: String,
    onBack: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonViewModel =
        koinViewModel(
            parameters = { parametersOf(session, personId) },
        ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PersonContent(
        session = session,
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        onItemSelected = onItemSelected,
        onPlayItem = onPlayItem,
        modifier = modifier,
    )
}

@Composable
internal fun PersonContent(
    session: Session,
    state: PersonUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        PersonUiState.Loading ->
            PersonCenteredMessage(
                text = stringResource(Res.string.person_loading),
                onBack = onBack,
                modifier = modifier,
            )
        is PersonUiState.Error ->
            PersonError(
                retryable = state.retryable,
                onBack = onBack,
                onRetry = onRetry,
                modifier = modifier,
            )
        is PersonUiState.Content ->
            PersonBody(
                session = session,
                content = state,
                onBack = onBack,
                onLoadMore = onLoadMore,
                onItemSelected = onItemSelected,
                onPlayItem = onPlayItem,
                modifier = modifier,
            )
    }
}

@Composable
private fun PersonBody(
    session: Session,
    content: PersonUiState.Content,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val backContentDescription = stringResource(Res.string.person_back_cd)

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .desktopScrollInput(listState, DesktopScrollOrientation.Vertical),
            contentPadding =
                PaddingValues(
                    top = heroDetailTopPadding(),
                    bottom = appNavigationBarContentPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.detailSectionSpacing),
        ) {
            item(key = "header") {
                PersonHeader(
                    session = session,
                    header = content.header,
                )
            }
            if (content.movies.isNotEmpty()) {
                item(key = "movies") {
                    PersonMediaRow(
                        title = stringResource(Res.string.person_movies),
                        session = session,
                        items = content.movies,
                        onItemSelected = onItemSelected,
                        onPlayItem = onPlayItem,
                    )
                }
            }
            if (content.series.isNotEmpty()) {
                item(key = "series") {
                    PersonMediaRow(
                        title = stringResource(Res.string.person_series),
                        session = session,
                        items = content.series,
                        onItemSelected = onItemSelected,
                        onPlayItem = onPlayItem,
                    )
                }
            }
            if (content.movies.isEmpty() && content.series.isEmpty() && !content.isLoadingMore) {
                item(key = "empty") {
                    val horizontalContentPadding = adaptiveHorizontalContentPadding()
                    Text(
                        text = stringResource(Res.string.person_empty),
                        modifier =
                            Modifier.padding(
                                start = horizontalContentPadding.start,
                                end = horizontalContentPadding.end,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (content.error) {
                item(key = "page-error") {
                    PersonInlineError(onRetry = onLoadMore)
                }
            }
            if (content.isLoadingMore) {
                item(key = "loading-more") {
                    val horizontalContentPadding = adaptiveHorizontalContentPadding()
                    LoadingRow(
                        text = stringResource(Res.string.person_loading_more),
                        modifier =
                            Modifier.padding(
                                start = horizontalContentPadding.start,
                                end = horizontalContentPadding.end,
                            ),
                    )
                }
            } else if (content.hasMore) {
                item(key = "load-more") {
                    val horizontalContentPadding = adaptiveHorizontalContentPadding()
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = horizontalContentPadding.start,
                                    end = horizontalContentPadding.end,
                                ),
                    ) {
                        Button(
                            onClick = onLoadMore,
                            modifier =
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .heightIn(min = Dimensions.minTouchTarget),
                        ) {
                            Text(stringResource(Res.string.person_load_more))
                        }
                    }
                }
            }
        }
        HeroDetailBackButton(
            contentDescription = backContentDescription,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Composable
private fun PersonMediaRow(
    title: String,
    session: Session,
    items: List<MediaCardUi>,
    onItemSelected: (MediaCardUi) -> Unit,
    onPlayItem: (String, Long) -> Unit,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val rowListState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        Text(
            text = title,
            modifier =
                Modifier.padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyRow(
            state = rowListState,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .desktopScrollInput(rowListState, DesktopScrollOrientation.Horizontal),
            contentPadding = horizontalContentPadding.asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        ) {
            items(
                items = items,
                key = { item -> item.id },
            ) { item ->
                MediaCard(
                    item = item,
                    session = session,
                    onClick = { onItemSelected(item) },
                    onLongClick = item.directPlayAction(onPlayItem),
                )
            }
        }
    }
}

@Composable
private fun PersonCenteredMessage(
    text: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backContentDescription = stringResource(Res.string.person_back_cd)
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = horizontalContentPadding.start,
                        top = heroDetailMessageTopPadding(),
                        end = horizontalContentPadding.end,
                        bottom = appNavigationBarContentPadding(),
                    ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
        ) {
            LoadingRow(text = text)
        }
        HeroDetailBackButton(
            contentDescription = backContentDescription,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Composable
private fun PersonError(
    retryable: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.person_error)
    val backContentDescription = stringResource(Res.string.person_back_cd)
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = horizontalContentPadding.start,
                        top = heroDetailMessageTopPadding(),
                        end = horizontalContentPadding.end,
                        bottom = appNavigationBarContentPadding(),
                    ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.formSpacing),
        ) {
            RetryableError(
                message = title,
                retryable = retryable,
                onRetry = onRetry,
                textStyle = MaterialTheme.typography.bodyLarge,
                horizontalAlignment = Alignment.Start,
            )
        }
        HeroDetailBackButton(
            contentDescription = backContentDescription,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Composable
private fun PersonInlineError(onRetry: () -> Unit) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    RetryableError(
        message = stringResource(Res.string.person_error),
        retryable = true,
        onRetry = onRetry,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        horizontalAlignment = Alignment.Start,
    )
}

@Composable
private fun LoadingRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
