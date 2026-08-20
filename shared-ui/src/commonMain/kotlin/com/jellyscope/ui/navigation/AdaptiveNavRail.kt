// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.jellyscope.ui.generated.resources.Res
import com.jellyscope.ui.generated.resources.nav_rail_collapse_cd
import com.jellyscope.ui.generated.resources.nav_rail_expand_cd
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
fun AdaptiveNavRail(
    items: List<TopLevelNavItem>,
    selectedRoute: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onItemSelected: (TopLevelNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        // Background spans edge-to-edge (full height, behind the status/nav
        // bars); the content below insets for the safe area so icons aren't
        // clipped by the system bars.
        modifier =
            modifier
                .fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = ADAPTIVE_RAIL_SURFACE_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = Dimensions.zero,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical),
                    ).padding(vertical = Dimensions.adaptiveNavRailVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(Dimensions.contentSpacing),
        ) {
            AdaptiveNavRailToggleButton(
                expanded = expanded,
                onClick = { onExpandedChange(!expanded) },
            )
            items.forEach { item ->
                AdaptiveNavRailItem(
                    item = item,
                    selected = item.route == selectedRoute,
                    expanded = expanded,
                    onClick = { onItemSelected(item) },
                )
            }
        }
    }
}

@Composable
private fun AdaptiveNavRailToggleButton(
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val contentDescription =
        stringResource(
            if (expanded) {
                Res.string.nav_rail_collapse_cd
            } else {
                Res.string.nav_rail_expand_cd
            },
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(Dimensions.adaptiveNavRailItemHeight)
                .clip(RoundedCornerShape(Dimensions.adaptiveNavRailItemRadius))
                .clickable(onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }.padding(horizontal = Dimensions.adaptiveNavRailItemHorizontalPadding),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = contentDescription,
            modifier = Modifier.size(Dimensions.discoveryIconSize),
            tint = LocalContentColor.current,
        )
    }
}

@Composable
private fun AdaptiveNavRailItem(
    item: TopLevelNavItem,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val contentDescription = stringResource(item.contentDescription)
    val containerColor =
        if (selected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(Dimensions.adaptiveNavRailItemHeight)
                .clip(RoundedCornerShape(Dimensions.adaptiveNavRailItemRadius))
                .background(containerColor)
                .clickable(onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    this.selected = selected
                }.padding(horizontal = Dimensions.adaptiveNavRailItemHorizontalPadding),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            TopLevelNavIconGlyph(
                icon = item.icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(Dimensions.discoveryIconSize),
            )
            if (expanded) {
                Spacer(modifier = Modifier.width(Dimensions.adaptiveNavRailLabelGap))
                Text(
                    text = stringResource(item.label),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun TopLevelNavIconGlyph(
    icon: TopLevelNavIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector =
            when (icon) {
                TopLevelNavIcon.Home -> Icons.Filled.Home
                TopLevelNavIcon.Discover -> Icons.Filled.Explore
                TopLevelNavIcon.Library -> Icons.Filled.VideoLibrary
                TopLevelNavIcon.Find -> Icons.Filled.Search
                TopLevelNavIcon.Downloads -> Icons.Filled.Download
            },
        contentDescription = contentDescription,
        modifier = modifier,
        tint = LocalContentColor.current,
    )
}

private const val ADAPTIVE_RAIL_SURFACE_ALPHA = 0.96f
