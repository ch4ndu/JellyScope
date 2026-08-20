// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.jellyscope.tv.R
import com.jellyscope.ui.screen.discover.DiscoverSection
import com.jellyscope.ui.screen.discover.discoverSectionsInOrder
import com.jellyscope.ui.screen.discover.sectionHasItems
import com.jellyscope.ui.theme.LocalJellyfinPalette
import com.jellyscope.ui.component.DetailBodyStyle as TvBodyStyle
import com.jellyscope.ui.component.DetailText as TvText
import com.jellyscope.ui.component.FocusableBox as TvFocusableBox

@Composable
internal fun TvDiscoverSectionTabs(
    selectedSection: DiscoverSection,
    sectionHasItems: Boolean,
    selectedTabRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    onSectionSelected: (DiscoverSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        discoverSectionsInOrder.forEach { section ->
            TvDiscoverSectionTab(
                label = discoverSectionLabel(section),
                selected = section == selectedSection,
                focusRequester = selectedTabRequester.takeIf { section == selectedSection },
                downTarget = gridFocusRequester.takeIf { sectionHasItems },
                onClick = { onSectionSelected(section) },
            )
        }
    }
}

@Composable
private fun TvDiscoverSectionTab(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    downTarget: FocusRequester?,
    onClick: () -> Unit,
) {
    TvFocusableBox(
        onClick = onClick,
        modifier =
            Modifier
                .defaultMinSize(minHeight = TvDimens.minButtonHeight)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .then(downTarget?.let { target -> Modifier.focusProperties { down = target } } ?: Modifier),
        contentDescription = label,
        contentPadding =
            PaddingValues(
                horizontal = TvDimens.buttonHorizontalPadding,
                vertical = TvDimens.cardTitleGap,
            ),
        backgroundColor =
            if (selected) {
                LocalJellyfinPalette.current.cyan.copy(alpha = 0.18f)
            } else {
                LocalJellyfinPalette.current.surfaceRaised
            },
        focusedBackgroundColor = LocalJellyfinPalette.current.cyan,
        contentAlignment = Alignment.Center,
    ) { focused ->
        TvText(
            text = label,
            style =
                TvBodyStyle.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                ),
            color =
                when {
                    focused -> LocalJellyfinPalette.current.onFocusedLight
                    selected -> LocalJellyfinPalette.current.cyan
                    else -> LocalJellyfinPalette.current.textSecondary
                },
            maxLines = 1,
        )
    }
}

@Composable
private fun discoverSectionLabel(section: DiscoverSection): String =
    when (section) {
        DiscoverSection.Genres -> stringResource(R.string.tv_discover_genres)
        DiscoverSection.Studios -> stringResource(R.string.tv_discover_studios)
        DiscoverSection.Collections -> stringResource(R.string.tv_discover_collections)
        DiscoverSection.Suggestions -> stringResource(R.string.tv_discover_suggestions)
        DiscoverSection.Upcoming -> stringResource(R.string.tv_discover_upcoming)
    }

// Prefer the grid (its entry item is always composed via entryTarget); fall
// back to the section tabs when the current section has no grid items, so
// RIGHT-from-rail always moves focus into the content area.
