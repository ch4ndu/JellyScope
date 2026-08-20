// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.ui.screen.player.AndroidPlayerSurfaceHost
import com.jellyscope.ui.screen.player.PlayerResizeMode

@Composable
internal fun TvPlayerSurface(
    controller: PlayerController,
    subtitlesRaised: Boolean,
    resizeMode: PlayerResizeMode,
    subtitleStyle: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    val subtitleBottomInsetPx =
        with(LocalDensity.current) {
            if (subtitlesRaised) {
                TvDimens.playerFloatingPanelBottomPadding.roundToPx()
            } else {
                0
            }
        }
    AndroidPlayerSurfaceHost(
        controller = controller,
        modifier = modifier,
        resizeMode = resizeMode,
        subtitleStyle = subtitleStyle,
        subtitleBottomInsetPx = subtitleBottomInsetPx,
    )
}
