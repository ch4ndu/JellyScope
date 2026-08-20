// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.detail

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription

internal enum class DetailActionIcon {
    Restart,
    Watched,
    Unwatched,
    Favorite,
    FavoriteOutline,
    Info,
    Version,
    Audio,
    Subtitles,
    ExpandMore,
    Trailer,
}

@Composable
internal fun DetailIcon(
    icon: DetailActionIcon,
    color: Color,
    modifier: Modifier = Modifier.size(DetailDimens.playerIconSize),
) {
    Icon(
        imageVector =
            when (icon) {
                DetailActionIcon.Restart -> DetailIcons.Restart
                DetailActionIcon.Watched -> Icons.Filled.CheckCircle
                DetailActionIcon.Unwatched -> Icons.Outlined.CheckCircle
                DetailActionIcon.Favorite -> DetailIcons.Heart
                DetailActionIcon.FavoriteOutline -> DetailIcons.HeartOutline
                DetailActionIcon.Info -> DetailIcons.InformationOutline
                DetailActionIcon.Version -> Icons.Filled.Movie
                DetailActionIcon.Audio -> Icons.Filled.Audiotrack
                DetailActionIcon.Subtitles -> Icons.Filled.ClosedCaption
                DetailActionIcon.ExpandMore -> Icons.Filled.KeyboardArrowDown
                DetailActionIcon.Trailer -> Icons.Filled.Movie
            },
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}
