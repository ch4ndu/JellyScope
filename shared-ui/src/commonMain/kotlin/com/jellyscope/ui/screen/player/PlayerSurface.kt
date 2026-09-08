// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.SubtitleStyle

@Composable
expect fun PlayerSurface(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    resizeMode: PlayerResizeMode = PlayerResizeMode.Fit,
    subtitleStyle: SubtitleStyle = SubtitleStyle(),
    subtitleClearanceActive: Boolean,
    pictureInPictureRequiresLinearPlayback: Boolean,
)

@Composable
internal expect fun PlayerPointerActivityRegistration(
    enabled: Boolean,
    onActivity: () -> Unit,
)
