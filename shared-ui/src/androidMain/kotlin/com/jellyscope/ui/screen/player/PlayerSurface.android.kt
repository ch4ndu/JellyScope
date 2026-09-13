// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.jellyscope.core.domain.playback.PlayerController
import com.jellyscope.core.domain.playback.SubtitleStyle
import com.jellyscope.ui.theme.Dimensions

@Composable
actual fun PlayerSurface(
    controller: PlayerController,
    modifier: Modifier,
    resizeMode: PlayerResizeMode,
    subtitleStyle: SubtitleStyle,
    subtitleClearanceActive: Boolean,
    subtitleBottomClearance: Dp?,
    pictureInPictureRequiresLinearPlayback: Boolean,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val subtitleBottomInsetPx =
            if (subtitleClearanceActive) {
                subtitleBottomClearance
                    ?.let { clearance ->
                        with(density) { clearance.roundToPx() }
                            .coerceIn(0, with(density) { maxHeight.roundToPx() })
                    }
                    ?: (with(density) { Dimensions.mobileSubtitleClearance.roundToPx() } + WindowInsets.safeDrawing.getBottom(density))
            } else {
                0
            }
        AndroidPlayerSurfaceHost(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            resizeMode = resizeMode,
            subtitleStyle = subtitleStyle,
            subtitleBottomInsetPx = subtitleBottomInsetPx,
            mobileSubtitleBaseTextSizeSp = MOBILE_SUBTITLE_BASE_TEXT_SIZE_SP,
        )
    }
}

private const val MOBILE_SUBTITLE_BASE_TEXT_SIZE_SP = 16f

@Composable
internal actual fun PlayerPointerActivityRegistration(
    enabled: Boolean,
    onActivity: () -> Unit,
) = Unit
