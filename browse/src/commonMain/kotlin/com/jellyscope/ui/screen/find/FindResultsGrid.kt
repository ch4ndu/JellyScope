// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.find

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.jellyscope.core.domain.model.Session
import com.jellyscope.ui.component.MediaCard
import com.jellyscope.ui.component.MediaCardUi
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.find_results_episodes
import com.jellyscope.ui.generated.resources.find_results_movies
import com.jellyscope.ui.generated.resources.find_results_shows
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.lazy.grid.items as gridItems

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyGridScope.resultsContent(
    session: Session,
    state: FindUiState,
    onResultTabSelected: (FindResultTab) -> Unit,
    onItemSelected: (MediaCardUi) -> Unit,
) {
    item(
        key = "result-tabs",
        span = { GridItemSpan(maxLineSpan) },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            resultTabChoices.forEach { tab ->
                FindChip(
                    label = stringResource(tab.label),
                    selected = state.selectedResultTab == tab.tab,
                    onClick = { onResultTabSelected(tab.tab) },
                )
            }
        }
    }
    if (state.selectedResultTab == FindResultTab.All || state.selectedResultTab == FindResultTab.Movies) {
        resultSection(
            keyPrefix = "movies",
            title = Res.string.find_results_movies,
            items = state.groupedResults.movies,
            session = session,
            onItemSelected = onItemSelected,
        )
    }
    if (state.selectedResultTab == FindResultTab.All || state.selectedResultTab == FindResultTab.Shows) {
        resultSection(
            keyPrefix = "shows",
            title = Res.string.find_results_shows,
            items = state.groupedResults.shows,
            session = session,
            onItemSelected = onItemSelected,
        )
    }
    if (state.selectedResultTab == FindResultTab.All || state.selectedResultTab == FindResultTab.Episodes) {
        resultSection(
            keyPrefix = "episodes",
            title = Res.string.find_results_episodes,
            items = state.groupedResults.episodes,
            session = session,
            onItemSelected = onItemSelected,
        )
    }
}

private fun LazyGridScope.resultSection(
    keyPrefix: String,
    title: StringResource,
    items: List<MediaCardUi>,
    session: Session,
    onItemSelected: (MediaCardUi) -> Unit,
) {
    if (items.isEmpty()) {
        return
    }

    item(
        key = "$keyPrefix-title",
        span = { GridItemSpan(maxLineSpan) },
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    gridItems(
        items = items,
        key = { item -> "$keyPrefix-${item.id}" },
    ) { item ->
        // Keyed on the whole item, not its id: the lambda closes over item state
        // that changes while the id stays stable.
        val onItemClick = remember(item, onItemSelected) { { onItemSelected(item) } }
        MediaCard(
            item = item,
            session = session,
            onClick = onItemClick,
            modifier =
                Modifier
                    .fillMaxWidth(),
            fillWidth = true,
        )
    }
}
