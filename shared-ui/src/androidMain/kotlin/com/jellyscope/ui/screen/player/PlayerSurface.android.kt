// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.SubtitleStyle

@Composable
actual fun PlayerSurface(
    controller: PlayerController,
    modifier: Modifier,
    resizeMode: PlayerResizeMode,
    subtitleStyle: SubtitleStyle,
    subtitleClearanceActive: Boolean,
    pictureInPictureRequiresLinearPlayback: Boolean,
) {
    // Android owns its subtitle inset calculation; this desktop-only signal is inert here.
    AndroidPlayerSurfaceHost(
        controller = controller,
        modifier = modifier,
        resizeMode = resizeMode,
        subtitleStyle = subtitleStyle,
        subtitleBottomInsetPx = 0,
    )
}

@Composable
internal actual fun PlayerPointerActivityRegistration(
    enabled: Boolean,
    onActivity: () -> Unit,
) = Unit
