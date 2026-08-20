// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.TooltipIconButton
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.component.isDirectlyPlayable
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.home_empty_all_message
import com.jellyscope.ui.generated.resources.home_empty_all_title
import com.jellyscope.ui.generated.resources.home_empty_row
import com.jellyscope.ui.generated.resources.home_error_row
import com.jellyscope.ui.generated.resources.home_loading_row
import com.jellyscope.ui.generated.resources.home_retry_button
import com.jellyscope.ui.generated.resources.home_view_all_cd
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeRowSection(
    title: String,
    row: HomeRow,
    rowState: RowState,
    session: Session,
    onRetry: (HomeRow) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    onViewAllSelected: ((HomeRow) -> Unit)?,
    onPlayItem: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val rowListState = rememberLazyListState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
    ) {
        when (rowState) {
            RowState.Loading -> {
                HomeRowHeader(title = title)
                LoadingRowMessage(stringResource(Res.string.home_loading_row))
            }
            RowState.Empty -> {
                HomeRowHeader(title = title)
                InlineRowMessage(stringResource(Res.string.home_empty_row))
            }
            RowState.Error -> {
                HomeRowHeader(title = title)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = horizontalContentPadding.start,
                                end = horizontalContentPadding.end,
                            ),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.home_error_row),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { onRetry(row) },
                        modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
                    ) {
                        Text(stringResource(Res.string.home_retry_button))
                    }
                }
            }
            is RowState.Content -> {
                HomeRowHeader(
                    title = title,
                    onViewAllSelected =
                        onViewAllSelected?.let { viewAllSelected ->
                            { viewAllSelected(row) }
                        },
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
                        items = rowState.items,
                        key = { item -> item.id },
                    ) { item ->
                        MediaCard(
                            item = item,
                            session = session,
                            onClick = { onItemSelected(item) },
                            onLongClick =
                                if (item.kind.isDirectlyPlayable()) {
                                    {
                                        onPlayItem(
                                            item.id,
                                            item.resumePositionTicks ?: 0L,
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRowHeader(
    title: String,
    modifier: Modifier = Modifier,
    onViewAllSelected: (() -> Unit)? = null,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        if (onViewAllSelected != null) {
            val contentDescription = stringResource(Res.string.home_view_all_cd, title)
            TooltipIconButton(
                label = contentDescription,
                onClick = onViewAllSelected,
                modifier = Modifier.size(Dimensions.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.GridView,
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
private fun LoadingRowMessage(text: String) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimensions.progressIndicatorSize),
            strokeWidth = Dimensions.progressIndicatorStroke,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun HomeEmptyState(modifier: Modifier = Modifier) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(Res.string.home_empty_all_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.home_empty_all_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InlineRowMessage(text: String) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()

    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = horizontalContentPadding.start,
                    end = horizontalContentPadding.end,
                ),
        style = MaterialTheme.typography.bodyMedium,
    )
}
