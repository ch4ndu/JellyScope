// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.jellyscope.core.domain.model.LibraryInnerView
import com.jellyscope.core.domain.model.LibraryRecommendationReason
import com.jellyscope.core.domain.model.LibraryRecommendationSection
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.LoadingIndicator
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.component.SelectableActionButton
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.home_retry_button
import com.jellyscope.ui.generated.resources.library_recommended_because_liked
import com.jellyscope.ui.generated.resources.library_recommended_because_watched
import com.jellyscope.ui.generated.resources.library_recommended_continue_watching
import com.jellyscope.ui.generated.resources.library_recommended_directed_by
import com.jellyscope.ui.generated.resources.library_recommended_generic
import com.jellyscope.ui.generated.resources.library_recommended_next_up
import com.jellyscope.ui.generated.resources.library_recommended_recently_added
import com.jellyscope.ui.generated.resources.library_recommended_starring
import com.jellyscope.ui.generated.resources.library_view_empty
import com.jellyscope.ui.generated.resources.library_view_error
import com.jellyscope.ui.generated.resources.library_view_library
import com.jellyscope.ui.generated.resources.library_view_recommended
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryInnerViewTabs(
    views: List<LibraryInnerView>,
    selectedView: LibraryInnerView,
    onViewSelected: (LibraryInnerView) -> Unit,
) {
    val padding = adaptiveHorizontalContentPadding()
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        modifier =
            Modifier
                .fillMaxWidth()
                .desktopScrollInput(listState, DesktopScrollOrientation.Horizontal),
        contentPadding = padding.asPaddingValues(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
    ) {
        items(views, key = { view -> view.name }) { view ->
            SelectableActionButton(
                label = libraryInnerViewLabel(view),
                selected = selectedView == view,
                onClick = { onViewSelected(view) },
            )
        }
    }
}

@Composable
internal fun LibraryRecommendedContent(
    session: Session,
    states: Map<LibraryRecommendationSection, LibraryRecommendationRowState>,
    listState: LazyListState,
    onRetry: (LibraryRecommendationSection) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val visibleSections = states.entries.filterNot { (_, state) -> state is LibraryRecommendationRowState.Empty }
    if (visibleSections.isEmpty()) {
        LibraryCenteredMessage(stringResource(Res.string.library_view_empty), modifier)
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().desktopScrollInput(listState, DesktopScrollOrientation.Vertical),
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(Dimensions.homeRowSpacing),
    ) {
        visibleSections.forEach { (section, state) ->
            when (state) {
                LibraryRecommendationRowState.Loading ->
                    item(key = section.name) {
                        LibraryLoadingRow(title = recommendationSectionLabel(section))
                    }
                LibraryRecommendationRowState.Error ->
                    item(key = section.name) {
                        LibraryErrorRow(
                            title = recommendationSectionLabel(section),
                            onRetry = { onRetry(section) },
                        )
                    }
                LibraryRecommendationRowState.Empty -> Unit
                is LibraryRecommendationRowState.Content ->
                    // One lazy item per shelf. Emitting a whole section's shelves
                    // inside a single item defeated vertical virtualization: the
                    // lazy column had to compose and measure every shelf in the
                    // section at once, and could not retain per-shelf scroll state.
                    //
                    // Keyed by section AND row key. The row key comes from the
                    // server hub and is only documented as unique within its
                    // section, so composing the two is what makes the key unique
                    // across the whole list — not a blanket prefix on a key that
                    // was already unique.
                    state.rows.forEach { row ->
                        item(key = "${section.name}/${row.key}") {
                            LibraryMediaShelf(
                                title = recommendationRowLabel(row),
                                items = row.items,
                                session = session,
                                onItemSelected = onItemSelected,
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun LibraryMediaShelf(
    title: String,
    items: List<MediaCardUi>,
    session: Session,
    onItemSelected: (MediaCardUi) -> Unit,
) {
    val padding = adaptiveHorizontalContentPadding()
    val listState = rememberLazyListState()
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing)) {
        Text(
            text = title,
            modifier = Modifier.padding(start = padding.start, end = padding.end),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().desktopScrollInput(listState, DesktopScrollOrientation.Horizontal),
            contentPadding = padding.asPaddingValues(),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.cardSpacing),
        ) {
            items(items, key = { item -> item.id }) { item ->
                MediaCard(item = item, session = session, onClick = { onItemSelected(item) })
            }
        }
    }
}

@Composable
private fun LibraryLoadingRow(title: String) {
    val padding = adaptiveHorizontalContentPadding()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = padding.start, end = padding.end),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator()
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LibraryErrorRow(
    title: String,
    onRetry: () -> Unit,
) {
    val padding = adaptiveHorizontalContentPadding()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = padding.start, end = padding.end),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$title — ${stringResource(Res.string.library_view_error)}", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.home_retry_button)) }
    }
}

@Composable
private fun LibraryCenteredLoading(modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

@Composable
private fun LibraryCenteredError(
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = onRetry) { Text(stringResource(Res.string.home_retry_button)) }
    }
}

@Composable
private fun LibraryCenteredMessage(
    message: String,
    modifier: Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
}

private fun libraryGridPadding(
    top: Dp,
    bottom: Dp,
): PaddingValues {
    val horizontal = Dimensions.screenPadding
    return PaddingValues(start = horizontal, top = top, end = horizontal, bottom = bottom)
}

@Composable
internal fun libraryInnerViewLabel(view: LibraryInnerView): String =
    when (view) {
        LibraryInnerView.Recommended -> stringResource(Res.string.library_view_recommended)
        LibraryInnerView.Library -> stringResource(Res.string.library_view_library)
        // Retained enum entries that are no longer offered as tabs.
        LibraryInnerView.Genres,
        LibraryInnerView.Collections,
        -> ""
    }

@Composable
private fun recommendationSectionLabel(section: LibraryRecommendationSection): String =
    when (section) {
        LibraryRecommendationSection.ContinueWatching -> stringResource(Res.string.library_recommended_continue_watching)
        LibraryRecommendationSection.RecentlyAdded -> stringResource(Res.string.library_recommended_recently_added)
        LibraryRecommendationSection.NextUp -> stringResource(Res.string.library_recommended_next_up)
        LibraryRecommendationSection.MovieRecommendations -> stringResource(Res.string.library_recommended_generic)
    }

@Composable
private fun recommendationRowLabel(row: LibraryRecommendationRowUi): String {
    val baseline = row.baselineItemName?.takeIf { name -> name.isNotBlank() }
    return when {
        row.section != LibraryRecommendationSection.MovieRecommendations -> recommendationSectionLabel(row.section)
        baseline == null -> stringResource(Res.string.library_recommended_generic)
        row.reason == LibraryRecommendationReason.SimilarToRecentlyPlayed ->
            stringResource(Res.string.library_recommended_because_watched, baseline)
        row.reason == LibraryRecommendationReason.SimilarToLikedItem ->
            stringResource(Res.string.library_recommended_because_liked, baseline)
        row.reason == LibraryRecommendationReason.HasDirector ->
            stringResource(Res.string.library_recommended_directed_by, baseline)
        row.reason == LibraryRecommendationReason.HasActor ->
            stringResource(Res.string.library_recommended_starring, baseline)
        else -> stringResource(Res.string.library_recommended_generic)
    }
}
