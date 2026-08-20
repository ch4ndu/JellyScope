// SPDX-License-Identifier: MPL-2.0

package com.jellyscope.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import com.jellyscope.ui.screen.settings.rememberSettingsBackdropImage
import com.jellyscope.ui.theme.LocalAppBackgroundBrush
import com.jellyscope.ui.theme.LocalJellyfinPalette

/**
 * Settings owns no full-screen background. The hosted drawer installs this one
 * static, palette-aware layer so the collapsed rail and route content share the
 * same physical backdrop without a second decode or a transition seam.
 */
@Composable
internal fun TvSettingsBackdrop(modifier: Modifier = Modifier) {
    val palette = LocalJellyfinPalette.current
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalAppBackgroundBrush.current),
    ) {
        rememberSettingsBackdropImage(useLandscape = true)?.let { backdrop ->
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
                    .background(palette.navy.copy(alpha = TvDimens.settingsBackdropDimAlpha)),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops =
                                arrayOf(
                                    0f to palette.navy.copy(alpha = TvDimens.settingsBackdropContentScrimStartAlpha),
                                    0.56f to palette.gradientBottom.copy(alpha = TvDimens.settingsBackdropContentScrimMiddleAlpha),
                                    1f to palette.gradientBottom.copy(alpha = TvDimens.settingsBackdropContentScrimEndAlpha),
                                ),
                        ),
                    ),
        )
    }
}
