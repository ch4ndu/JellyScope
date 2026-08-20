// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.ui.screen.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.jellyscope.ui.adaptive.WindowWidthTier
import com.jellyscope.ui.theme.Dimensions
import com.jellyscope.ui.theme.JellyfinPalette
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import com.jellyscope.ui.theme.LocalJellyfinPalette

@Composable
internal fun SettingsBackdrop(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthTier = WindowWidthTier.fromAvailableWidth(maxWidth)
        val useLandscapeBackdrop = widthTier != WindowWidthTier.Compact || maxWidth >= maxHeight
        val palette = LocalJellyfinPalette.current
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(LocalAppBackgroundBrush.current),
        ) {
            rememberSettingsBackdropImage(useLandscapeBackdrop)?.let { backdrop ->
                Image(
                    bitmap = backdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(palette.navy.copy(alpha = Dimensions.settingsBackdropDimAlpha)),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(settingsBackdropScrim(palette, useLandscapeBackdrop, isRtl)),
            )
        }
    }
}

private fun settingsBackdropScrim(
    palette: JellyfinPalette,
    useLandscapeBackdrop: Boolean,
    isRtl: Boolean,
): Brush =
    if (useLandscapeBackdrop) {
        if (isRtl) {
            Brush.horizontalGradient(
                colorStops =
                    arrayOf(
                        0f to
                            palette.gradientBottom.copy(
                                alpha = Dimensions.settingsBackdropContentScrimEndAlpha,
                            ),
                        0.44f to
                            palette.gradientBottom.copy(
                                alpha = Dimensions.settingsBackdropContentScrimMiddleAlpha,
                            ),
                        1f to
                            palette.navy.copy(
                                alpha = Dimensions.settingsBackdropContentScrimStartAlpha,
                            ),
                    ),
            )
        } else {
            Brush.horizontalGradient(
                colorStops =
                    arrayOf(
                        0f to palette.navy.copy(alpha = Dimensions.settingsBackdropContentScrimStartAlpha),
                        0.56f to
                            palette.gradientBottom.copy(
                                alpha = Dimensions.settingsBackdropContentScrimMiddleAlpha,
                            ),
                        1f to
                            palette.gradientBottom.copy(
                                alpha = Dimensions.settingsBackdropContentScrimEndAlpha,
                            ),
                    ),
            )
        }
    } else {
        Brush.verticalGradient(
            colorStops =
                arrayOf(
                    0f to palette.navy.copy(alpha = Dimensions.settingsBackdropContentScrimEndAlpha),
                    0.48f to palette.gradientBottom.copy(alpha = Dimensions.settingsBackdropContentScrimMiddleAlpha),
                    1f to palette.gradientBottom.copy(alpha = Dimensions.settingsBackdropContentScrimStartAlpha),
                ),
        )
    }
