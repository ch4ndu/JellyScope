// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.LocalJellyfinPalette

/** The one bold-check glyph every watched treatment renders. */
internal val WatchedCheckIcon: ImageVector by lazy {
    ImageVector
        .Builder(
            name = "check-bold",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData =
                PathParser()
                    .parsePathString("M9,20.42L2.79,14.21L5.62,11.38L9,14.77L18.88,4.88L21.71,7.71L9,20.42Z")
                    .toNodes(),
            fill = SolidColor(Color.White),
        ).build()
}

/**
 * The app-wide watched treatment: a compact cyan circle with a bold check
 * glyph, identical on shared tiles, adaptive detail cards, and TV cards.
 */
@Composable
fun WatchedBadge(
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(Dimensions.watchedBadgeSize)
                .clip(CircleShape)
                .background(LocalJellyfinPalette.current.cyan)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = WatchedCheckIcon,
            contentDescription = null,
            tint = LocalJellyfinPalette.current.onFocusedLight,
            modifier = Modifier.size(Dimensions.watchedBadgeIconSize),
        )
    }
}
