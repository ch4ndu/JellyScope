// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.compose.navigation
import com.jellyscope.ui.adaptive.AdaptiveContentPane
import com.jellyscope.ui.adaptive.LocalStartSafeDrawingConsumed
import com.jellyscope.ui.component.SafeAreaContent
import com.jellyscope.ui.theme.Dimensions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LoggedInScreenFrame(
    contentWindowInsets: WindowInsets,
    startSafeDrawingConsumed: Boolean = false,
    content: @Composable () -> Unit,
) {
    SafeAreaContent(contentWindowInsets = contentWindowInsets) {
        CompositionLocalProvider(
            LocalStartSafeDrawingConsumed provides startSafeDrawingConsumed,
        ) {
            AdaptiveContentPane {
                content()
            }
        }
    }
}

@Composable
internal fun LoggedInBottomBar(
    currentRoute: String?,
    items: List<TopLevelNavItem> = TopLevelNavItems,
    onItemSelected: (TopLevelNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
    ) {
        items.forEach { item ->
            val selected = item.route == selectedTopLevelRoute(currentRoute)
            val itemContentDescription = stringResource(item.contentDescription)
            NavigationBarItem(
                selected = selected,
                onClick = { onItemSelected(item) },
                icon = {
                    TopLevelNavIconGlyph(
                        icon = item.icon,
                        contentDescription = itemContentDescription,
                        modifier = Modifier.size(Dimensions.discoveryIconSize),
                    )
                },
                label = {
                    Text(stringResource(item.label))
                },
                modifier =
                    Modifier
                        .semantics {
                            contentDescription = itemContentDescription
                        },
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
    }
}
