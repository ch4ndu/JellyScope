// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jellyscope.ui.adaptive.adaptiveHorizontalContentPadding
import com.jellyscope.ui.component.DesktopScrollOrientation
import com.jellyscope.ui.component.desktopScrollInput
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.discover_collections
import com.jellyscope.ui.generated.resources.discover_genres
import com.jellyscope.ui.generated.resources.discover_studios
import com.jellyscope.ui.generated.resources.discover_suggestions
import com.jellyscope.ui.generated.resources.discover_upcoming
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiscoverSectionTabs(
    selectedSection: DiscoverSection,
    onSectionSelected: (DiscoverSection) -> Unit,
) {
    val horizontalContentPadding = adaptiveHorizontalContentPadding()
    val rowListState = rememberLazyListState()

    LazyRow(
        state = rowListState,
        modifier =
            Modifier
                .fillMaxWidth()
                .desktopScrollInput(rowListState, DesktopScrollOrientation.Horizontal),
        contentPadding = horizontalContentPadding.asPaddingValues(),
        horizontalArrangement = Arrangement.spacedBy(Dimensions.inlineSpacing),
    ) {
        items(
            items = discoverSectionsInOrder,
            key = { section -> section.name },
        ) { section ->
            FilterChip(
                selected = selectedSection == section,
                onClick = { onSectionSelected(section) },
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiary,
                        selectedLabelColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                label = { Text(discoverSectionLabel(section)) },
                modifier = Modifier.heightIn(min = Dimensions.minTouchTarget),
            )
        }
    }
}

@Composable
private fun discoverSectionLabel(section: DiscoverSection): String =
    when (section) {
        DiscoverSection.Genres -> stringResource(Res.string.discover_genres)
        DiscoverSection.Studios -> stringResource(Res.string.discover_studios)
        DiscoverSection.Collections -> stringResource(Res.string.discover_collections)
        DiscoverSection.Suggestions -> stringResource(Res.string.discover_suggestions)
        DiscoverSection.Upcoming -> stringResource(Res.string.discover_upcoming)
    }
